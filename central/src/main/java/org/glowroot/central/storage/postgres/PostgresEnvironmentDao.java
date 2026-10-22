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
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import javax.sql.DataSource;

import com.google.protobuf.InvalidProtocolBufferException;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.glowroot.central.repo.EnvironmentStore;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.InitMessage.Environment;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Postgres port of Cassandra {@code EnvironmentDao}.
 */
public class PostgresEnvironmentDao implements EnvironmentStore {

    private final DataSource dataSource;
    private final Executor asyncExecutor;

    public PostgresEnvironmentDao(DataSource dataSource, Executor asyncExecutor) {
        this.dataSource = dataSource;
        this.asyncExecutor = asyncExecutor;
    }

    public CompletionStage<?> store(String agentId, Environment environment) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(
                            "insert into environment (agent_id, environment) values (?, ?) "
                                    + "on conflict (agent_id) do update set "
                                    + "environment = excluded.environment")) {
                ps.setString(1, agentId);
                ps.setBytes(2, environment.toByteArray());
                ps.executeUpdate();
                future.complete(null);
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        }, asyncExecutor);
        return future;
    }

    @Override
    public CompletionStage<Environment> read(String agentId, CassandraProfile profile) {
        return read(agentId);
    }

    public CompletionStage<@Nullable Environment> read(String agentId) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(
                            "select environment from environment where agent_id = ?")) {
                ps.setString(1, agentId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    try {
                        return Environment.parseFrom(checkNotNull(rs.getBytes(1)));
                    } catch (InvalidProtocolBufferException e) {
                        throw new RuntimeException(e);
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }
}
