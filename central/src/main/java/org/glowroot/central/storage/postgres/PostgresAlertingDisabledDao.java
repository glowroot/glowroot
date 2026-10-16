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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import javax.sql.DataSource;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.glowroot.common.util.Clock;

/**
 * Postgres port of Cassandra {@code AlertingDisabledDao}.
 */
public class PostgresAlertingDisabledDao {

    private final DataSource dataSource;
    private final Clock clock;
    private final Executor asyncExecutor;

    public PostgresAlertingDisabledDao(DataSource dataSource, Clock clock, Executor asyncExecutor) {
        this.dataSource = dataSource;
        this.clock = clock;
        this.asyncExecutor = asyncExecutor;
    }

    public CompletionStage<@Nullable Long> getAlertingDisabledUntilTime(String agentRollupId) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(
                            "select disabled_until_time from alerting_disabled "
                                    + "where agent_rollup_id = ?")) {
                ps.setString(1, agentRollupId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    Timestamp ts = rs.getTimestamp(1);
                    if (ts == null) {
                        return null;
                    }
                    long until = ts.getTime();
                    if (until <= clock.currentTimeMillis()) {
                        return null;
                    }
                    return until;
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }

    public CompletionStage<?> setAlertingDisabledUntilTime(String agentRollupId,
            @Nullable Long disabledUntilTime) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try {
                if (disabledUntilTime == null) {
                    try (Connection connection = dataSource.getConnection();
                            PreparedStatement ps = connection.prepareStatement(
                                    "delete from alerting_disabled where agent_rollup_id = ?")) {
                        ps.setString(1, agentRollupId);
                        ps.executeUpdate();
                    }
                } else {
                    try (Connection connection = dataSource.getConnection();
                            PreparedStatement ps = connection.prepareStatement(
                                    "insert into alerting_disabled (agent_rollup_id, "
                                            + "disabled_until_time) values (?, ?) "
                                            + "on conflict (agent_rollup_id) do update set "
                                            + "disabled_until_time = excluded.disabled_until_time")) {
                        ps.setString(1, agentRollupId);
                        ps.setTimestamp(2, new Timestamp(disabledUntilTime));
                        ps.executeUpdate();
                    }
                }
                future.complete(null);
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        }, asyncExecutor);
        return future;
    }
}
