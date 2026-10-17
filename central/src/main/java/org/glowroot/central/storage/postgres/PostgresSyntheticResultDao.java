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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import javax.sql.DataSource;

import com.google.common.primitives.Ints;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.glowroot.central.repo.ImmutableSyntheticResultRollup0;
import org.glowroot.central.repo.SyntheticResultDao;
import org.glowroot.common.util.CaptureTimes;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.repo.ConfigRepository;
import org.glowroot.common2.repo.ConfigRepository.RollupConfig;
import org.glowroot.common2.repo.ImmutableSyntheticResult;
import org.glowroot.common2.repo.SyntheticResult;

import static java.util.concurrent.TimeUnit.HOURS;

/**
 * Postgres {@link SyntheticResultDao}: L0 store/read plus simple closed-bucket level rollup.
 * Error intervals are not persisted (error_count only).
 */
public class PostgresSyntheticResultDao implements SyntheticResultDao {

    private final DataSource dataSource;
    private final ConfigRepository configRepository;
    private final Clock clock;
    private final Executor asyncExecutor;

    public PostgresSyntheticResultDao(DataSource dataSource, ConfigRepository configRepository,
            Clock clock, Executor asyncExecutor) {
        this.dataSource = dataSource;
        this.configRepository = configRepository;
        this.clock = clock;
        this.asyncExecutor = asyncExecutor;
    }

    @Override
    public CompletionStage<?> store(String agentRollupId, String syntheticMonitorId,
            String syntheticMonitorDisplay, long captureTime, long durationNanos,
            @Nullable String errorMessage) {
        return configRepository.getCentralStorageConfig().thenCompose(storage -> {
            int ttlSeconds =
                    Ints.saturatedCast(HOURS.toSeconds(storage.rollupExpirationHours().get(0)));
            Timestamp expiresAt =
                    PostgresExpires.fromTtlSeconds(ttlSeconds, captureTime, clock);
            long errorCount = errorMessage == null ? 0L : 1L;
            CompletableFuture<Void> future = new CompletableFuture<>();
            CompletableFuture.runAsync(() -> {
                try (Connection connection = dataSource.getConnection();
                        PreparedStatement ps = connection.prepareStatement(
                                "insert into synthetic_result (agent_rollup, synthetic_monitor_id, "
                                        + "capture_time, total_duration_nanos, execution_count, "
                                        + "error_count, rollup_level, display, expires_at) values "
                                        + "(?, ?, ?, ?, 1, ?, 0, ?, ?) "
                                        + "on conflict (agent_rollup, synthetic_monitor_id, "
                                        + "capture_time, rollup_level) do update set "
                                        + "total_duration_nanos = excluded.total_duration_nanos, "
                                        + "execution_count = excluded.execution_count, "
                                        + "error_count = excluded.error_count, "
                                        + "display = excluded.display, "
                                        + "expires_at = excluded.expires_at")) {
                    ps.setString(1, agentRollupId);
                    ps.setString(2, syntheticMonitorId);
                    ps.setTimestamp(3, new Timestamp(captureTime));
                    ps.setDouble(4, durationNanos);
                    ps.setLong(5, errorCount);
                    ps.setString(6, syntheticMonitorDisplay);
                    ps.setTimestamp(7, expiresAt);
                    ps.executeUpdate();
                    future.complete(null);
                } catch (SQLException e) {
                    future.completeExceptionally(e);
                }
            }, asyncExecutor);
            return future;
        });
    }

    @Override
    public CompletionStage<List<SyntheticResultRollup0>> readLastFromRollup0(String agentRollupId,
            String syntheticMonitorId, int x) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(
                            "select capture_time, total_duration_nanos, error_count from "
                                    + "synthetic_result where agent_rollup = ? "
                                    + "and synthetic_monitor_id = ? and rollup_level = 0 "
                                    + "and expires_at > ? order by capture_time desc limit ?")) {
                ps.setString(1, agentRollupId);
                ps.setString(2, syntheticMonitorId);
                ps.setTimestamp(3, new Timestamp(clock.currentTimeMillis()));
                ps.setInt(4, Math.max(x, 0));
                List<SyntheticResultRollup0> rows = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(ImmutableSyntheticResultRollup0.builder()
                                .captureTime(rs.getTimestamp(1).getTime())
                                .totalDurationNanos(rs.getDouble(2))
                                .error(rs.getLong(3) > 0)
                                .build());
                    }
                }
                Collections.reverse(rows);
                return rows;
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }

    @Override
    public CompletionStage<?> rollup(String agentRollupId) {
        return configRepository.getCentralStorageConfig().thenCompose(storage -> {
            List<Integer> expirationHours = storage.rollupExpirationHours();
            List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
            return CompletableFuture.runAsync(() -> {
                try (Connection connection = dataSource.getConnection()) {
                    // Cassandra synthetic levels are 0..rollupConfigs.size()-1; rollup starts at 1.
                    for (int toLevel = 1; toLevel < rollupConfigs.size(); toLevel++) {
                        int fromLevel = toLevel - 1;
                        int ttlIndex = Math.min(toLevel, expirationHours.size() - 1);
                        int ttlSeconds = Ints.saturatedCast(
                                HOURS.toSeconds(expirationHours.get(ttlIndex)));
                        long intervalMillis = rollupConfigs.get(toLevel).intervalMillis();
                        rollupLevel(connection, agentRollupId, fromLevel, toLevel, intervalMillis,
                                ttlSeconds);
                    }
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            }, asyncExecutor);
        });
    }

    @Override
    public Map<String, String> getSyntheticMonitorIds(String agentRollupId, long from, long to) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select distinct synthetic_monitor_id, display from synthetic_result "
                                + "where agent_rollup = ? and capture_time >= ? "
                                + "and capture_time <= ? and expires_at > ? "
                                + "order by synthetic_monitor_id")) {
            ps.setString(1, agentRollupId);
            ps.setTimestamp(2, new Timestamp(from));
            ps.setTimestamp(3, new Timestamp(to));
            ps.setTimestamp(4, new Timestamp(clock.currentTimeMillis()));
            Map<String, String> ids = new LinkedHashMap<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.put(rs.getString(1), rs.getString(2));
                }
            }
            return ids;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CompletionStage<List<SyntheticResult>> readSyntheticResults(String agentRollupId,
            String syntheticMonitorId, long from, long to, int rollupLevel) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<SyntheticResult> values =
                        readLevel(agentRollupId, syntheticMonitorId, from, to, rollupLevel);
                if (!values.isEmpty() || rollupLevel <= 0) {
                    return values;
                }
                List<SyntheticResult> raw =
                        readLevel(agentRollupId, syntheticMonitorId, from, to, 0);
                if (raw.isEmpty()) {
                    return raw;
                }
                List<RollupConfig> configs = configRepository.getRollupConfigs();
                int configIndex = Math.min(rollupLevel, configs.size() - 1);
                return bucket(raw, configs.get(configIndex).intervalMillis());
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }

    public void truncateAll() throws Exception {
        try (Connection connection = dataSource.getConnection();
                var st = connection.createStatement()) {
            st.execute("truncate synthetic_result");
        }
    }

    private void rollupLevel(Connection connection, String agentRollupId, int fromLevel,
            int toLevel, long intervalMillis, int ttlSeconds) throws SQLException {
        long now = clock.currentTimeMillis();
        long safeRollupTime = CaptureTimes.getRollup(now, intervalMillis) - intervalMillis;
        List<MonitorMeta> monitors = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "select distinct synthetic_monitor_id, display from synthetic_result "
                        + "where agent_rollup = ? and rollup_level = ? and expires_at > ?")) {
            ps.setString(1, agentRollupId);
            ps.setInt(2, fromLevel);
            ps.setTimestamp(3, new Timestamp(now));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    monitors.add(new MonitorMeta(rs.getString(1), rs.getString(2)));
                }
            }
        }
        for (MonitorMeta monitor : monitors) {
            // Incremental: only fromLevel points after last closed toLevel bucket.
            long from = maxCaptureTime(connection, agentRollupId, monitor.id, toLevel);
            if (from > 0) {
                from = from + 1;
            }
            if (from > safeRollupTime) {
                continue;
            }
            List<RowAcc> source = readLevelRows(connection, agentRollupId, monitor.id, from,
                    safeRollupTime, fromLevel);
            if (source.isEmpty()) {
                continue;
            }
            Map<Long, Acc> buckets = new LinkedHashMap<>();
            for (RowAcc row : source) {
                long bucket = CaptureTimes.getRollup(row.captureTime, intervalMillis);
                if (bucket > safeRollupTime) {
                    continue;
                }
                Acc acc = buckets.get(bucket);
                if (acc == null) {
                    acc = new Acc();
                    buckets.put(bucket, acc);
                }
                acc.totalDurationNanos += row.totalDurationNanos;
                acc.executionCount += row.executionCount;
                acc.errorCount += row.errorCount;
            }
            for (Map.Entry<Long, Acc> e : buckets.entrySet()) {
                Acc acc = e.getValue();
                Timestamp expiresAt =
                        PostgresExpires.fromTtlSeconds(ttlSeconds, e.getKey(), clock);
                try (PreparedStatement upsert = connection.prepareStatement(
                        "insert into synthetic_result (agent_rollup, synthetic_monitor_id, "
                                + "capture_time, total_duration_nanos, execution_count, "
                                + "error_count, rollup_level, display, expires_at) "
                                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                                + "on conflict (agent_rollup, synthetic_monitor_id, capture_time, "
                                + "rollup_level) do update set "
                                + "total_duration_nanos = excluded.total_duration_nanos, "
                                + "execution_count = excluded.execution_count, "
                                + "error_count = excluded.error_count, "
                                + "display = excluded.display, "
                                + "expires_at = excluded.expires_at")) {
                    upsert.setString(1, agentRollupId);
                    upsert.setString(2, monitor.id);
                    upsert.setTimestamp(3, new Timestamp(e.getKey()));
                    upsert.setDouble(4, acc.totalDurationNanos);
                    upsert.setLong(5, acc.executionCount);
                    upsert.setLong(6, acc.errorCount);
                    upsert.setInt(7, toLevel);
                    upsert.setString(8, monitor.display);
                    upsert.setTimestamp(9, expiresAt);
                    upsert.executeUpdate();
                }
            }
        }
    }

    private static long maxCaptureTime(Connection connection, String agentRollupId,
            String syntheticMonitorId, int rollupLevel) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "select max(capture_time) from synthetic_result "
                        + "where agent_rollup = ? and synthetic_monitor_id = ? "
                        + "and rollup_level = ? and expires_at > now()")) {
            ps.setString(1, agentRollupId);
            ps.setString(2, syntheticMonitorId);
            ps.setInt(3, rollupLevel);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Timestamp ts = rs.getTimestamp(1);
                    if (ts != null) {
                        return ts.getTime();
                    }
                }
            }
        }
        return 0;
    }

    private List<SyntheticResult> readLevel(String agentRollupId, String syntheticMonitorId,
            long from, long to, int rollupLevel) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            List<RowAcc> rows =
                    readLevelRows(connection, agentRollupId, syntheticMonitorId, from, to,
                            rollupLevel);
            List<SyntheticResult> out = new ArrayList<>(rows.size());
            for (RowAcc row : rows) {
                out.add(ImmutableSyntheticResult.builder()
                        .captureTime(row.captureTime)
                        .totalDurationNanos(row.totalDurationNanos)
                        .executionCount(row.executionCount)
                        .addAllErrorIntervals(Collections.emptyList())
                        .build());
            }
            return out;
        }
    }

    private List<RowAcc> readLevelRows(Connection connection, String agentRollupId,
            String syntheticMonitorId, long from, long to, int rollupLevel) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "select capture_time, total_duration_nanos, execution_count, error_count "
                        + "from synthetic_result where agent_rollup = ? "
                        + "and synthetic_monitor_id = ? and rollup_level = ? "
                        + "and capture_time >= ? and capture_time <= ? and expires_at > ? "
                        + "order by capture_time")) {
            ps.setString(1, agentRollupId);
            ps.setString(2, syntheticMonitorId);
            ps.setInt(3, rollupLevel);
            ps.setTimestamp(4, new Timestamp(from));
            ps.setTimestamp(5, new Timestamp(to));
            ps.setTimestamp(6, new Timestamp(clock.currentTimeMillis()));
            List<RowAcc> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new RowAcc(rs.getTimestamp(1).getTime(), rs.getDouble(2),
                            rs.getLong(3), rs.getLong(4)));
                }
            }
            return rows;
        }
    }

    private static List<SyntheticResult> bucket(List<SyntheticResult> raw, long intervalMillis) {
        Map<Long, Acc> buckets = new LinkedHashMap<>();
        for (SyntheticResult row : raw) {
            long bucket = CaptureTimes.getRollup(row.captureTime(), intervalMillis);
            Acc acc = buckets.get(bucket);
            if (acc == null) {
                acc = new Acc();
                buckets.put(bucket, acc);
            }
            acc.totalDurationNanos += row.totalDurationNanos();
            acc.executionCount += row.executionCount();
        }
        List<SyntheticResult> out = new ArrayList<>(buckets.size());
        for (Map.Entry<Long, Acc> e : buckets.entrySet()) {
            Acc acc = e.getValue();
            out.add(ImmutableSyntheticResult.builder()
                    .captureTime(e.getKey())
                    .totalDurationNanos(acc.totalDurationNanos)
                    .executionCount(acc.executionCount)
                    .addAllErrorIntervals(Collections.emptyList())
                    .build());
        }
        return out;
    }

    private static final class MonitorMeta {
        final String id;
        final String display;

        MonitorMeta(String id, String display) {
            this.id = id;
            this.display = display;
        }
    }

    private static final class RowAcc {
        final long captureTime;
        final double totalDurationNanos;
        final long executionCount;
        final long errorCount;

        RowAcc(long captureTime, double totalDurationNanos, long executionCount, long errorCount) {
            this.captureTime = captureTime;
            this.totalDurationNanos = totalDurationNanos;
            this.executionCount = executionCount;
            this.errorCount = errorCount;
        }
    }

    private static final class Acc {
        double totalDurationNanos;
        long executionCount;
        long errorCount;
    }
}
