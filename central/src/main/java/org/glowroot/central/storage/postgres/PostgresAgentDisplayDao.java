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
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import javax.sql.DataSource;

import com.google.common.base.Joiner;
import com.spotify.futures.CompletableFutures;
import org.glowroot.central.repo.AgentRollupIds;
import org.glowroot.common2.config.MoreConfigDefaults;
import org.glowroot.common2.repo.AgentDisplayRepository;

/**
 * Minimal Postgres port of Cassandra {@code AgentDisplayDao}.
 */
public class PostgresAgentDisplayDao implements AgentDisplayRepository {

    private final DataSource dataSource;
    private final Executor asyncExecutor;

    public PostgresAgentDisplayDao(DataSource dataSource, Executor asyncExecutor) {
        this.dataSource = dataSource;
        this.asyncExecutor = asyncExecutor;
    }

    @Override
    public CompletionStage<String> readFullDisplay(String agentRollupId) {
        return readDisplayParts(agentRollupId).thenApply(parts -> Joiner.on(" :: ").join(parts));
    }

    @Override
    public CompletableFuture<List<String>> readDisplayParts(String agentRollupId) {
        List<String> agentRollupIds = AgentRollupIds.getAgentRollupIds(agentRollupId);
        List<CompletionStage<String>> futures = new ArrayList<>();
        for (ListIterator<String> i = agentRollupIds.listIterator(agentRollupIds.size()); i
                .hasPrevious();) {
            futures.add(readLastDisplayPartAsync(i.previous()));
        }
        return CompletableFutures.allAsList(futures);
    }

    @Override
    public CompletableFuture<String> readLastDisplayPartAsync(String agentRollupId) {
        return CompletableFuture.supplyAsync(() -> readLastDisplayPart(agentRollupId),
                asyncExecutor);
    }

    /** Sync read for callers already on the JDBC async executor (avoids pool deadlock). */
    public String readLastDisplayPart(String agentRollupId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select display from agent_display where agent_rollup_id = ?")) {
            ps.setString(1, agentRollupId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return MoreConfigDefaults.getDefaultAgentRollupDisplayPart(agentRollupId);
                }
                String display = rs.getString(1);
                if (display == null || display.isEmpty()) {
                    return MoreConfigDefaults.getDefaultAgentRollupDisplayPart(agentRollupId);
                }
                return display;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
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
