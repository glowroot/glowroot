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

import javax.annotation.Nullable;
import javax.sql.DataSource;

import com.google.common.collect.Iterables;
import com.google.common.primitives.Ints;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.common2.repo.ConfigRepository;
import org.glowroot.common2.repo.ConfigRepository.RollupConfig;

import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Postgres port of Cassandra {@code FullQueryTextDao} — sha1 body + per-agent-rollup check rows.
 */
public class PostgresFullQueryTextDao {

    private final DataSource dataSource;
    private final ConfigRepository configRepository;
    private final Clock clock;
    private final Executor asyncExecutor;

    public PostgresFullQueryTextDao(DataSource dataSource, ConfigRepository configRepository,
            Clock clock, Executor asyncExecutor) {
        this.dataSource = dataSource;
        this.configRepository = configRepository;
        this.clock = clock;
        this.asyncExecutor = asyncExecutor;
    }

    CompletionStage</*@Nullable*/ String> getFullText(String agentRollupId, String fullTextSha1,
            CassandraProfile profile) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return getFullTextSync(agentRollupId, fullTextSha1);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }

    CompletionStage<?> store(List<String> agentRollupIds, String fullTextSha1, String fullText) {
        return configRepository.getCentralStorageConfig().thenCompose(storageConfig -> {
            int ttlSeconds = ttlSeconds(storageConfig);
            Timestamp expiresAt = PostgresExpires.fromTtlSeconds(ttlSeconds,
                    clock.currentTimeMillis(), clock);
            return CompletableFuture.runAsync(() -> {
                try {
                    storeSync(agentRollupIds, fullTextSha1, fullText, expiresAt);
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            }, asyncExecutor);
        });
    }

    @Nullable
    String getFullTextSync(String agentRollupId, String fullTextSha1) throws SQLException {
        Timestamp now = new Timestamp(clock.currentTimeMillis());
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement check = connection.prepareStatement(
                    "select 1 from full_query_text_check where agent_rollup = ? "
                            + "and full_query_text_sha1 = ? and expires_at > ?")) {
                check.setString(1, agentRollupId);
                check.setString(2, fullTextSha1);
                check.setTimestamp(3, now);
                try (ResultSet rs = check.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                }
            }
            try (PreparedStatement read = connection.prepareStatement(
                    "select full_query_text from full_query_text "
                            + "where full_query_text_sha1 = ? and expires_at > ?")) {
                read.setString(1, fullTextSha1);
                read.setTimestamp(2, now);
                try (ResultSet rs = read.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    return rs.getString(1);
                }
            }
        }
    }

    void storeSync(List<String> agentRollupIds, String fullTextSha1, String fullText,
            Timestamp expiresAt) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                for (String agentRollupId : agentRollupIds) {
                    try (PreparedStatement insertCheck = connection.prepareStatement(
                            "insert into full_query_text_check (agent_rollup, full_query_text_sha1, "
                                    + "expires_at) values (?, ?, ?) "
                                    + "on conflict (agent_rollup, full_query_text_sha1) do update set "
                                    + "expires_at = greatest(full_query_text_check.expires_at, "
                                    + "excluded.expires_at)")) {
                        insertCheck.setString(1, agentRollupId);
                        insertCheck.setString(2, fullTextSha1);
                        insertCheck.setTimestamp(3, expiresAt);
                        insertCheck.executeUpdate();
                    }
                }
                upsertBody(connection, fullTextSha1, fullText, expiresAt);
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private void upsertBody(Connection connection, String fullTextSha1, String fullText,
            Timestamp expiresAt) throws SQLException {
        // Mirror Cassandra: only overwrite when bumping expires_at by at least 1 day.
        Timestamp existingExpires = null;
        try (PreparedStatement read = connection.prepareStatement(
                "select expires_at from full_query_text where full_query_text_sha1 = ?")) {
            read.setString(1, fullTextSha1);
            try (ResultSet rs = read.executeQuery()) {
                if (rs.next()) {
                    existingExpires = rs.getTimestamp(1);
                }
            }
        }
        if (existingExpires != null) {
            long bumpMillis = expiresAt.getTime() - existingExpires.getTime();
            if (bumpMillis < DAYS.toMillis(1)) {
                return;
            }
        }
        try (PreparedStatement upsert = connection.prepareStatement(
                "insert into full_query_text (full_query_text_sha1, full_query_text, expires_at) "
                        + "values (?, ?, ?) "
                        + "on conflict (full_query_text_sha1) do update set "
                        + "full_query_text = excluded.full_query_text, "
                        + "expires_at = excluded.expires_at")) {
            upsert.setString(1, fullTextSha1);
            upsert.setString(2, fullText);
            upsert.setTimestamp(3, expiresAt);
            upsert.executeUpdate();
        }
    }

    private int ttlSeconds(org.glowroot.common2.config.StorageConfig storageConfig) {
        int queryRollupExpirationHours =
                Iterables.getLast(storageConfig.queryAndServiceCallRollupExpirationHours());
        int expirationHours =
                Math.max(queryRollupExpirationHours, storageConfig.traceExpirationHours());
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        RollupConfig lastRollupConfig = Iterables.getLast(rollupConfigs);
        long ttl = MILLISECONDS.toSeconds(lastRollupConfig.intervalMillis())
                + DAYS.toSeconds(2)
                + HOURS.toSeconds(expirationHours);
        return Ints.saturatedCast(ttl);
    }
}
