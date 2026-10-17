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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import javax.sql.DataSource;

import org.glowroot.central.repo.AgentRollupIds;
import org.glowroot.central.repo.HeartbeatStore;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.repo.CassandraProfile;

import static java.util.concurrent.TimeUnit.HOURS;

/**
 * Postgres port of Cassandra {@code HeartbeatDao}.
 */
public class PostgresHeartbeatDao implements HeartbeatStore {

    static final int EXPIRATION_HOURS = 24;

    private final DataSource dataSource;
    private final Clock clock;
    private final Executor asyncExecutor;

    public PostgresHeartbeatDao(DataSource dataSource, Clock clock, Executor asyncExecutor) {
        this.dataSource = dataSource;
        this.clock = clock;
        this.asyncExecutor = asyncExecutor;
    }

    public CompletionStage<?> store(String agentId) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try {
                long now = clock.currentTimeMillis();
                Timestamp capture = new Timestamp(now);
                Timestamp expires = new Timestamp(now + HOURS.toMillis(EXPIRATION_HOURS));
                List<String> agentRollupIds = AgentRollupIds.getAgentRollupIds(agentId);
                try (Connection connection = dataSource.getConnection();
                        PreparedStatement ps = connection.prepareStatement(
                                "insert into heartbeat (agent_id, central_capture_time, expires_at) "
                                        + "values (?, ?, ?) on conflict (agent_id, central_capture_time) "
                                        + "do update set expires_at = excluded.expires_at")) {
                    for (String agentRollupId : agentRollupIds) {
                        ps.setString(1, agentRollupId);
                        ps.setTimestamp(2, capture);
                        ps.setTimestamp(3, expires);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                future.complete(null);
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        }, asyncExecutor);
        return future;
    }

    @Override
    public CompletionStage<Boolean> exists(String agentRollupId, long centralCaptureFrom,
            long centralCaptureTo, CassandraProfile profile) {
        return exists(agentRollupId, centralCaptureFrom, centralCaptureTo);
    }

    public CompletionStage<Boolean> exists(String agentRollupId, long centralCaptureFrom,
            long centralCaptureTo) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(
                            "select 1 from heartbeat where agent_id = ? "
                                    + "and central_capture_time > ? and central_capture_time <= ? "
                                    + "and expires_at > ? limit 1")) {
                ps.setString(1, agentRollupId);
                ps.setTimestamp(2, new Timestamp(centralCaptureFrom));
                ps.setTimestamp(3, new Timestamp(centralCaptureTo));
                ps.setTimestamp(4, new Timestamp(clock.currentTimeMillis()));
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }
}
