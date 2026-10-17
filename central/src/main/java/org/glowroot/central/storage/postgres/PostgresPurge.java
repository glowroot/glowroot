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
import java.sql.Statement;

import javax.sql.DataSource;

/**
 * Delete rows past {@code expires_at} (Cassandra TTL stand-in). Prefer
 * {@link #deleteExpiredBatched} for boot and scheduled sweeps so large tables do not hold one
 * long lock.
 */
public final class PostgresPurge {

    static final String[] TABLES = {
            "heartbeat",
            "transaction_type",
            "trace_attribute_name",
            "resolved_incident",
            "aggregate_overall",
            "aggregate_transaction",
            "aggregate_needs_rollup",
            "aggregate_needs_rollup_from_child",
            "active_top_level_rollup",
            "active_child_rollup",
            "trace",
            "trace_point",
            "gauge_value",
            "synthetic_result",
            "full_query_text",
            "full_query_text_check",
    };

    /** Default batch size balances lock duration vs round-trips. */
    static final int DEFAULT_BATCH_SIZE = 5_000;

    private PostgresPurge() {}

    /** Convenience: batched delete with {@link #DEFAULT_BATCH_SIZE}. */
    public static void deleteExpired(DataSource dataSource) throws SQLException {
        deleteExpiredBatched(dataSource, DEFAULT_BATCH_SIZE);
    }

    /**
     * Deletes expired rows in batches of {@code batchSize} per table (via {@code ctid}) until each
     * table is clean. Returns total rows deleted.
     */
    public static int deleteExpiredBatched(DataSource dataSource, int batchSize)
            throws SQLException {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        int total = 0;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            for (String table : TABLES) {
                total += deleteTableBatched(connection, table, batchSize);
            }
        }
        return total;
    }

    private static int deleteTableBatched(Connection connection, String table, int batchSize)
            throws SQLException {
        // Identifier whitelist only — TABLES is static.
        String sql = "with doomed as ("
                + "select ctid from " + table + " where expires_at < now() limit ?"
                + ") delete from " + table + " where ctid in (select ctid from doomed)";
        int deleted = 0;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            while (true) {
                ps.setInt(1, batchSize);
                int n = ps.executeUpdate();
                deleted += n;
                if (n < batchSize) {
                    break;
                }
            }
        }
        return deleted;
    }

    /** One-shot unbatched delete (tests / tiny DBs only). */
    static int deleteExpiredUnbatched(DataSource dataSource) throws SQLException {
        int total = 0;
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            for (String table : TABLES) {
                total += statement.executeUpdate(
                        "delete from " + table + " where expires_at < now()");
            }
        }
        return total;
    }
}
