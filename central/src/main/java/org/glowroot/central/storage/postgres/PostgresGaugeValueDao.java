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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import javax.sql.DataSource;

import com.google.common.primitives.Ints;
import org.glowroot.central.repo.GaugeValueDao;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.common2.repo.ConfigRepository;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueMessage.GaugeValue;

import static java.util.concurrent.TimeUnit.HOURS;

/**
 * Minimal Postgres {@link GaugeValueDao}: L0 store/read only; rollup / gauge-name meta no-op.
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
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<List<Gauge>> getRecentlyActiveGauges(String agentRollupId) {
        return CompletableFuture.completedFuture(Collections.emptyList());
    }

    @Override
    public CompletionStage<List<Gauge>> getGauges(String agentRollupId, long from, long to,
            CassandraProfile profile) {
        return CompletableFuture.completedFuture(Collections.emptyList());
    }

    @Override
    public CompletionStage<List<GaugeValue>> readGaugeValues(String agentRollupId,
            String gaugeName, long from, long to, int rollupLevel, CassandraProfile profile) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(
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
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }

    @Override
    public CompletionStage<Long> getOldestCaptureTime(String agentRollupId, String gaugeName,
            int rollupLevel, CassandraProfile profile) {
        return CompletableFuture.completedFuture(0L);
    }

    @Override
    public void truncateAll() throws Exception {
        try (Connection connection = dataSource.getConnection();
                var st = connection.createStatement()) {
            st.execute("truncate gauge_value");
        }
    }
}
