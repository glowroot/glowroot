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

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.Versions;
import org.glowroot.common2.repo.ConfigRepository.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Postgres DAO for {@code central_config}.
 */
public class PostgresCentralConfigDao {

    private static final Logger logger = LoggerFactory.getLogger(PostgresCentralConfigDao.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private final DataSource dataSource;
    private final Map<String, Class<?>> keyTypes = new ConcurrentHashMap<>();

    public PostgresCentralConfigDao(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void addKeyType(String key, Class<?> clazz) {
        keyTypes.put(key, clazz);
    }

    public CompletionStage<?> write(String key, Object config, String priorVersion) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try {
                writeOptimisticSync(key, config, priorVersion);
                future.complete(null);
            } catch (OptimisticLockException e) {
                future.completeExceptionally(e);
            } catch (SQLException | IOException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public CompletionStage<?> writeWithoutOptimisticLocking(String key, Object config) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try {
                writeWithoutOptimisticLockingSync(key, config);
                future.complete(null);
            } catch (SQLException | JsonProcessingException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public CompletableFuture<@Nullable Object> read(String key) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return readSync(key);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** @deprecated use {@link #writeWithoutOptimisticLocking(String, Object)} */
    public void writeWithoutOptimisticLockingSync(String key, Object config)
            throws SQLException, JsonProcessingException {
        String json = mapper.writeValueAsString(config);
        if ("{}".equals(json)) {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement delete = connection.prepareStatement(
                            "delete from central_config where key = ?")) {
                delete.setString(1, key);
                delete.executeUpdate();
            }
            return;
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement upsert = connection.prepareStatement(
                        "insert into central_config (key, value) values (?, ?) "
                                + "on conflict (key) do update set value = excluded.value")) {
            upsert.setString(1, key);
            upsert.setString(2, json);
            upsert.executeUpdate();
        }
    }

    private void writeOptimisticSync(String key, Object config, String priorVersion)
            throws SQLException, IOException, OptimisticLockException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String currValue = null;
                try (PreparedStatement read = connection.prepareStatement(
                        "select value from central_config where key = ? for update")) {
                    read.setString(1, key);
                    try (ResultSet rs = read.executeQuery()) {
                        if (rs.next()) {
                            currValue = rs.getString(1);
                        }
                    }
                }
                if (currValue == null) {
                    String initialValue = mapper.writeValueAsString(config);
                    try (PreparedStatement insert = connection.prepareStatement(
                            "insert into central_config (key, value) values (?, ?)")) {
                        insert.setString(1, key);
                        insert.setString(2, initialValue);
                        insert.executeUpdate();
                    }
                    connection.commit();
                    return;
                }
                Object currConfig = readValue(key, currValue);
                if (!Versions.getJsonVersion(currConfig).equals(priorVersion)) {
                    connection.rollback();
                    throw new OptimisticLockException();
                }
                String newValue = mapper.writeValueAsString(config);
                try (PreparedStatement update = connection.prepareStatement(
                        "update central_config set value = ? where key = ? and value = ?")) {
                    update.setString(1, newValue);
                    update.setString(2, key);
                    update.setString(3, currValue);
                    if (update.executeUpdate() != 1) {
                        connection.rollback();
                        throw new OptimisticLockException();
                    }
                }
                connection.commit();
            } catch (OptimisticLockException e) {
                throw e;
            } catch (SQLException | IOException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private @Nullable Object readSync(String key) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement read = connection.prepareStatement(
                        "select value from central_config where key = ?")) {
            read.setString(1, key);
            try (ResultSet rs = read.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String value = checkNotNull(rs.getString(1));
                try {
                    return readValue(key, value);
                } catch (IOException e) {
                    logger.error("error parsing config node '{}': ", key, e);
                    return null;
                }
            }
        }
    }

    private Object readValue(String key, String value) throws IOException {
        Class<?> type = checkNotNull(keyTypes.get(key), "No key type registered for %s", key);
        return checkNotNull(mapper.readValue(value, type));
    }
}
