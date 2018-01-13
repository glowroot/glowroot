/*
 * Copyright 2015-2018 the original author or authors.
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
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Future;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.google.common.base.Joiner;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Futures;
import org.immutables.value.Value;

import org.glowroot.central.util.Session;
import org.glowroot.common.config.ConfigDefaults;
import org.glowroot.common.repo.AgentRollupRepository;
import org.glowroot.common.repo.ImmutableAgentRollup;
import org.glowroot.common.repo.Utils;
import org.glowroot.common.util.Clock;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.DAYS;

public class AgentDao implements AgentRollupRepository {

    private final Session session;
    private final AgentConfigDao agentConfigDao;
    private final ConfigRepositoryImpl configRepository;
    private final Clock clock;

    private final PreparedStatement readPS;
    private final PreparedStatement insertPS;

    AgentDao(Session session, AgentConfigDao agentConfigDao, ConfigRepositoryImpl configRepository,
            Clock clock) throws Exception {
        this.session = session;
        this.agentConfigDao = agentConfigDao;
        this.configRepository = configRepository;
        this.clock = clock;

        int maxRollupHours = configRepository.getCentralStorageConfig().getMaxRollupHours();
        session.createTableWithTWCS("create table if not exists agent (one int, capture_time"
                + " timestamp, agent_id varchar, primary key (one, capture_time, agent_id))",
                maxRollupHours);

        readPS = session.prepare("select agent_id from agent where one = 1 and capture_time >= ?"
                + " and capture_time <= ?");
        insertPS = session.prepare(
                "insert into agent (one, capture_time, agent_id) values (1, ?, ?) using ttl ?");
    }

    @Override
    public List<AgentRollup> readRecentlyActiveAgentRollups(int lastXDays) throws Exception {
        long now = clock.currentTimeMillis();
        // looking to the future just to be safe
        return readAgentRollups(now - DAYS.toMillis(lastXDays), now + DAYS.toMillis(7));
    }

    @Override
    public List<AgentRollup> readAgentRollups(long from, long to) throws Exception {
        long rolledUpFrom = Utils.getRollupCaptureTime(from, DAYS.toMillis(1));
        long rolledUpTo = Utils.getRollupCaptureTime(to, DAYS.toMillis(1));
        BoundStatement boundStatement = readPS.bind();
        boundStatement.setTimestamp(0, new Date(rolledUpFrom));
        boundStatement.setTimestamp(1, new Date(rolledUpTo));
        ResultSet results = session.execute(boundStatement);
        Set<String> topLevelAgentRollupIds = Sets.newHashSet();
        Multimap<String, String> childMultimap = HashMultimap.create();
        for (Row row : results) {
            String agentId = checkNotNull(row.getString(0));
            List<String> agentRollupIds = AgentRollupIds.getAgentRollupIds(agentId);
            if (agentRollupIds.size() == 1) {
                topLevelAgentRollupIds.add(agentId);
            } else {
                String topLevelAgentId = agentRollupIds.get(agentRollupIds.size() - 1);
                topLevelAgentRollupIds.add(topLevelAgentId);
                for (int i = 1; i < agentRollupIds.size(); i++) {
                    childMultimap.put(agentRollupIds.get(i), agentRollupIds.get(i - 1));
                }
            }
        }
        List<AgentRollup> agentRollups = Lists.newArrayList();
        for (String topLevelAgentRollupId : topLevelAgentRollupIds) {
            agentRollups.add(createAgentRollup(topLevelAgentRollupId, childMultimap));
        }
        agentRollups.sort(Comparator.comparing(AgentRollup::display));
        return agentRollups;
    }

    @Override
    public String readAgentRollupDisplay(String agentRollupId) throws Exception {
        List<String> displayParts = readAgentRollupDisplayParts(agentRollupId);
        return Joiner.on(" :: ").join(displayParts);
    }

    @Override
    public List<String> readAgentRollupDisplayParts(String agentRollupId) throws Exception {
        List<String> agentRollupIds = AgentRollupIds.getAgentRollupIds(agentRollupId);
        List<String> displayParts = Lists.newArrayList();
        for (ListIterator<String> i = agentRollupIds.listIterator(agentRollupIds.size()); i
                .hasPrevious();) {
            displayParts.add(readAgentRollupLastDisplayPart(i.previous()));
        }
        return displayParts;
    }

    public Future<ResultSet> insert(String agentId, long captureTime) throws Exception {
        AgentConfig agentConfig = agentConfigDao.read(agentId);
        if (agentConfig == null) {
            // have yet to receive collectInit()
            return Futures.immediateFuture(null);
        }
        long rollupCaptureTime = Utils.getRollupCaptureTime(captureTime, DAYS.toMillis(1));
        BoundStatement boundStatement = insertPS.bind();
        int i = 0;
        boundStatement.setTimestamp(i++, new Date(rollupCaptureTime));
        boundStatement.setString(i++, agentId);
        boundStatement.setInt(i++, configRepository.getCentralStorageConfig().getMaxRollupTTL());
        return session.executeAsync(boundStatement);
    }

    private AgentRollup createAgentRollup(String agentRollupId,
            Multimap<String, String> parentChildMap) throws Exception {
        Collection<String> childAgentRollupIds = parentChildMap.get(agentRollupId);
        List<String> agentRollupDisplayParts = readAgentRollupDisplayParts(agentRollupId);
        ImmutableAgentRollup.Builder builder = ImmutableAgentRollup.builder()
                .id(agentRollupId)
                .display(Joiner.on(" :: ").join(agentRollupDisplayParts))
                .lastDisplayPart(agentRollupDisplayParts.get(agentRollupDisplayParts.size() - 1));
        List<AgentRollup> childAgentRollups = Lists.newArrayList();
        for (String childAgentRollupId : childAgentRollupIds) {
            childAgentRollups.add(createAgentRollup(childAgentRollupId, parentChildMap));
        }
        childAgentRollups.sort(Comparator.comparing(AgentRollup::display));
        return builder.addAllChildren(childAgentRollups)
                .build();
    }

    private String readAgentRollupLastDisplayPart(String agentRollupId) throws Exception {
        AgentConfig agentConfig = agentConfigDao.read(agentRollupId);
        if (agentConfig == null) {
            return agentRollupId;
        }
        String display = agentConfig.getGeneralConfig().getDisplay();
        if (display.isEmpty()) {
            return ConfigDefaults.getDefaultAgentRollupDisplayPart(agentRollupId);
        }
        return display;
    }

    @Value.Immutable
    public interface AgentConfigUpdate {
        AgentConfig config();
        UUID configUpdateToken();
    }
}
