/*
 * Copyright 2015-2019 the original author or authors.
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Future;

import javax.annotation.Nullable;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.google.common.base.Joiner;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.primitives.Ints;
import org.immutables.value.Value;

import org.glowroot.central.util.Session;
import org.glowroot.common.util.CaptureTimes;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.repo.ActiveAgentRepository;
import org.glowroot.common2.repo.ConfigRepository.RollupConfig;
import org.glowroot.common2.repo.ImmutableAgentRollup;
import org.glowroot.common2.repo.util.RollupLevelService;
import org.glowroot.common2.repo.util.RollupLevelService.DataKind;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.HOURS;

public class ActiveAgentDao implements ActiveAgentRepository {

    private final Session session;
    private final AgentDisplayDao agentDisplayDao;
    private final AgentConfigDao agentConfigDao;
    private final ConfigRepositoryImpl configRepository;
    private final RollupLevelService rollupLevelService;
    private final Clock clock;

    private final ImmutableList<PreparedStatement> insertPS;
    private final ImmutableList<PreparedStatement> readPS;

    ActiveAgentDao(Session session, AgentDisplayDao agentDisplayDao, AgentConfigDao agentConfigDao,
            ConfigRepositoryImpl configRepository, RollupLevelService rollupLevelService,
            Clock clock) throws Exception {
        this.session = session;
        this.agentDisplayDao = agentDisplayDao;
        this.agentConfigDao = agentConfigDao;
        this.configRepository = configRepository;
        this.rollupLevelService = rollupLevelService;
        this.clock = clock;

        int count = configRepository.getRollupConfigs().size();
        List<Integer> rollupExpirationHours =
                configRepository.getCentralStorageConfig().rollupExpirationHours();

        List<PreparedStatement> insertPS = new ArrayList<>();
        List<PreparedStatement> readPS = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            session.createTableWithTWCS("create table if not exists active_agent_rollup_" + i
                    + " (one int, capture_time timestamp, agent_id varchar, primary key (one,"
                    + " capture_time, agent_id))", rollupExpirationHours.get(i));
            insertPS.add(session.prepare("insert into active_agent_rollup_" + i + " (one,"
                    + " capture_time, agent_id) values (1, ?, ?) using ttl ?"));
            readPS.add(session.prepare("select agent_id, capture_time from active_agent_rollup_"
                    + i + " where one = 1 and capture_time >= ? and capture_time <= ?"));
        }
        this.insertPS = ImmutableList.copyOf(insertPS);
        this.readPS = ImmutableList.copyOf(readPS);
    }

    @Override
    public List<AgentRollup> readRecentlyActiveAgentRollups(long lastXMillis) throws Exception {
        long now = clock.currentTimeMillis();
        return readActiveAgentRollups(now - lastXMillis, now);
    }

    @Override
    public List<AgentRollup> readActiveAgentRollups(long from, long to) throws Exception {
        int rollupLevel = rollupLevelService.getRollupLevelForView(from, to, DataKind.GENERAL);
        long rollupIntervalMillis =
                getRollupIntervalMillis(configRepository.getRollupConfigs(), rollupLevel);
        long revisedTo = CaptureTimes.getRollup(to, rollupIntervalMillis);

        Set<String> allAgentRollupIds = new HashSet<>();
        Set<String> topLevelAgentRollupIds = new HashSet<>();
        Multimap<String, String> childMultimap = HashMultimap.create();
        Long lastRolledUpTime = process(rollupLevel, from, revisedTo, allAgentRollupIds,
                topLevelAgentRollupIds, childMultimap);
        if (rollupLevel != 0) {
            long revisedFrom = lastRolledUpTime == null ? from : lastRolledUpTime + 1;
            process(0, revisedFrom, revisedTo, allAgentRollupIds, topLevelAgentRollupIds,
                    childMultimap);
        }
        Map<String, Future<String>> agentDisplayFutureMap = new HashMap<>();
        for (String agentRollupId : allAgentRollupIds) {
            agentDisplayFutureMap.put(agentRollupId,
                    agentDisplayDao.readLastDisplayPartAsync(agentRollupId));
        }
        Map<String, String> agentDisplayMap = new HashMap<>();
        for (Map.Entry<String, Future<String>> entry : agentDisplayFutureMap.entrySet()) {
            agentDisplayMap.put(entry.getKey(), entry.getValue().get());
        }
        List<AgentRollup> agentRollups = new ArrayList<>();
        for (String topLevelAgentRollupId : topLevelAgentRollupIds) {
            agentRollups
                    .add(createAgentRollup(topLevelAgentRollupId, childMultimap, agentDisplayMap));
        }
        agentRollups.sort(Comparator.comparing(AgentRollup::display));
        return agentRollups;
    }

    public List<Future<?>> insert(String agentId, long captureTime) throws Exception {
        AgentConfig agentConfig = agentConfigDao.read(agentId);
        if (agentConfig == null) {
            // have yet to receive collectInit()
            return ImmutableList.of();
        }
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        // if the size changes, then logic in the loop for the last rollup level needs to be updated
        checkState(rollupConfigs.size() == 4);
        List<Integer> rollupExpirationHours =
                configRepository.getCentralStorageConfig().rollupExpirationHours();

        List<Future<?>> futures = new ArrayList<>();
        for (int rollupLevel = 0; rollupLevel < rollupConfigs.size(); rollupLevel++) {
            long rollupIntervalMillis = getRollupIntervalMillis(rollupConfigs, rollupLevel);
            long rollupCaptureTime = CaptureTimes.getRollup(captureTime, rollupIntervalMillis);
            int ttl = Ints.saturatedCast(HOURS.toSeconds(rollupExpirationHours.get(rollupLevel)));
            int adjustedTTL = Common.getAdjustedTTL(ttl, rollupCaptureTime, clock);
            BoundStatement boundStatement = insertPS.get(rollupLevel).bind();
            int i = 0;
            boundStatement.setTimestamp(i++, new Date(rollupCaptureTime));
            boundStatement.setString(i++, agentId);
            boundStatement.setInt(i++, adjustedTTL);
            futures.add(session.writeAsync(boundStatement));
        }
        return futures;
    }

    private @Nullable Long process(int rollupLevel, long from, long to,
            Set<String> allAgentRollupIds, Set<String> topLevelAgentRollupIds,
            Multimap<String, String> childMultimap) throws Exception {
        BoundStatement boundStatement = readPS.get(rollupLevel).bind();
        boundStatement.setTimestamp(0, new Date(from));
        boundStatement.setTimestamp(1, new Date(to));
        ResultSet results = session.read(boundStatement);
        Long lastRolledUpTime = null;
        for (Row row : results) {
            String agentId = checkNotNull(row.getString(0));
            lastRolledUpTime = checkNotNull(row.getTimestamp(1)).getTime();
            List<String> agentRollupIds = AgentRollupIds.getAgentRollupIds(agentId);
            allAgentRollupIds.addAll(agentRollupIds);
            if (agentRollupIds.size() == 1) {
                topLevelAgentRollupIds.add(agentId);
            } else {
                String topLevelAgentId = Iterables.getLast(agentRollupIds);
                topLevelAgentRollupIds.add(topLevelAgentId);
                for (int i = 1; i < agentRollupIds.size(); i++) {
                    childMultimap.put(agentRollupIds.get(i), agentRollupIds.get(i - 1));
                }
            }
        }
        return lastRolledUpTime;
    }

    private AgentRollup createAgentRollup(String agentRollupId,
            Multimap<String, String> parentChildMap, Map<String, String> agentDisplayMap)
            throws Exception {
        Collection<String> childAgentRollupIds = parentChildMap.get(agentRollupId);
        List<String> agentRollupIds = AgentRollupIds.getAgentRollupIds(agentRollupId);
        List<String> displayParts = new ArrayList<>();
        for (ListIterator<String> i = agentRollupIds.listIterator(agentRollupIds.size()); i
                .hasPrevious();) {
            displayParts.add(checkNotNull(agentDisplayMap.get(i.previous())));
        }
        ImmutableAgentRollup.Builder builder = ImmutableAgentRollup.builder()
                .id(agentRollupId)
                .display(Joiner.on(" :: ").join(displayParts))
                .lastDisplayPart(Iterables.getLast(displayParts));
        List<AgentRollup> childAgentRollups = new ArrayList<>();
        for (String childAgentRollupId : childAgentRollupIds) {
            childAgentRollups
                    .add(createAgentRollup(childAgentRollupId, parentChildMap, agentDisplayMap));
        }
        childAgentRollups.sort(Comparator.comparing(AgentRollup::display));
        return builder.addAllChildren(childAgentRollups)
                .build();
    }

    private static long getRollupIntervalMillis(List<RollupConfig> rollupConfigs, int rollupLevel) {
        long rollupIntervalMillis;
        if (rollupLevel < 3) {
            rollupIntervalMillis = rollupConfigs.get(rollupLevel + 1).intervalMillis();
        } else {
            rollupIntervalMillis = DAYS.toMillis(1);
        }
        return rollupIntervalMillis;
    }

    @Value.Immutable
    public interface AgentConfigAndUpdateToken {
        AgentConfig config();
        @Nullable
        UUID updateToken();
    }
}
