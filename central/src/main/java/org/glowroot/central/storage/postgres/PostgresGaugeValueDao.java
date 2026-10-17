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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import javax.sql.DataSource;

import com.google.common.primitives.Ints;
import org.glowroot.central.repo.GaugeValueDao;
import org.glowroot.common.util.CaptureTimes;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.common2.repo.ConfigRepository;
import org.glowroot.common2.repo.ConfigRepository.RollupConfig;
import org.glowroot.common2.repo.util.Gauges;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueMessage.GaugeValue;

import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.HOURS;

/**
 * Postgres {@link GaugeValueDao}: L0 store/read, gauge-name discovery, and simple level rollup
 * (plus read-time bucket fallback when higher levels are empty).
 */
public class PostgresGaugeValueDao implements GaugeValueDao {

    private final DataSource dataSource;
    private final ConfigRepository configRepository;
    private final Clock clock;
    private final Executor asyncExecutor;

    public PostgresGaugeValueDao(DataSource dataSource, ConfigRepository configRepository,
            Clock clock, Executor asyncExecutor) {
        this.dataSource = dataSource;
        this.configRepository = configRepository;
        this.clock = clock;
        this.asyncExecutor = asyncExecutor;
    }

    @Override
    public CompletionStage<?> store(String agentId, List<GaugeValue> gaugeValues) {
        if (gaugeValues.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return configRepository.getCentralStorageConfig().thenCompose(storage -> {
            int ttlSeconds =
                    Ints.saturatedCast(HOURS.toSeconds(storage.rollupExpirationHours().get(0)));
            CompletableFuture<Void> future = new CompletableFuture<>();
            CompletableFuture.runAsync(() -> {
                try (Connection connection = dataSource.getConnection();
                        PreparedStatement ps = connection.prepareStatement(
                                "insert into gauge_value (agent_rollup, gauge_name, capture_time, "
                                        + "value, weight, rollup_level, expires_at) values "
                                        + "(?, ?, ?, ?, ?, 0, ?) "
                                        + "on conflict (agent_rollup, gauge_name, capture_time, "
                                        + "rollup_level) do update set "
                                        + "value = excluded.value, weight = excluded.weight, "
                                        + "expires_at = excluded.expires_at")) {
                    for (GaugeValue gaugeValue : gaugeValues) {
                        long captureTime = gaugeValue.getCaptureTime();
                        Timestamp expiresAt =
                                PostgresExpires.fromTtlSeconds(ttlSeconds, captureTime, clock);
                        ps.setString(1, agentId);
                        ps.setString(2, gaugeValue.getGaugeName());
                        ps.setTimestamp(3, new Timestamp(captureTime));
                        ps.setDouble(4, gaugeValue.getValue());
                        ps.setLong(5, gaugeValue.getWeight());
                        ps.setTimestamp(6, expiresAt);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                    future.complete(null);
                } catch (SQLException e) {
                    future.completeExceptionally(e);
                }
            }, asyncExecutor);
            return future;
        });
    }

    @Override
    public CompletionStage<?> rollup(String agentRollupId) {
        return configRepository.getCentralStorageConfig().thenCompose(storage -> {
            List<Integer> expirationHours = storage.rollupExpirationHours();
            List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
            return CompletableFuture.runAsync(() -> {
                try (Connection connection = dataSource.getConnection()) {
                    // Gauge levels: L0 raw; L1..Ln map to rollupConfigs[0..n-1]
                    for (int toLevel = 1; toLevel <= rollupConfigs.size(); toLevel++) {
                        int fromLevel = toLevel - 1;
                        int ttlIndex = Math.min(toLevel, expirationHours.size() - 1);
                        int ttlSeconds =
                                Ints.saturatedCast(HOURS.toSeconds(expirationHours.get(ttlIndex)));
                        long intervalMillis = rollupConfigs.get(toLevel - 1).intervalMillis();
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
    public CompletionStage<List<Gauge>> getRecentlyActiveGauges(String agentRollupId) {
        long now = clock.currentTimeMillis();
        return getGauges(agentRollupId, now - DAYS.toMillis(7), now + DAYS.toMillis(365),
                CassandraProfile.web);
    }

    @Override
    public CompletionStage<List<Gauge>> getGauges(String agentRollupId, long from, long to,
            CassandraProfile profile) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(
                            "select distinct gauge_name from gauge_value "
                                    + "where agent_rollup = ? and capture_time >= ? "
                                    + "and capture_time <= ? and expires_at > ? "
                                    + "order by gauge_name")) {
                ps.setString(1, agentRollupId);
                ps.setTimestamp(2, new Timestamp(from));
                ps.setTimestamp(3, new Timestamp(to));
                ps.setTimestamp(4, new Timestamp(clock.currentTimeMillis()));
                List<Gauge> gauges = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        gauges.add(Gauges.getGauge(rs.getString(1)));
                    }
                }
                return gauges;
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }

    @Override
    public CompletionStage<List<GaugeValue>> readGaugeValues(String agentRollupId,
            String gaugeName, long from, long to, int rollupLevel, CassandraProfile profile) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<GaugeValue> values =
                        readLevel(agentRollupId, gaugeName, from, to, rollupLevel);
                if (!values.isEmpty() || rollupLevel <= 0) {
                    return values;
                }
                // Higher levels empty before background rollup catches up — bucket from L0.
                List<GaugeValue> raw = readLevel(agentRollupId, gaugeName, from, to, 0);
                if (raw.isEmpty()) {
                    return raw;
                }
                List<RollupConfig> configs = configRepository.getRollupConfigs();
                int configIndex = Math.min(rollupLevel, configs.size()) - 1;
                if (configIndex < 0) {
                    return raw;
                }
                return bucket(raw, configs.get(configIndex).intervalMillis());
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }

    @Override
    public CompletionStage<Long> getOldestCaptureTime(String agentRollupId, String gaugeName,
            int rollupLevel, CassandraProfile profile) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(
                            "select min(capture_time) from gauge_value "
                                    + "where agent_rollup = ? and gauge_name = ? "
                                    + "and rollup_level = ? and expires_at > ?")) {
                ps.setString(1, agentRollupId);
                ps.setString(2, gaugeName);
                ps.setInt(3, rollupLevel);
                ps.setTimestamp(4, new Timestamp(clock.currentTimeMillis()));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Long.MAX_VALUE;
                    }
                    Timestamp ts = rs.getTimestamp(1);
                    return ts == null ? Long.MAX_VALUE : ts.getTime();
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }

    @Override
    public void truncateAll() throws Exception {
        try (Connection connection = dataSource.getConnection();
                var st = connection.createStatement()) {
            st.execute("truncate gauge_value");
        }
    }

    private void rollupLevel(Connection connection, String agentRollupId, int fromLevel,
            int toLevel, long intervalMillis, int ttlSeconds) throws SQLException {
        long now = clock.currentTimeMillis();
        long safeRollupTime = CaptureTimes.getRollup(now, intervalMillis) - intervalMillis;
        List<String> gaugeNames = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "select distinct gauge_name from gauge_value "
                        + "where agent_rollup = ? and rollup_level = ? and expires_at > ?")) {
            ps.setString(1, agentRollupId);
            ps.setInt(2, fromLevel);
            ps.setTimestamp(3, new Timestamp(now));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    gaugeNames.add(rs.getString(1));
                }
            }
        }
        for (String gaugeName : gaugeNames) {
            // Incremental: only fromLevel points after last closed toLevel bucket (avoid O(n)
            // full-history rescan each rollup cycle). First catch-up still reads from 0 once.
            long from = maxCaptureTime(connection, agentRollupId, gaugeName, toLevel);
            if (from > 0) {
                from = from + 1;
            }
            if (from > safeRollupTime) {
                continue;
            }
            List<GaugeValue> source = readLevel(connection, agentRollupId, gaugeName, from,
                    safeRollupTime, fromLevel);
            if (source.isEmpty()) {
                continue;
            }
            Map<Long, Acc> buckets = new LinkedHashMap<>();
            for (GaugeValue gv : source) {
                long bucket = CaptureTimes.getRollup(gv.getCaptureTime(), intervalMillis);
                if (bucket > safeRollupTime) {
                    continue;
                }
                Acc acc = buckets.get(bucket);
                if (acc == null) {
                    acc = new Acc();
                    buckets.put(bucket, acc);
                }
                acc.valueWeightedSum += gv.getValue() * gv.getWeight();
                acc.weightSum += gv.getWeight();
            }
            for (Map.Entry<Long, Acc> e : buckets.entrySet()) {
                Acc acc = e.getValue();
                if (acc.weightSum == 0) {
                    continue;
                }
                double value = acc.valueWeightedSum / acc.weightSum;
                Timestamp expiresAt =
                        PostgresExpires.fromTtlSeconds(ttlSeconds, e.getKey(), clock);
                try (PreparedStatement upsert = connection.prepareStatement(
                        "insert into gauge_value (agent_rollup, gauge_name, capture_time, value, "
                                + "weight, rollup_level, expires_at) values (?, ?, ?, ?, ?, ?, ?) "
                                + "on conflict (agent_rollup, gauge_name, capture_time, "
                                + "rollup_level) do update set "
                                + "value = excluded.value, weight = excluded.weight, "
                                + "expires_at = excluded.expires_at")) {
                    upsert.setString(1, agentRollupId);
                    upsert.setString(2, gaugeName);
                    upsert.setTimestamp(3, new Timestamp(e.getKey()));
                    upsert.setDouble(4, value);
                    upsert.setLong(5, acc.weightSum);
                    upsert.setInt(6, toLevel);
                    upsert.setTimestamp(7, expiresAt);
                    upsert.executeUpdate();
                }
            }
        }
    }

    private static long maxCaptureTime(Connection connection, String agentRollupId,
            String gaugeName, int rollupLevel) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "select max(capture_time) from gauge_value "
                        + "where agent_rollup = ? and gauge_name = ? and rollup_level = ? "
                        + "and expires_at > now()")) {
            ps.setString(1, agentRollupId);
            ps.setString(2, gaugeName);
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

    private List<GaugeValue> readLevel(String agentRollupId, String gaugeName, long from, long to,
            int rollupLevel) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return readLevel(connection, agentRollupId, gaugeName, from, to, rollupLevel);
        }
    }

    private List<GaugeValue> readLevel(Connection connection, String agentRollupId,
            String gaugeName, long from, long to, int rollupLevel) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "select capture_time, value, weight from gauge_value "
                        + "where agent_rollup = ? and gauge_name = ? "
                        + "and rollup_level = ? and capture_time >= ? "
                        + "and capture_time <= ? and expires_at > ? "
                        + "order by capture_time")) {
            ps.setString(1, agentRollupId);
            ps.setString(2, gaugeName);
            ps.setInt(3, rollupLevel);
            ps.setTimestamp(4, new Timestamp(from));
            ps.setTimestamp(5, new Timestamp(to));
            ps.setTimestamp(6, new Timestamp(clock.currentTimeMillis()));
            List<GaugeValue> values = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    values.add(GaugeValue.newBuilder()
                            .setCaptureTime(rs.getTimestamp(1).getTime())
                            .setValue(rs.getDouble(2))
                            .setWeight(rs.getLong(3))
                            .build());
                }
            }
            return values;
        }
    }

    private static List<GaugeValue> bucket(List<GaugeValue> raw, long intervalMillis) {
        Map<Long, Acc> buckets = new LinkedHashMap<>();
        for (GaugeValue gv : raw) {
            long bucket = CaptureTimes.getRollup(gv.getCaptureTime(), intervalMillis);
            Acc acc = buckets.get(bucket);
            if (acc == null) {
                acc = new Acc();
                buckets.put(bucket, acc);
            }
            acc.valueWeightedSum += gv.getValue() * gv.getWeight();
            acc.weightSum += gv.getWeight();
        }
        List<GaugeValue> out = new ArrayList<>(buckets.size());
        for (Map.Entry<Long, Acc> e : buckets.entrySet()) {
            Acc acc = e.getValue();
            if (acc.weightSum == 0) {
                continue;
            }
            out.add(GaugeValue.newBuilder()
                    .setCaptureTime(e.getKey())
                    .setValue(acc.valueWeightedSum / acc.weightSum)
                    .setWeight(acc.weightSum)
                    .build());
        }
        return out;
    }

    private static final class Acc {
        double valueWeightedSum;
        long weightSum;
    }
}
