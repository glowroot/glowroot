/*
 * Copyright 2015-2023 the original author or authors.
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import javax.annotation.Nullable;

import com.datastax.oss.driver.api.core.cql.*;
import com.google.common.base.Joiner;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import com.google.common.primitives.Ints;

import edu.umd.cs.findbugs.annotations.CheckReturnValue;
import org.glowroot.central.util.Session;
import org.glowroot.common.util.CaptureTimes;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.repo.ActiveAgentRepository;
import org.glowroot.common2.repo.ConfigRepository.RollupConfig;
import org.glowroot.common2.repo.ImmutableAgentRollup;
import org.glowroot.common2.repo.ImmutableTopLevelAgentRollup;
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

    private final ImmutableList<PreparedStatement> insertTopLevelPS;
    private final ImmutableList<PreparedStatement> readTopLevelPS;

    private final ImmutableList<PreparedStatement> insertChildPS;
    private final ImmutableList<PreparedStatement> readChildPS;

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

        List<PreparedStatement> insertTopLevelPS = new ArrayList<>();
        List<PreparedStatement> readTopLevelPS = new ArrayList<>();
        List<PreparedStatement> insertChildPS = new ArrayList<>();
        List<PreparedStatement> readChildPS = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            session.createTableWithTWCS("create table if not exists active_top_level_rollup_" + i
                    + " (one int, capture_time timestamp, top_level_id varchar, primary key (one,"
                    + " capture_time, top_level_id))", rollupExpirationHours.get(i));
            insertTopLevelPS.add(session.prepare("insert into active_top_level_rollup_" + i
                    + " (one, capture_time, top_level_id) values (1, ?, ?) using ttl ?"));
            readTopLevelPS.add(session.prepare("select top_level_id from active_top_level_rollup_"
                    + i + " where one = 1 and capture_time >= ? and capture_time <= ?"));
            session.createTableWithTWCS("create table if not exists active_child_rollup_" + i
                    + " (top_level_id varchar, capture_time timestamp, child_agent_id varchar,"
                    + " primary key (top_level_id, capture_time, child_agent_id))",
                    rollupExpirationHours.get(i));
            insertChildPS.add(session.prepare("insert into active_child_rollup_" + i
                    + " (top_level_id, capture_time, child_agent_id) values (?, ?, ?) using"
                    + " ttl ?"));
            readChildPS.add(session.prepare("select child_agent_id from active_child_rollup_" + i
                    + " where top_level_id = ? and capture_time >= ? and capture_time <= ?"));
        }
        this.insertTopLevelPS = ImmutableList.copyOf(insertTopLevelPS);
        this.readTopLevelPS = ImmutableList.copyOf(readTopLevelPS);
        this.insertChildPS = ImmutableList.copyOf(insertChildPS);
        this.readChildPS = ImmutableList.copyOf(readChildPS);
    }

    @Override
    public List<TopLevelAgentRollup> readActiveTopLevelAgentRollups(long from, long to)
            throws Exception {
        int rollupLevel = rollupLevelService.getRollupLevelForView(from, to, DataKind.GENERAL);
        long rollupIntervalMillis =
                getRollupIntervalMillis(configRepository.getRollupConfigs(), rollupLevel);
        long revisedTo = CaptureTimes.getRollup(to, rollupIntervalMillis);

        Set<String> topLevelIds = new HashSet<>();
        BoundStatement boundStatement = readTopLevelPS.get(rollupLevel).bind()
            .setInstant(0, Instant.ofEpochMilli(from))
            .setInstant(1, Instant.ofEpochMilli(revisedTo));
        ResultSet results = session.read(boundStatement);
        for (Row row : results) {
            topLevelIds.add(checkNotNull(row.getString(0)));
        }
        Map<String, Future<String>> topLevelDisplayFutureMap = new HashMap<>();
        for (String topLevelId : topLevelIds) {
            topLevelDisplayFutureMap.put(topLevelId,
                    agentDisplayDao.readLastDisplayPartAsync(topLevelId));
        }
        List<TopLevelAgentRollup> agentRollups = new ArrayList<>();
        for (Map.Entry<String, Future<String>> entry : topLevelDisplayFutureMap.entrySet()) {
            agentRollups.add(ImmutableTopLevelAgentRollup.builder()
                    .id(entry.getKey())
                    .display(entry.getValue().get())
                    .build());
        }
        agentRollups.sort(Comparator.comparing(TopLevelAgentRollup::display));
        return agentRollups;
    }

    @Override
    public List<AgentRollup> readActiveChildAgentRollups(String topLevelId, long from, long to)
            throws Exception {
        return readActiveChildAgentRollups(topLevelId, from, to, true);
    }

    @Override
    public List<AgentRollup> readRecentlyActiveAgentRollups(long lastXMillis) throws Exception {
        long now = clock.currentTimeMillis();
        return readActiveAgentRollups(now - lastXMillis, now);
    }

    @Override
    public List<AgentRollup> readActiveAgentRollups(long from, long to) throws Exception {
        List<TopLevelAgentRollup> topLevelAgentRollups = readActiveTopLevelAgentRollups(from, to);
        List<AgentRollup> agentRollups = new ArrayList<>();
        for (TopLevelAgentRollup topLevelAgentRollup : topLevelAgentRollups) {
            ImmutableAgentRollup.Builder builder = ImmutableAgentRollup.builder()
                    .id(topLevelAgentRollup.id())
                    .display(topLevelAgentRollup.display())
                    .lastDisplayPart(topLevelAgentRollup.display());
            if (topLevelAgentRollup.id().endsWith("::")) {
                builder.addAllChildren(
                        readActiveChildAgentRollups(topLevelAgentRollup.id(), from, to, false));
            }
            agentRollups.add(builder.build());
        }
        return agentRollups;
    }

    @CheckReturnValue
    public List<CompletableFuture<?>> insert(String agentId, long captureTime) {
        AgentConfig agentConfig = agentConfigDao.read(agentId);
        if (agentConfig == null) {
            // have yet to receive collectInit()
            return ImmutableList.of();
        }
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        List<Integer> rollupExpirationHours =
                configRepository.getCentralStorageConfig().rollupExpirationHours();

        int index = agentId.indexOf("::");
        String topLevelId;
        String childAgentId;
        if (index == -1) {
            topLevelId = agentId;
            childAgentId = null;
        } else {
            topLevelId = agentId.substring(0, index + 2);
            childAgentId = agentId.substring(index + 2);
        }
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (int rollupLevel = 0; rollupLevel < rollupConfigs.size(); rollupLevel++) {
            long rollupIntervalMillis = getRollupIntervalMillis(rollupConfigs, rollupLevel);
            long rollupCaptureTime = CaptureTimes.getRollup(captureTime, rollupIntervalMillis);
            int ttl = Ints.saturatedCast(HOURS.toSeconds(rollupExpirationHours.get(rollupLevel)));
            int adjustedTTL = Common.getAdjustedTTL(ttl, rollupCaptureTime, clock);

            int i = 0;
            BoundStatement boundStatement = insertTopLevelPS.get(rollupLevel).bind()
                .setInstant(i++, Instant.ofEpochMilli(rollupCaptureTime))
                .setString(i++, topLevelId)
                .setInt(i++, adjustedTTL);
            futures.add(session.writeAsync(boundStatement).toCompletableFuture());

            if (childAgentId != null) {
                i = 0;
                boundStatement = insertChildPS.get(rollupLevel).bind()
                    .setString(i++, topLevelId)
                    .setInstant(i++, Instant.ofEpochMilli(rollupCaptureTime))
                    .setString(i++, childAgentId)
                    .setInt(i++, adjustedTTL);
                futures.add(session.writeAsync(boundStatement).toCompletableFuture());
            }
        }
        return futures;
    }

    private List<AgentRollup> readActiveChildAgentRollups(String topLevelId, long from, long to,
            boolean stripTopLevelDisplay) throws Exception {
        int rollupLevel = rollupLevelService.getRollupLevelForView(from, to, DataKind.GENERAL);
        long rollupIntervalMillis =
                getRollupIntervalMillis(configRepository.getRollupConfigs(), rollupLevel);
        long revisedTo = CaptureTimes.getRollup(to, rollupIntervalMillis);

        Set<String> allAgentRollupIds = new HashSet<>();
        Set<String> directChildAgentRollupIds = new HashSet<>();
        Multimap<String, String> childMultimap = HashMultimap.create();
        BoundStatement boundStatement = readChildPS.get(rollupLevel).bind()
            .setString(0, topLevelId)
            .setInstant(1, Instant.ofEpochMilli(from))
            .setInstant(2, Instant.ofEpochMilli(revisedTo));
        ResultSet results = session.read(boundStatement);
        for (Row row : results) {
            String agentId = topLevelId + checkNotNull(row.getString(0));
            List<String> agentRollupIds = AgentRollupIds.getAgentRollupIds(agentId);
            allAgentRollupIds.addAll(agentRollupIds);
            if (agentRollupIds.size() == 2) {
                directChildAgentRollupIds.add(agentId);
            } else {
                String directChildAgentId = agentRollupIds.get(agentRollupIds.size() - 2);
                directChildAgentRollupIds.add(directChildAgentId);
                for (int i = 1; i < agentRollupIds.size() - 1; i++) {
                    childMultimap.put(agentRollupIds.get(i), agentRollupIds.get(i - 1));
                }
            }
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
        for (String topLevelAgentRollupId : directChildAgentRollupIds) {
            agentRollups.add(createAgentRollup(topLevelAgentRollupId, childMultimap,
                    agentDisplayMap, stripTopLevelDisplay));
        }
        agentRollups.sort(Comparator.comparing(AgentRollup::display));
        return agentRollups;
    }

    private static AgentRollup createAgentRollup(String agentRollupId,
            Multimap<String, String> childMultimap, Map<String, String> agentDisplayMap,
            boolean stripTopLevelDisplay) {
        Collection<String> childAgentRollupIds = childMultimap.get(agentRollupId);
        List<String> agentRollupIds = AgentRollupIds.getAgentRollupIds(agentRollupId);
        List<String> displayParts = new ArrayList<>();
        ListIterator<String> i = agentRollupIds.listIterator(agentRollupIds.size());
        if (stripTopLevelDisplay) {
            i.previous();
        }
        while (i.hasPrevious()) {
            displayParts.add(checkNotNull(agentDisplayMap.get(i.previous())));
        }
        ImmutableAgentRollup.Builder builder = ImmutableAgentRollup.builder()
                .id(agentRollupId)
                .display(Joiner.on(" :: ").join(displayParts))
                .lastDisplayPart(displayParts.get(displayParts.size() - 1));
        List<AgentRollup> childAgentRollups = new ArrayList<>();
        for (String childAgentRollupId : childAgentRollupIds) {
            childAgentRollups.add(createAgentRollup(childAgentRollupId, childMultimap,
                    agentDisplayMap, stripTopLevelDisplay));
        }
        childAgentRollups.sort(Comparator.comparing(AgentRollup::display));
        return builder.addAllChildren(childAgentRollups)
                .build();
    }

    private static long getRollupIntervalMillis(List<RollupConfig> rollupConfigs, int rollupLevel) {
        checkState(rollupConfigs.size() == 4); // if size changes, then logic needs to be updated
        if (rollupLevel < 3) {
            return rollupConfigs.get(rollupLevel + 1).intervalMillis();
        } else {
            return DAYS.toMillis(1);
        }
    }

    private static @Nullable AgentRollup getAgentRollup(List<AgentRollup> agentRollups,
            String topLevelAgentRollupId) {
        for (AgentRollup agentRollup : agentRollups) {
            if (agentRollup.id().equals(topLevelAgentRollupId)) {
                return agentRollup;
            }
        }
        return null;
    }
}
