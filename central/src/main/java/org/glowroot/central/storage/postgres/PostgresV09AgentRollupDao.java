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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import javax.sql.DataSource;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.glowroot.central.repo.V09AgentRollupStore;

/**
 * Postgres port of Cassandra {@code V09AgentRollupDao}. Supports legacy v0.9 agent rollup id
 * mapping when old agents still connect. Not a no-op stub — full read/write.
 */
public class PostgresV09AgentRollupDao implements V09AgentRollupStore {

    private final DataSource dataSource;
    private final Executor asyncExecutor;
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
    private volatile boolean cacheLoaded;

    public PostgresV09AgentRollupDao(DataSource dataSource, Executor asyncExecutor) {
        this.dataSource = dataSource;
        this.asyncExecutor = asyncExecutor;
    }

    public void store(String v09AgentId, String v09AgentRollupId) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "insert into v09_agent_rollup (v09_agent_id, v09_agent_rollup_id) "
                                + "values (?, ?) on conflict (v09_agent_id) do update set "
                                + "v09_agent_rollup_id = excluded.v09_agent_rollup_id")) {
            ps.setString(1, v09AgentId);
            ps.setString(2, v09AgentRollupId);
            ps.executeUpdate();
        }
        cache.put(v09AgentId, v09AgentRollupId);
    }

    public @Nullable String getV09AgentRollupId(String v09AgentId) throws Exception {
        ensureCacheLoaded();
        return cache.get(v09AgentId);
    }

    public CompletableFuture<Void> storeAsync(String v09AgentId, String v09AgentRollupId) {
        return CompletableFuture.runAsync(() -> {
            try {
                store(v09AgentId, v09AgentRollupId);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }

    private synchronized void ensureCacheLoaded() throws SQLException {
        if (cacheLoaded) {
            return;
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select v09_agent_id, v09_agent_rollup_id from v09_agent_rollup");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                cache.put(rs.getString(1), rs.getString(2));
            }
        }
        cacheLoaded = true;
    }
}
