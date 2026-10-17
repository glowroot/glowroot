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
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import javax.sql.DataSource;

import com.google.common.primitives.Ints;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.glowroot.central.repo.SyntheticResultDao;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.repo.ConfigRepository;
import org.glowroot.common2.repo.SyntheticResult;

import static java.util.concurrent.TimeUnit.HOURS;

/**
 * Minimal Postgres {@link SyntheticResultDao}: L0 store only; reads/rollup stubs for boot.
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
        return CompletableFuture.completedFuture(Collections.emptyList());
    }

    @Override
    public CompletionStage<?> rollup(String agentRollupId) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public Map<String, String> getSyntheticMonitorIds(String agentRollupId, long from, long to) {
        return Collections.emptyMap();
    }

    @Override
    public CompletionStage<List<SyntheticResult>> readSyntheticResults(String agentRollupId,
            String syntheticMonitorId, long from, long to, int rollupLevel) {
        return CompletableFuture.completedFuture(Collections.emptyList());
    }

    public void truncateAll() throws Exception {
        try (Connection connection = dataSource.getConnection();
                var st = connection.createStatement()) {
            st.execute("truncate synthetic_result");
        }
    }
}
