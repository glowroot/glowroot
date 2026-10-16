/*
 * Copyright 2026 the original author or authors.
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
package org.glowroot.central.storage.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
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
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;
import javax.sql.DataSource;

import com.google.common.base.Joiner;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.primitives.Ints;
import edu.umd.cs.findbugs.annotations.CheckReturnValue;
import org.glowroot.central.repo.AgentRollupIds;
import org.glowroot.common.util.CaptureTimes;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.repo.ActiveAgentRepository;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.common2.repo.ConfigRepository;
import org.glowroot.common2.repo.ConfigRepository.RollupConfig;
import org.glowroot.common2.repo.ImmutableAgentRollup;
import org.glowroot.common2.repo.ImmutableTopLevelAgentRollup;
import org.glowroot.common2.repo.util.RollupLevelService;
import org.glowroot.common2.repo.util.RollupLevelService.DataKind;
import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.HOURS;

/**
 * Postgres port of Cassandra {@code ActiveAgentDao}.
 */
public class PostgresActiveAgentDao implements ActiveAgentRepository {

    private final DataSource dataSource;
    private final PostgresAgentDisplayDao agentDisplayDao;
    private final PostgresAgentConfigDao agentConfigDao;
    private final ConfigRepository configRepository;
    private final RollupLevelService rollupLevelService;
    private final Clock clock;
    private final Executor asyncExecutor;

    public PostgresActiveAgentDao(DataSource dataSource, PostgresAgentDisplayDao agentDisplayDao,
            PostgresAgentConfigDao agentConfigDao, ConfigRepository configRepository,
            RollupLevelService rollupLevelService, Clock clock, Executor asyncExecutor) {
        this.dataSource = dataSource;
        this.agentDisplayDao = agentDisplayDao;
        this.agentConfigDao = agentConfigDao;
        this.configRepository = configRepository;
        this.rollupLevelService = rollupLevelService;
        this.clock = clock;
        this.asyncExecutor = asyncExecutor;
    }

    @Override
    public CompletionStage<List<TopLevelAgentRollup>> readActiveTopLevelAgentRollups(long from,
            long to, CassandraProfile profile) {
        int rollupLevel = rollupLevelService.getRollupLevelForView(from, to, DataKind.GENERAL);
        long rollupIntervalMillis =
                getRollupIntervalMillis(configRepository.getRollupConfigs(), rollupLevel);
        long revisedTo = CaptureTimes.getRollup(to, rollupIntervalMillis);
        return CompletableFuture.supplyAsync(() -> {
            Set<String> topLevelIds = new HashSet<>();
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(
                            "select distinct top_level_id from active_top_level_rollup "
                                    + "where rollup_level = ? and capture_time >= ? "
                                    + "and capture_time <= ? and expires_at > ?")) {
                ps.setInt(1, rollupLevel);
                ps.setTimestamp(2, new Timestamp(from));
                ps.setTimestamp(3, new Timestamp(revisedTo));
                ps.setTimestamp(4, new Timestamp(clock.currentTimeMillis()));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        topLevelIds.add(rs.getString(1));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            List<TopLevelAgentRollup> agentRollups = new ArrayList<>();
            for (String topLevelId : topLevelIds) {
                // sync read — already on asyncExecutor; nested .join() would deadlock the pool
                String display = agentDisplayDao.readLastDisplayPart(topLevelId);
                agentRollups.add(ImmutableTopLevelAgentRollup.builder()
                        .id(topLevelId)
                        .display(display)
                        .build());
            }
            agentRollups.sort(Comparator.comparing(TopLevelAgentRollup::display));
            return agentRollups;
        }, asyncExecutor);
    }

    @Override
    public CompletionStage<List<AgentRollup>> readActiveChildAgentRollups(String topLevelId,
            long from, long to, CassandraProfile profile) {
        return readActiveChildAgentRollups(topLevelId, from, to, true);
    }

    @Override
    public CompletionStage<List<AgentRollup>> readRecentlyActiveAgentRollups(long lastXMillis,
            CassandraProfile profile) {
        long now = clock.currentTimeMillis();
        return readActiveAgentRollups(now - lastXMillis, now, profile);
    }

    @Override
    public CompletionStage<List<AgentRollup>> readActiveAgentRollups(long from, long to,
            CassandraProfile profile) {
        return readActiveTopLevelAgentRollups(from, to, profile).thenCompose(topLevelAgentRollups -> {
            List<CompletionStage<AgentRollup>> futures = new ArrayList<>();
            for (TopLevelAgentRollup topLevelAgentRollup : topLevelAgentRollups) {
                ImmutableAgentRollup.Builder builder = ImmutableAgentRollup.builder()
                        .id(topLevelAgentRollup.id())
                        .display(topLevelAgentRollup.display())
                        .lastDisplayPart(topLevelAgentRollup.display());
                if (topLevelAgentRollup.id().endsWith("::")) {
                    futures.add(readActiveChildAgentRollups(topLevelAgentRollup.id(), from, to,
                            false).thenApply(list -> builder.addAllChildren(list).build()));
                } else {
                    futures.add(CompletableFuture.completedFuture(builder.build()));
                }
            }
            @SuppressWarnings("unchecked")
            CompletableFuture<AgentRollup>[] array = futures.stream()
                    .map(CompletionStage::toCompletableFuture)
                    .toArray(CompletableFuture[]::new);
            return CompletableFuture.allOf(array).thenApply(v -> {
                List<AgentRollup> agentRollups = new ArrayList<>();
                for (CompletableFuture<AgentRollup> future : array) {
                    agentRollups.add(future.join());
                }
                return agentRollups;
            });
        });
    }

    @CheckReturnValue
    public CompletionStage<?> insert(String agentId, long captureTime) {
        return agentConfigDao.readAsync(agentId).thenCompose(agentConfig -> {
            if (agentConfig == null) {
                // have yet to receive collectInit()
                return CompletableFuture.completedFuture(null);
            }
            return insertWithConfig(agentId, captureTime);
        });
    }

    private CompletionStage<?> insertWithConfig(String agentId, long captureTime) {
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        return configRepository.getCentralStorageConfig().thenCompose(centralStorageConfig -> {
            List<Integer> rollupExpirationHours = centralStorageConfig.rollupExpirationHours();
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
            final String topLevelIdFinal = topLevelId;
            final @Nullable String childAgentIdFinal = childAgentId;
            CompletableFuture<Void> future = new CompletableFuture<>();
            CompletableFuture.runAsync(() -> {
                try (Connection connection = dataSource.getConnection()) {
                    try (PreparedStatement topPs = connection.prepareStatement(
                            "insert into active_top_level_rollup (rollup_level, capture_time, "
                                    + "top_level_id, expires_at) values (?, ?, ?, ?) "
                                    + "on conflict (rollup_level, capture_time, top_level_id) "
                                    + "do update set expires_at = excluded.expires_at");
                            PreparedStatement childPs = connection.prepareStatement(
                                    "insert into active_child_rollup (rollup_level, top_level_id, "
                                            + "capture_time, child_agent_id, expires_at) "
                                            + "values (?, ?, ?, ?, ?) "
                                            + "on conflict (rollup_level, top_level_id, capture_time, "
                                            + "child_agent_id) do update set "
                                            + "expires_at = excluded.expires_at")) {
                        for (int rollupLevel = 0; rollupLevel < rollupConfigs.size();
                                rollupLevel++) {
                            long rollupIntervalMillis =
                                    getRollupIntervalMillis(rollupConfigs, rollupLevel);
                            long rollupCaptureTime =
                                    CaptureTimes.getRollup(captureTime, rollupIntervalMillis);
                            int ttl = Ints.saturatedCast(
                                    HOURS.toSeconds(rollupExpirationHours.get(rollupLevel)));
                            Timestamp expiresAt =
                                    PostgresExpires.fromTtlSeconds(ttl, rollupCaptureTime, clock);
                            topPs.setInt(1, rollupLevel);
                            topPs.setTimestamp(2, new Timestamp(rollupCaptureTime));
                            topPs.setString(3, topLevelIdFinal);
                            topPs.setTimestamp(4, expiresAt);
                            topPs.addBatch();
                            if (childAgentIdFinal != null) {
                                childPs.setInt(1, rollupLevel);
                                childPs.setString(2, topLevelIdFinal);
                                childPs.setTimestamp(3, new Timestamp(rollupCaptureTime));
                                childPs.setString(4, childAgentIdFinal);
                                childPs.setTimestamp(5, expiresAt);
                                childPs.addBatch();
                            }
                        }
                        topPs.executeBatch();
                        if (childAgentIdFinal != null) {
                            childPs.executeBatch();
                        }
                    }
                    future.complete(null);
                } catch (SQLException e) {
                    future.completeExceptionally(e);
                }
            }, asyncExecutor);
            return future;
        });
    }

    private CompletionStage<List<AgentRollup>> readActiveChildAgentRollups(String topLevelId,
            long from, long to, boolean stripTopLevelDisplay) {
        int rollupLevel = rollupLevelService.getRollupLevelForView(from, to, DataKind.GENERAL);
        long rollupIntervalMillis =
                getRollupIntervalMillis(configRepository.getRollupConfigs(), rollupLevel);
        long revisedTo = CaptureTimes.getRollup(to, rollupIntervalMillis);
        return CompletableFuture.supplyAsync(() -> {
            List<String> agentIds = new ArrayList<>();
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(
                            "select distinct child_agent_id from active_child_rollup "
                                    + "where rollup_level = ? and top_level_id = ? "
                                    + "and capture_time >= ? and capture_time <= ? "
                                    + "and expires_at > ?")) {
                ps.setInt(1, rollupLevel);
                ps.setString(2, topLevelId);
                ps.setTimestamp(3, new Timestamp(from));
                ps.setTimestamp(4, new Timestamp(revisedTo));
                ps.setTimestamp(5, new Timestamp(clock.currentTimeMillis()));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        agentIds.add(topLevelId + rs.getString(1));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            Set<String> allAgentRollupIds = new HashSet<>();
            Set<String> directChildAgentRollupIds = new HashSet<>();
            Multimap<String, String> childMultimap = HashMultimap.create();
            for (String agentId : agentIds) {
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
            Map<String, String> agentDisplayMap = new HashMap<>();
            for (String agentRollupId : allAgentRollupIds) {
                agentDisplayMap.put(agentRollupId,
                        agentDisplayDao.readLastDisplayPart(agentRollupId));
            }
            List<AgentRollup> agentRollups = new ArrayList<>();
            for (String topLevelAgentRollupId : directChildAgentRollupIds) {
                agentRollups.add(createAgentRollup(topLevelAgentRollupId, childMultimap,
                        agentDisplayMap, stripTopLevelDisplay));
            }
            agentRollups.sort(Comparator.comparing(AgentRollup::display));
            return agentRollups;
        }, asyncExecutor);
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
            displayParts.add(agentDisplayMap.get(i.previous()));
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
        return builder.addAllChildren(childAgentRollups).build();
    }

    private static long getRollupIntervalMillis(List<RollupConfig> rollupConfigs, int rollupLevel) {
        checkState(rollupConfigs.size() == 4);
        if (rollupLevel < 3) {
            return rollupConfigs.get(rollupLevel + 1).intervalMillis();
        } else {
            return DAYS.toMillis(1);
        }
    }
}
