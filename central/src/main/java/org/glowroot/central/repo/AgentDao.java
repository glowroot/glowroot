/*
 * Copyright 2015-2016 the original author or authors.
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

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.utils.UUIDs;
import com.google.common.base.MoreObjects;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import org.immutables.value.Value;

import org.glowroot.common.config.AgentRollupConfig;
import org.glowroot.common.config.ImmutableAgentRollupConfig;
import org.glowroot.common.repo.AgentRepository;
import org.glowroot.common.repo.ImmutableAgentRollup;
import org.glowroot.common.util.Styles;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginProperty;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.Environment;

import static com.google.common.base.Preconditions.checkNotNull;

public class AgentDao implements AgentRepository {

    private static final String WITH_LCS =
            "with compaction = { 'class' : 'LeveledCompactionStrategy' }";

    private final Session session;

    private final PreparedStatement insertAgentPS;
    private final PreparedStatement insertAgentConfigOnlyPS;
    private final PreparedStatement existsAgentPS;
    private final PreparedStatement readEnvironmentPS;
    private final PreparedStatement readAgentConfigPS;
    private final PreparedStatement readAgentConfigUpdatePS;
    private final PreparedStatement markAgentConfigUpdatedPS;
    private final PreparedStatement deleteAgentPS;

    private final PreparedStatement readAllAgentRollupPS;
    private final PreparedStatement readParentAgentRollupIdPS;
    private final PreparedStatement insertAgentRollupPS;
    private final PreparedStatement insertAgentRollupLastCaptureTimePS;

    private final PreparedStatement readAgentRollupConfigPS;
    private final PreparedStatement insertAgentRollupConfigPS;

    private final PreparedStatement deleteAgentRollupPS;

    private final LoadingCache<String, Optional<String>> agentRollupIdCache =
            CacheBuilder.newBuilder().build(new AgentRollupIdCacheLoader());

    private final LoadingCache<String, Optional<AgentRollupConfig>> agentRollupConfigCache =
            CacheBuilder.newBuilder().build(new AgentRollupConfigCacheLoader());

    private final LoadingCache<String, Optional<AgentConfig>> agentConfigCache =
            CacheBuilder.newBuilder().build(new AgentConfigCacheLoader());

    public AgentDao(Session session) {
        this.session = session;

        session.execute("create table if not exists agent (agent_id varchar, environment blob,"
                + " config blob, config_update boolean, config_update_token uuid,"
                + " primary key (agent_id)) " + WITH_LCS);
        // secondary index is needed for Cassandra 2.x (to avoid error on readAgentConfigUpdatePS)
        session.execute(
                "create index if not exists agent_config_update_idx on agent (config_update)");
        session.execute("create table if not exists agent_rollup (one int, agent_rollup_id varchar,"
                + " parent_agent_rollup_id varchar, display varchar, agent boolean,"
                + " last_capture_time timestamp, primary key (one, agent_rollup_id)) " + WITH_LCS);

        insertAgentPS = session.prepare("insert into agent (agent_id, environment, config,"
                + " config_update, config_update_token) values (?, ?, ?, ?, ?)");
        insertAgentConfigOnlyPS = session.prepare("insert into agent (agent_id, config,"
                + " config_update, config_update_token) values (?, ?, ?, ?)");
        existsAgentPS = session.prepare("select agent_id from agent where agent_id = ?");
        readEnvironmentPS = session.prepare("select environment from agent where agent_id = ?");
        readAgentConfigPS = session.prepare("select config from agent where agent_id = ?");
        readAgentConfigUpdatePS = session.prepare("select config, config_update_token from agent"
                + " where agent_id = ? and config_update = true allow filtering");
        markAgentConfigUpdatedPS = session.prepare("update agent set config_update = false,"
                + " config_update_token = null where agent_id = ? if config_update_token = ?");
        deleteAgentPS = session.prepare("delete from agent where agent_id = ?");

        readAllAgentRollupPS = session.prepare("select agent_rollup_id, parent_agent_rollup_id,"
                + " display, agent, last_capture_time from agent_rollup where one = 1");
        readParentAgentRollupIdPS = session.prepare("select parent_agent_rollup_id from"
                + " agent_rollup where one = 1 and agent_rollup_id = ?");
        insertAgentRollupPS = session.prepare("insert into agent_rollup (one, agent_rollup_id,"
                + " parent_agent_rollup_id, agent) values (1, ?, ?, ?)");
        insertAgentRollupLastCaptureTimePS = session.prepare("insert into agent_rollup (one,"
                + " agent_rollup_id, last_capture_time) values (1, ?, ?)");

        readAgentRollupConfigPS = session
                .prepare("select display from agent_rollup where one = 1 and agent_rollup_id = ?");
        insertAgentRollupConfigPS = session.prepare("insert into agent_rollup (one,"
                + " agent_rollup_id, display) values (1, ?, ?)");
        deleteAgentRollupPS = session.prepare("delete from agent_rollup where one = 1"
                + " and agent_rollup_id = ?");
    }

    // returns stored agent config
    public AgentConfig store(String agentId, @Nullable String agentRollupId,
            Environment environment, AgentConfig agentConfig)
            throws InvalidProtocolBufferException {
        AgentConfig existingAgentConfig = readAgentConfig(agentId);
        AgentConfig updatedAgentConfig;
        if (existingAgentConfig == null) {
            updatedAgentConfig = agentConfig;
        } else {
            // sync list of plugin properties, central property values win
            Map<String, PluginConfig> existingPluginConfigs = Maps.newHashMap();
            for (PluginConfig existingPluginConfig : existingAgentConfig.getPluginConfigList()) {
                existingPluginConfigs.put(existingPluginConfig.getId(), existingPluginConfig);
            }
            List<PluginConfig> pluginConfigs = Lists.newArrayList();
            for (PluginConfig agentPluginConfig : agentConfig.getPluginConfigList()) {
                PluginConfig existingPluginConfig =
                        existingPluginConfigs.get(agentPluginConfig.getId());
                if (existingPluginConfig == null) {
                    pluginConfigs.add(agentPluginConfig);
                    continue;
                }
                Map<String, PluginProperty> existingProperties = Maps.newHashMap();
                for (PluginProperty existingProperty : existingPluginConfig.getPropertyList()) {
                    existingProperties.put(existingProperty.getName(), existingProperty);
                }
                List<PluginProperty> properties = Lists.newArrayList();
                for (PluginProperty agentProperty : agentPluginConfig.getPropertyList()) {
                    PluginProperty existingProperty =
                            existingProperties.get(agentProperty.getName());
                    if (existingProperty == null) {
                        properties.add(agentProperty);
                        continue;
                    }
                    // overlay existing property value
                    properties.add(agentProperty.toBuilder()
                            .setValue(existingProperty.getValue())
                            .build());
                }
                pluginConfigs.add(PluginConfig.newBuilder()
                        .setId(agentPluginConfig.getId())
                        .setName(agentPluginConfig.getName())
                        .addAllProperty(properties)
                        .build());
            }
            updatedAgentConfig = existingAgentConfig.toBuilder()
                    .clearPluginConfig()
                    .addAllPluginConfig(pluginConfigs)
                    .build();
        }
        BoundStatement boundStatement = insertAgentPS.bind();
        int i = 0;
        boundStatement.setString(i++, agentId);
        boundStatement.setBytes(i++, ByteBuffer.wrap(environment.toByteArray()));
        boundStatement.setBytes(i++, ByteBuffer.wrap(updatedAgentConfig.toByteArray()));
        // this method is only called by collectInit(), and agent will not consider collectInit()
        // to be successful until it receives updated agent config
        boundStatement.setBool(i++, false);
        boundStatement.setToNull(i++);
        session.execute(boundStatement);
        // insert into agent_rollup last so readEnvironment() and readAgentConfig() below are more
        // likely to return non-null
        boundStatement = insertAgentRollupPS.bind();
        i = 0;
        boundStatement.setString(i++, agentId);
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setBool(i++, true);
        session.execute(boundStatement);
        if (agentRollupId != null) {
            List<String> agentRollupIds = getAgentRollupIds(agentRollupId);
            for (int j = agentRollupIds.size() - 1; j >= 0; j--) {
                String loopAgentRollupId = agentRollupIds.get(j);
                String loopParentAgentRollupId = j == 0 ? null : agentRollupIds.get(j - 1);
                boundStatement = insertAgentRollupPS.bind();
                i = 0;
                boundStatement.setString(i++, loopAgentRollupId);
                boundStatement.setString(i++, loopParentAgentRollupId);
                boundStatement.setBool(i++, false);
                session.execute(boundStatement);
            }
        }

        agentRollupIdCache.invalidate(agentId);
        // currently agent rollup config cannot be changed from agent (via glowroot.properties)
        // but this will probably change, and likely to forget to invalidate agent rollup config
        // cache at that time, so...
        agentRollupConfigCache.invalidate(agentId);
        agentConfigCache.invalidate(agentId);
        return updatedAgentConfig;
    }

    @Override
    public List<AgentRollup> readAgentRollups() {
        ResultSet results = session.execute(readAllAgentRollupPS.bind());
        Set<AgentRollupRecord> topLevel = Sets.newHashSet();
        Multimap<String, AgentRollupRecord> childMultimap = ArrayListMultimap.create();
        for (Row row : results) {
            int i = 0;
            String id = checkNotNull(row.getString(i++));
            String parentId = row.getString(i++);
            String display = MoreObjects.firstNonNull(row.getString(i++), id);
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
    public String readAgentRollupDisplay(String agentRollupId) {
        AgentRollupConfig agentRollupConfig =
                agentRollupConfigCache.getUnchecked(agentRollupId).orNull();
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
    public boolean isAgentId(String agentId) {
        BoundStatement boundStatement = existsAgentPS.bind();
        boundStatement.setString(0, agentId);
        return !session.execute(boundStatement).isExhausted();
    }

    @Override
    public @Nullable Environment readEnvironment(String agentId)
            throws InvalidProtocolBufferException {
        BoundStatement boundStatement = readEnvironmentPS.bind();
        boundStatement.setString(0, agentId);
        ResultSet results = session.execute(boundStatement);
        Row row = results.one();
        if (row == null) {
            // agent must have been manually deleted
            return null;
        }
        ByteBuffer bytes = row.getBytes(0);
        if (bytes == null) {
            // for some reason received data from agent, but not initial environment data
            return null;
        }
        return Environment.parseFrom(ByteString.copyFrom(bytes));
    }

    public @Nullable AgentConfigUpdate readForAgentConfigUpdate(String agentId)
            throws InvalidProtocolBufferException {
        BoundStatement boundStatement = readAgentConfigUpdatePS.bind();
        boundStatement.setString(0, agentId);
        ResultSet results = session.execute(boundStatement);
        Row row = results.one();
        if (row == null) {
            // no pending config update for this agent (or agent has been manually deleted)
            return null;
        }
        ByteBuffer bytes = checkNotNull(row.getBytes(0));
        UUID configUpdateToken = checkNotNull(row.getUUID(1));
        return ImmutableAgentConfigUpdate.builder()
                .config(AgentConfig.parseFrom(ByteString.copyFrom(bytes)))
                .configUpdateToken(configUpdateToken)
                .build();
    }

    public void markAgentConfigUpdated(String agentId, UUID configUpdateToken) {
        BoundStatement boundStatement = markAgentConfigUpdatedPS.bind();
        int i = 0;
        boundStatement.setString(i++, agentId);
        boundStatement.setUUID(i++, configUpdateToken);
        session.execute(boundStatement);
    }

    // includes agentId itself
    // agentId is index 0
    // its direct parent is index 1
    // etc...
    public List<String> readAgentRollupIds(String agentId) {
        String agentRollupId = agentRollupIdCache.getUnchecked(agentId).orNull();
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
        BoundStatement boundStatement = insertAgentRollupLastCaptureTimePS.bind();
        int i = 0;
        boundStatement.setString(i++, agentId);
        boundStatement.setTimestamp(i++, new Date(captureTime));
        return session.executeAsync(boundStatement);
    }

    @Nullable
    AgentConfig readAgentConfig(String agentId) {
        return agentConfigCache.getUnchecked(agentId).orNull();
    }

    void storeAgentConfig(String agentId, AgentConfig agentConfig) {
        BoundStatement boundStatement = insertAgentConfigOnlyPS.bind();
        int i = 0;
        boundStatement.setString(i++, agentId);
        boundStatement.setBytes(i++, ByteBuffer.wrap(agentConfig.toByteArray()));
        boundStatement.setBool(i++, true);
        boundStatement.setUUID(i++, UUIDs.random());
        session.execute(boundStatement);
        agentConfigCache.invalidate(agentId);
    }

    @Nullable
    AgentRollupConfig readAgentRollupConfig(String agentRollupId) {
        return agentRollupConfigCache.getUnchecked(agentRollupId).orNull();
    }

    void update(AgentRollupConfig agentRollupConfig) {
        BoundStatement boundStatement = insertAgentRollupConfigPS.bind();
        int i = 0;
        boundStatement.setString(i++, agentRollupConfig.id());
        boundStatement.setString(i++, Strings.emptyToNull(agentRollupConfig.display()));
        session.execute(boundStatement);
        agentRollupConfigCache.invalidate(agentRollupConfig.id());
    }

    void delete(String agentRollupId) {
        if (isAgentId(agentRollupId)) {
            BoundStatement boundStatement = deleteAgentPS.bind();
            boundStatement.setString(0, agentRollupId);
            session.execute(boundStatement);
        }
        BoundStatement boundStatement = deleteAgentRollupPS.bind();
        boundStatement.setString(0, agentRollupId);
        session.execute(boundStatement);
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

    private class AgentRollupIdCacheLoader extends CacheLoader<String, Optional<String>> {
        @Override
        public Optional<String> load(String agentId) throws Exception {
            BoundStatement boundStatement = readParentAgentRollupIdPS.bind();
            boundStatement.setString(0, agentId);
            ResultSet results = session.execute(boundStatement);
            Row row = results.one();
            if (row == null) {
                return Optional.absent();
            }
            return Optional.fromNullable(row.getString(0));
        }
    }

    private class AgentRollupConfigCacheLoader
            extends CacheLoader<String, Optional<AgentRollupConfig>> {
        @Override
        public Optional<AgentRollupConfig> load(String agentRollupId) throws Exception {
            BoundStatement boundStatement = readAgentRollupConfigPS.bind();
            boundStatement.setString(0, agentRollupId);
            ResultSet results = session.execute(boundStatement);
            Row row = results.one();
            if (row == null) {
                return Optional.absent();
            }
            return Optional.of(ImmutableAgentRollupConfig.builder()
                    .id(agentRollupId)
                    .display(Strings.nullToEmpty(row.getString(0)))
                    .build());
        }
    }

    private class AgentConfigCacheLoader extends CacheLoader<String, Optional<AgentConfig>> {
        @Override
        public Optional<AgentConfig> load(String agentId) throws Exception {
            BoundStatement boundStatement = readAgentConfigPS.bind();
            boundStatement.setString(0, agentId);
            ResultSet results = session.execute(boundStatement);
            Row row = results.one();
            if (row == null) {
                // agent must have been manually deleted
                return Optional.absent();
            }
            ByteBuffer bytes = row.getBytes(0);
            if (bytes == null) {
                // for some reason received data from agent, but not initial agent config
                return Optional.absent();
            }
            return Optional.of(AgentConfig.parseFrom(ByteString.copyFrom(bytes)));
        }
    }
}
