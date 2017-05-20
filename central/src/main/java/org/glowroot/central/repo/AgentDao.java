/*
 * Copyright 2015-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.central.repo;

import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.google.common.base.MoreObjects;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.central.util.Cache;
import org.glowroot.central.util.Cache.CacheLoader;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.Sessions;
import org.glowroot.common.config.AgentRollupConfig;
import org.glowroot.common.config.ImmutableAgentRollupConfig;
import org.glowroot.common.repo.AgentRepository;
import org.glowroot.common.repo.ConfigRepository.OptimisticLockException;
import org.glowroot.common.repo.ImmutableAgentRollup;
import org.glowroot.common.util.Styles;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;

import static com.google.common.base.Preconditions.checkNotNull;

public class AgentDao implements AgentRepository {

    private static final Logger logger = LoggerFactory.getLogger(AgentDao.class);

    private static final String WITH_LCS =
            "with compaction = { 'class' : 'LeveledCompactionStrategy' }";

    private final Session session;

    private final PreparedStatement readPS;
    private final PreparedStatement readParentIdPS;
    private final PreparedStatement insertPS;
    private final PreparedStatement insertLastCaptureTimePS;

    private final PreparedStatement isAgentPS;

    private final PreparedStatement readDisplayPS;
    private final PreparedStatement updateDisplayPS;

    private final PreparedStatement deletePS;

    private final Cache<String, Optional<String>> agentRollupIdCache;
    private final Cache<String, Optional<AgentRollupConfig>> agentRollupConfigCache;

    public AgentDao(Session session, ClusterManager clusterManager) throws Exception {
        this.session = session;

        Sessions.execute(session, "create table if not exists agent_rollup (one int,"
                + " agent_rollup_id varchar, parent_agent_rollup_id varchar, display varchar,"
                + " agent boolean, last_capture_time timestamp, primary key (one,"
                + " agent_rollup_id)) " + WITH_LCS);

        try {
            cleanUpAgentRollupTable(session);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }

        readPS = session.prepare("select agent_rollup_id, parent_agent_rollup_id,"
                + " display, agent, last_capture_time from agent_rollup where one = 1");
        readParentIdPS = session.prepare("select parent_agent_rollup_id from agent_rollup where"
                + " one = 1 and agent_rollup_id = ?");
        insertPS = session.prepare("insert into agent_rollup (one, agent_rollup_id,"
                + " parent_agent_rollup_id, agent) values (1, ?, ?, ?)");
        // it would be nice to use "update ... if not exists" here, which would eliminate the need
        // to filter incomplete records in readAgentRollups() and eliminate the need to clean up
        // table occassionally in cleanUpAgentRollupTable(), but "if not exists" easily leads to
        // lots of timeout errors "Cassandra timeout during write query at consistency SERIAL"
        insertLastCaptureTimePS = session.prepare("insert into agent_rollup (one, agent_rollup_id,"
                + " last_capture_time) values (1, ?, ?)");

        isAgentPS = session
                .prepare("select agent from agent_rollup where one = 1 and agent_rollup_id = ?");

        readDisplayPS = session
                .prepare("select display from agent_rollup where one = 1 and agent_rollup_id = ?");
        updateDisplayPS = session.prepare("update agent_rollup set display = ? where"
                + " one = 1 and agent_rollup_id = ? if display = ?");

        deletePS =
                session.prepare("delete from agent_rollup where one = 1 and agent_rollup_id = ?");

        agentRollupIdCache =
                clusterManager.createCache("agentRollupIdCache", new AgentRollupIdCacheLoader());
        agentRollupConfigCache = clusterManager.createCache("agentRollupConfigCache",
                new AgentRollupConfigCacheLoader());
    }

    // returns stored agent config
    public void store(String agentId, @Nullable String agentRollupId) throws Exception {
        // insert into agent_rollup last so readEnvironment() and readAgentConfig() are more likely
        // to return non-null
        BoundStatement boundStatement = insertPS.bind();
        int i = 0;
        boundStatement.setString(i++, agentId);
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setBool(i++, true);
        Sessions.execute(session, boundStatement);
        if (agentRollupId != null) {
            List<String> agentRollupIds = getAgentRollupIds(agentRollupId);
            for (int j = agentRollupIds.size() - 1; j >= 0; j--) {
                String loopAgentRollupId = agentRollupIds.get(j);
                String loopParentAgentRollupId = j == 0 ? null : agentRollupIds.get(j - 1);
                boundStatement = insertPS.bind();
                i = 0;
                boundStatement.setString(i++, loopAgentRollupId);
                boundStatement.setString(i++, loopParentAgentRollupId);
                boundStatement.setBool(i++, false);
                Sessions.execute(session, boundStatement);
            }
        }

        agentRollupIdCache.invalidate(agentId);
        // currently agent rollup config cannot be changed from agent (via glowroot.properties)
        // but this will probably change, and likely to forget to invalidate agent rollup config
        // cache at that time, so...
        agentRollupConfigCache.invalidate(agentId);
    }

    @Override
    public List<AgentRollup> readAgentRollups() throws Exception {
        ResultSet results = Sessions.execute(session, readPS.bind());
        Set<AgentRollupRecord> topLevel = Sets.newHashSet();
        Multimap<String, AgentRollupRecord> childMultimap = ArrayListMultimap.create();
        for (Row row : results) {
            int i = 0;
            String id = checkNotNull(row.getString(i++));
            String parentId = row.getString(i++);
            String display = MoreObjects.firstNonNull(row.getString(i++), id);
            if (row.isNull(i)) {
                // this row was created by insertLastCaptureTimePS, but there has not been a
                // collectInit() for it yet, so exclude it from layout service (to avoid
                // AgentConfigNotFoundException)
                continue;
            }
            boolean agent = row.getBool(i++);
            Date lastCaptureTime = row.getTimestamp(i++);
            AgentRollupRecord agentRollupRecord = ImmutableAgentRollupRecord.builder()
                    .id(id)
                    .display(display)
                    .agent(agent)
                    .lastCaptureTime(lastCaptureTime)
                    .build();
            if (parentId == null) {
                topLevel.add(agentRollupRecord);
            } else {
                childMultimap.put(parentId, agentRollupRecord);
            }
        }
        List<AgentRollup> agentRollups = Lists.newArrayList();
        for (AgentRollupRecord topLevelAgentRollup : Ordering.natural().sortedCopy(topLevel)) {
            agentRollups.add(createAgentRollup(topLevelAgentRollup, childMultimap));
        }
        return agentRollups;
    }

    @Override
    public String readAgentRollupDisplay(String agentRollupId) throws Exception {
        AgentRollupConfig agentRollupConfig = readAgentRollupConfig(agentRollupId);
        if (agentRollupConfig == null) {
            return agentRollupId;
        }
        String display = agentRollupConfig.display();
        if (display.isEmpty()) {
            return agentRollupId;
        }
        return display;
    }

    @Override
    public boolean isAgent(String agentRollupId) throws Exception {
        BoundStatement boundStatement = isAgentPS.bind();
        boundStatement.setString(0, agentRollupId);
        Row row = Sessions.execute(session, boundStatement).one();
        if (row == null) {
            return false;
        }
        return row.getBool(0);
    }

    // includes agentId itself
    // agentId is index 0
    // its direct parent is index 1
    // etc...
    public List<String> readAgentRollupIds(String agentId) throws Exception {
        String agentRollupId = agentRollupIdCache.get(agentId).orNull();
        if (agentRollupId == null) {
            // agent must have been manually deleted
            return ImmutableList.of(agentId);
        }
        List<String> agentRollupIds = getAgentRollupIds(agentRollupId);
        Collections.reverse(agentRollupIds);
        agentRollupIds.add(0, agentId);
        return agentRollupIds;
    }

    ResultSetFuture updateLastCaptureTime(String agentId, long captureTime) {
        BoundStatement boundStatement = insertLastCaptureTimePS.bind();
        int i = 0;
        boundStatement.setString(i++, agentId);
        boundStatement.setTimestamp(i++, new Date(captureTime));
        return session.executeAsync(boundStatement);
    }

    @Nullable
    AgentRollupConfig readAgentRollupConfig(String agentRollupId) throws Exception {
        return agentRollupConfigCache.get(agentRollupId).orNull();
    }

    void update(AgentRollupConfig agentRollupConfig, String priorVersion) throws Exception {
        BoundStatement boundStatement = readDisplayPS.bind();
        boundStatement.setString(0, agentRollupConfig.id());
        ResultSet results = Sessions.execute(session, boundStatement);
        Row row = results.one();
        if (row == null) {
            // agent rollup was just deleted
            throw new OptimisticLockException();
        }
        String currDisplay = row.getString(0);
        AgentRollupConfig priorAgentRollupConfig =
                buildAgentRollupConfig(agentRollupConfig.id(), currDisplay);
        if (!priorAgentRollupConfig.version().equals(priorVersion)) {
            throw new OptimisticLockException();
        }
        boundStatement = updateDisplayPS.bind();
        int i = 0;
        boundStatement.setString(i++, agentRollupConfig.id());
        boundStatement.setString(i++, Strings.emptyToNull(agentRollupConfig.display()));
        boundStatement.setString(i++, currDisplay);
        Sessions.execute(session, boundStatement);
        results = Sessions.execute(session, boundStatement);
        row = checkNotNull(results.one());
        boolean applied = row.getBool("[applied]");
        if (applied) {
            agentRollupConfigCache.invalidate(agentRollupConfig.id());
        } else {
            throw new OptimisticLockException();
        }
    }

    void delete(String agentRollupId) throws Exception {
        BoundStatement boundStatement = deletePS.bind();
        boundStatement.setString(0, agentRollupId);
        Sessions.execute(session, boundStatement);
    }

    private AgentRollup createAgentRollup(AgentRollupRecord agentRollupRecord,
            Multimap<String, AgentRollupRecord> parentChildMap) {
        Collection<AgentRollupRecord> childAgentRollupRecords =
                parentChildMap.get(agentRollupRecord.id());
        ImmutableAgentRollup.Builder builder = ImmutableAgentRollup.builder()
                .id(agentRollupRecord.id())
                .display(agentRollupRecord.display())
                .agent(agentRollupRecord.agent())
                .lastCaptureTime(agentRollupRecord.lastCaptureTime());
        for (AgentRollupRecord childAgentRollupRecord : Ordering.natural()
                .sortedCopy(childAgentRollupRecords)) {
            builder.addChildren(createAgentRollup(childAgentRollupRecord, parentChildMap));
        }
        return builder.build();
    }

    static List<String> getAgentRollupIds(String agentRollupId) {
        List<String> agentRollupIds = Lists.newArrayList();
        int lastFoundIndex = -1;
        int nextFoundIndex;
        while ((nextFoundIndex = agentRollupId.indexOf('/', lastFoundIndex)) != -1) {
            agentRollupIds.add(agentRollupId.substring(0, nextFoundIndex));
            lastFoundIndex = nextFoundIndex + 1;
        }
        agentRollupIds.add(agentRollupId);
        return agentRollupIds;
    }

    private static ImmutableAgentRollupConfig buildAgentRollupConfig(String id,
            @Nullable String display) {
        return ImmutableAgentRollupConfig.builder()
                .id(id)
                .display(Strings.nullToEmpty(display))
                .build();
    }

    private static void cleanUpAgentRollupTable(Session session) throws Exception {
        ResultSet results =
                Sessions.execute(session, "select agent_rollup_id, agent from agent_rollup");
        PreparedStatement deletePS = session.prepare(
                "delete from agent_rollup where one = 1 and agent_rollup_id = ? if agent = null");
        for (Row row : results) {
            if (row.isNull(1)) {
                // this row was created by insertLastCaptureTimePS, but there has not been a
                // collectInit() for it yet, so exclude it from layout service (to avoid
                // AgentConfigNotFoundException)
                BoundStatement boundStatement = deletePS.bind();
                boundStatement.setString(0, checkNotNull(row.getString(0)));
                Sessions.execute(session, boundStatement);
            }
        }
    }

    @Value.Immutable
    public interface AgentConfigUpdate {
        AgentConfig config();
        UUID configUpdateToken();
    }

    @Value.Immutable
    @Styles.AllParameters
    interface AgentRollupRecord extends Comparable<AgentRollupRecord> {

        String id();
        String display();
        boolean agent();
        @Nullable
        Date lastCaptureTime();

        @Override
        default public int compareTo(AgentRollupRecord right) {
            return display().compareToIgnoreCase(right.display());
        }
    }

    private class AgentRollupIdCacheLoader implements CacheLoader<String, Optional<String>> {
        @Override
        public Optional<String> load(String agentId) throws Exception {
            BoundStatement boundStatement = readParentIdPS.bind();
            boundStatement.setString(0, agentId);
            ResultSet results = Sessions.execute(session, boundStatement);
            Row row = results.one();
            if (row == null) {
                return Optional.absent();
            }
            return Optional.fromNullable(row.getString(0));
        }
    }

    private class AgentRollupConfigCacheLoader
            implements CacheLoader<String, Optional<AgentRollupConfig>> {
        @Override
        public Optional<AgentRollupConfig> load(String agentRollupId) throws Exception {
            BoundStatement boundStatement = readDisplayPS.bind();
            boundStatement.setString(0, agentRollupId);
            ResultSet results = Sessions.execute(session, boundStatement);
            Row row = results.one();
            if (row == null) {
                return Optional.absent();
            }
            return Optional.of(buildAgentRollupConfig(agentRollupId, row.getString(0)));
        }
    }
}
