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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import javax.sql.DataSource;

/**
 * Minimal Postgres port of Cassandra {@code AgentDisplayDao#store}.
 */
public class PostgresAgentDisplayDao {

    private final DataSource dataSource;
    private final Executor asyncExecutor;

    public PostgresAgentDisplayDao(DataSource dataSource, Executor asyncExecutor) {
        this.dataSource = dataSource;
        this.asyncExecutor = asyncExecutor;
    }

    public CompletionStage<?> store(String agentRollupId, String display) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try {
                if (display.isEmpty()) {
                    try (Connection connection = dataSource.getConnection();
                            PreparedStatement ps = connection.prepareStatement(
                                    "delete from agent_display where agent_rollup_id = ?")) {
                        ps.setString(1, agentRollupId);
                        ps.executeUpdate();
                    }
                } else {
                    try (Connection connection = dataSource.getConnection();
                            PreparedStatement ps = connection.prepareStatement(
                                    "insert into agent_display (agent_rollup_id, display) "
                                            + "values (?, ?) on conflict (agent_rollup_id) do update "
                                            + "set display = excluded.display")) {
                        ps.setString(1, agentRollupId);
                        ps.setString(2, display);
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
