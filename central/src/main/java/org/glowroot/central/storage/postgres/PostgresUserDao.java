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

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import javax.sql.DataSource;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.glowroot.common2.config.ImmutableUserConfig;
import org.glowroot.common2.config.UserConfig;
import org.glowroot.common2.repo.ConfigRepository.DuplicateUsernameException;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Postgres port of Cassandra {@code UserDao} (no ClusterManager cache yet).
 */
public class PostgresUserDao {

    private final DataSource dataSource;
    private final Executor asyncExecutor;

    public PostgresUserDao(DataSource dataSource, Executor asyncExecutor) {
        this.dataSource = dataSource;
        this.asyncExecutor = asyncExecutor;
    }

    public CompletionStage<List<UserConfig>> read() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return readSync();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }

    public CompletionStage<@Nullable UserConfig> read(String username) {
        return read().thenApply(users -> {
            for (UserConfig userConfig : users) {
                if (userConfig.username().equals(username)) {
                    return userConfig;
                }
            }
            return null;
        });
    }

    public CompletionStage<@Nullable UserConfig> readCaseInsensitive(String username) {
        return read().thenApply(users -> {
            for (UserConfig userConfig : users) {
                if (userConfig.username().equalsIgnoreCase(username)) {
                    return userConfig;
                }
            }
            return null;
        });
    }

    public CompletionStage<Boolean> namedUsersExist() {
        return read().thenApply(users -> {
            for (UserConfig userConfig : users) {
                if (!userConfig.username().equalsIgnoreCase("anonymous")) {
                    return true;
                }
            }
            return false;
        });
    }

    public CompletionStage<Void> insert(UserConfig userConfig) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try {
                upsertSync(userConfig);
                future.complete(null);
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        }, asyncExecutor);
        return future;
    }

    public CompletionStage<?> insertIfNotExists(UserConfig userConfig) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try {
                insertIfNotExistsSync(userConfig);
                future.complete(null);
            } catch (DuplicateUsernameException e) {
                future.completeExceptionally(e);
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        }, asyncExecutor);
        return future;
    }

    public CompletionStage<Void> delete(String username) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(
                            "delete from \"user\" where username = ?")) {
                ps.setString(1, username);
                ps.executeUpdate();
                future.complete(null);
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        }, asyncExecutor);
        return future;
    }

    private List<UserConfig> readSync() throws SQLException {
        List<UserConfig> users = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "select username, ldap, password_hash, roles from \"user\"");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                users.add(buildUser(rs));
            }
        }
        return users;
    }

    private void upsertSync(UserConfig userConfig) throws SQLException {
        String sql = "insert into \"user\" (username, ldap, password_hash, roles) values (?, ?, ?, ?) "
                + "on conflict (username) do update set ldap = excluded.ldap, "
                + "password_hash = excluded.password_hash, roles = excluded.roles";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            bindUser(ps, connection, userConfig);
            ps.executeUpdate();
        }
    }

    private void insertIfNotExistsSync(UserConfig userConfig)
            throws SQLException, DuplicateUsernameException {
        String sql = "insert into \"user\" (username, ldap, password_hash, roles) values (?, ?, ?, ?) "
                + "on conflict (username) do nothing";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            bindUser(ps, connection, userConfig);
            if (ps.executeUpdate() == 0) {
                throw new DuplicateUsernameException();
            }
        }
    }

    private static void bindUser(PreparedStatement ps, Connection connection, UserConfig userConfig)
            throws SQLException {
        ps.setString(1, userConfig.username());
        ps.setBoolean(2, userConfig.ldap());
        ps.setString(3, userConfig.passwordHash());
        ps.setArray(4, connection.createArrayOf("text", userConfig.roles().toArray(new String[0])));
    }

    private static UserConfig buildUser(ResultSet rs) throws SQLException {
        return ImmutableUserConfig.builder()
                .username(checkNotNull(rs.getString("username")))
                .ldap(rs.getBoolean("ldap"))
                .passwordHash(checkNotNull(rs.getString("password_hash")))
                .roles(toStringSet(rs.getArray("roles")))
                .build();
    }

    static Set<String> toStringSet(/*@Nullable*/ Array array) throws SQLException {
        Set<String> set = new LinkedHashSet<>();
        if (array == null) {
            return set;
        }
        Object raw = array.getArray();
        if (raw instanceof String[]) {
            set.addAll(Arrays.asList((String[]) raw));
        } else if (raw instanceof Object[]) {
            for (Object o : (Object[]) raw) {
                if (o != null) {
                    set.add(o.toString());
                }
            }
        }
        return set;
    }
}
