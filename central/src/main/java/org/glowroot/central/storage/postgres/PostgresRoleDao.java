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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import javax.sql.DataSource;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.glowroot.common2.config.ImmutableRoleConfig;
import org.glowroot.common2.config.RoleConfig;
import org.glowroot.common2.repo.ConfigRepository.DuplicateRoleNameException;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.glowroot.central.storage.postgres.PostgresUserDao.toStringSet;

/**
 * Postgres port of Cassandra {@code RoleDao} (no ClusterManager cache yet).
 */
public class PostgresRoleDao {

    private final DataSource dataSource;
    private final Executor asyncExecutor;

    public PostgresRoleDao(DataSource dataSource, Executor asyncExecutor) {
        this.dataSource = dataSource;
        this.asyncExecutor = asyncExecutor;
    }

    public CompletionStage<List<RoleConfig>> read() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return readSync();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }

    public CompletionStage<@Nullable RoleConfig> read(String name) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(
                            "select name, permissions from role where name = ?")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    RoleConfig role = buildRole(rs);
                    if (rs.next()) {
                        throw new IllegalStateException("Multiple role records for name: " + name);
                    }
                    return role;
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }

    public CompletionStage<?> insert(RoleConfig roleConfig) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try {
                upsertSync(roleConfig);
                future.complete(null);
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        }, asyncExecutor);
        return future;
    }

    public CompletionStage<?> insertIfNotExists(RoleConfig roleConfig) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try {
                insertIfNotExistsSync(roleConfig);
                future.complete(null);
            } catch (DuplicateRoleNameException e) {
                future.completeExceptionally(e);
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        }, asyncExecutor);
        return future;
    }

    public CompletionStage<?> delete(String name) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(
                            "delete from role where name = ?")) {
                ps.setString(1, name);
                ps.executeUpdate();
                future.complete(null);
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        }, asyncExecutor);
        return future;
    }

    private List<RoleConfig> readSync() throws SQLException {
        List<RoleConfig> roles = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select name, permissions from role");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                roles.add(buildRole(rs));
            }
        }
        return roles;
    }

    private void upsertSync(RoleConfig roleConfig) throws SQLException {
        String sql = "insert into role (name, permissions) values (?, ?) "
                + "on conflict (name) do update set permissions = excluded.permissions";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            bindRole(ps, connection, roleConfig);
            ps.executeUpdate();
        }
    }

    private void insertIfNotExistsSync(RoleConfig roleConfig)
            throws SQLException, DuplicateRoleNameException {
        String sql = "insert into role (name, permissions) values (?, ?) "
                + "on conflict (name) do nothing";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            bindRole(ps, connection, roleConfig);
            if (ps.executeUpdate() == 0) {
                throw new DuplicateRoleNameException();
            }
        }
    }

    private static void bindRole(PreparedStatement ps, Connection connection, RoleConfig roleConfig)
            throws SQLException {
        ps.setString(1, roleConfig.name());
        ps.setArray(2, connection.createArrayOf("text",
                roleConfig.permissions().toArray(new String[0])));
    }

    private static RoleConfig buildRole(ResultSet rs) throws SQLException {
        return ImmutableRoleConfig.builder()
                .central(true)
                .name(checkNotNull(rs.getString("name")))
                .permissions(toStringSet(rs.getArray("permissions")))
                .build();
    }
}
