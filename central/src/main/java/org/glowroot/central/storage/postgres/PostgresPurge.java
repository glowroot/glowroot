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
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

/**
 * Boot-time delete of rows past {@code expires_at}. Not a scheduled sweeper (Cassandra TTL
 * equivalent for long-running labs); call after schema upgrade.
 */
public final class PostgresPurge {

    private static final String[] TABLES = {
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
            "gauge_value",
            "synthetic_result",
            "full_query_text",
            "full_query_text_check",
    };

    private PostgresPurge() {}

    public static void deleteExpired(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            for (String table : TABLES) {
                statement.executeUpdate("delete from " + table + " where expires_at < now()");
            }
        }
    }
}
