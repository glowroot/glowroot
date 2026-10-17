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
import java.sql.Statement;

import javax.sql.DataSource;

/**
 * Postgres schema migrator.
 * <ul>
 * <li>v1: central_config</li>
 * <li>v2: user + role (+ seeds)</li>
 * <li>v3: agent_config + agent_display</li>
 * <li>v4: heartbeat, environment, alerting_disabled</li>
 * <li>v5: open/resolved_incident, active_*_rollup, transaction_type, trace_attribute_name</li>
 * <li>v6: v09_agent_rollup</li>
 * <li>v7: aggregate_overall / aggregate_transaction / aggregate_query / aggregate_needs_rollup</li>
 * <li>v8: aggregate_needs_rollup_from_child</li>
 * <li>v9: trace, gauge_value, synthetic_result</li>
 * <li>v10: full_query_text + full_query_text_check</li>
 * </ul>
 */
public final class PostgresSchemaUpgrade {

    static final int CURRENT_VERSION = 10;

    private PostgresSchemaUpgrade() {}

    public static void upgrade(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                ensureSchemaVersionTable(connection);
                int version = readVersion(connection);
                if (version < 1) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("create table if not exists central_config ("
                                + "key varchar(255) primary key, "
                                + "value text not null)");
                    }
                    writeVersion(connection, 1);
                    version = 1;
                }
                if (version < 2) {
                    migrateToV2(connection);
                    writeVersion(connection, 2);
                    version = 2;
                }
                if (version < 3) {
                    migrateToV3(connection);
                    writeVersion(connection, 3);
                    version = 3;
                }
                if (version < 4) {
                    migrateToV4(connection);
                    writeVersion(connection, 4);
                    version = 4;
                }
                if (version < 5) {
                    migrateToV5(connection);
                    writeVersion(connection, 5);
                    version = 5;
                }
                if (version < 6) {
                    migrateToV6(connection);
                    writeVersion(connection, 6);
                    version = 6;
                }
                if (version < 7) {
                    migrateToV7(connection);
                    writeVersion(connection, 7);
                    version = 7;
                }
                if (version < 8) {
                    migrateToV8(connection);
                    writeVersion(connection, 8);
                    version = 8;
                }
                if (version < 9) {
                    migrateToV9(connection);
                    writeVersion(connection, 9);
                    version = 9;
                }
                if (version < 10) {
                    migrateToV10(connection);
                    writeVersion(connection, 10);
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private static void migrateToV2(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // "user" is a reserved word in PostgreSQL
            statement.execute("create table if not exists \"user\" ("
                    + "username varchar(255) primary key, "
                    + "ldap boolean not null, "
                    + "password_hash varchar(512) not null, "
                    + "roles text[] not null)");
            statement.execute("create table if not exists role ("
                    + "name varchar(255) primary key, "
                    + "permissions text[] not null)");
        }
        try (PreparedStatement insertUser = connection.prepareStatement(
                "insert into \"user\" (username, ldap, password_hash, roles) values (?, ?, ?, ?) "
                        + "on conflict (username) do nothing");
                PreparedStatement insertRole = connection.prepareStatement(
                        "insert into role (name, permissions) values (?, ?) "
                                + "on conflict (name) do nothing")) {
            insertUser.setString(1, "anonymous");
            insertUser.setBoolean(2, false);
            insertUser.setString(3, "");
            insertUser.setArray(4, connection.createArrayOf("text", new String[] {"Administrator"}));
            insertUser.executeUpdate();

            insertRole.setString(1, "Administrator");
            insertRole.setArray(2, connection.createArrayOf("text", new String[] {
                    "agent:*:transaction", "agent:*:error", "agent:*:jvm",
                    "agent:*:syntheticMonitor", "agent:*:incident", "agent:*:config", "admin"}));
            insertRole.executeUpdate();
        }
    }

    private static void migrateToV3(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("create table if not exists agent_config ("
                    + "agent_rollup_id varchar(512) primary key, "
                    + "config bytea not null, "
                    + "config_update boolean not null default false, "
                    + "config_update_token uuid)");
            statement.execute("create table if not exists agent_display ("
                    + "agent_rollup_id varchar(512) primary key, "
                    + "display varchar(1024) not null)");
        }
    }

    private static void migrateToV4(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("create table if not exists heartbeat ("
                    + "agent_id varchar(512) not null, "
                    + "central_capture_time timestamptz not null, "
                    + "expires_at timestamptz not null, "
                    + "primary key (agent_id, central_capture_time))");
            statement.execute("create index if not exists heartbeat_range_idx "
                    + "on heartbeat (agent_id, central_capture_time)");
            statement.execute("create table if not exists environment ("
                    + "agent_id varchar(512) primary key, "
                    + "environment bytea not null)");
            statement.execute("create table if not exists alerting_disabled ("
                    + "agent_rollup_id varchar(512) primary key, "
                    + "disabled_until_time timestamptz not null)");
        }
    }

    private static void migrateToV5(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("create table if not exists open_incident ("
                    + "agent_rollup_id varchar(512) not null, "
                    + "condition bytea not null, "
                    + "severity varchar(32) not null, "
                    + "notification bytea not null, "
                    + "open_time timestamptz not null, "
                    + "primary key (agent_rollup_id, condition, severity))");
            statement.execute("create table if not exists resolved_incident ("
                    + "resolve_time timestamptz not null, "
                    + "agent_rollup_id varchar(512) not null, "
                    + "condition bytea not null, "
                    + "severity varchar(32) not null, "
                    + "notification bytea not null, "
                    + "open_time timestamptz not null, "
                    + "expires_at timestamptz not null, "
                    + "primary key (resolve_time, agent_rollup_id, condition))");
            statement.execute("create index if not exists resolved_incident_from_idx "
                    + "on resolved_incident (resolve_time)");
            statement.execute("create table if not exists active_top_level_rollup ("
                    + "rollup_level int not null, "
                    + "capture_time timestamptz not null, "
                    + "top_level_id varchar(512) not null, "
                    + "expires_at timestamptz not null, "
                    + "primary key (rollup_level, capture_time, top_level_id))");
            statement.execute("create index if not exists active_top_level_range_idx "
                    + "on active_top_level_rollup (rollup_level, capture_time)");
            statement.execute("create table if not exists active_child_rollup ("
                    + "rollup_level int not null, "
                    + "top_level_id varchar(512) not null, "
                    + "capture_time timestamptz not null, "
                    + "child_agent_id varchar(512) not null, "
                    + "expires_at timestamptz not null, "
                    + "primary key (rollup_level, top_level_id, capture_time, child_agent_id))");
            statement.execute("create index if not exists active_child_range_idx "
                    + "on active_child_rollup (rollup_level, top_level_id, capture_time)");
            statement.execute("create table if not exists transaction_type ("
                    + "agent_rollup varchar(512) not null, "
                    + "transaction_type varchar(512) not null, "
                    + "expires_at timestamptz not null, "
                    + "primary key (agent_rollup, transaction_type))");
            statement.execute("create table if not exists trace_attribute_name ("
                    + "agent_rollup varchar(512) not null, "
                    + "transaction_type varchar(512) not null, "
                    + "trace_attribute_name varchar(512) not null, "
                    + "expires_at timestamptz not null, "
                    + "primary key (agent_rollup, transaction_type, trace_attribute_name))");
        }
    }

    private static void migrateToV6(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("create table if not exists v09_agent_rollup ("
                    + "v09_agent_id varchar(512) primary key, "
                    + "v09_agent_rollup_id varchar(512) not null)");
        }
    }

    private static void migrateToV7(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("create table if not exists aggregate_overall ("
                    + "agent_rollup_id varchar(512) not null, "
                    + "rollup_level int not null, "
                    + "transaction_type varchar(512) not null, "
                    + "capture_time timestamptz not null, "
                    + "total_duration_nanos double precision not null, "
                    + "total_cpu_nanos double precision not null, "
                    + "total_allocated_bytes double precision not null, "
                    + "transaction_count bigint not null, "
                    + "error_count bigint not null, "
                    + "aggregate_bytes bytea not null, "
                    + "shared_query_texts_bytes bytea, "
                    + "expires_at timestamptz not null, "
                    + "primary key (agent_rollup_id, rollup_level, transaction_type, capture_time))");
            statement.execute("create index if not exists aggregate_overall_range_idx "
                    + "on aggregate_overall (agent_rollup_id, rollup_level, transaction_type, "
                    + "capture_time)");
            statement.execute("create table if not exists aggregate_transaction ("
                    + "agent_rollup_id varchar(512) not null, "
                    + "rollup_level int not null, "
                    + "transaction_type varchar(512) not null, "
                    + "transaction_name varchar(1024) not null, "
                    + "capture_time timestamptz not null, "
                    + "total_duration_nanos double precision not null, "
                    + "total_cpu_nanos double precision not null, "
                    + "total_allocated_bytes double precision not null, "
                    + "transaction_count bigint not null, "
                    + "error_count bigint not null, "
                    + "aggregate_bytes bytea not null, "
                    + "shared_query_texts_bytes bytea, "
                    + "expires_at timestamptz not null, "
                    + "primary key (agent_rollup_id, rollup_level, transaction_type, "
                    + "transaction_name, capture_time))");
            statement.execute("create index if not exists aggregate_transaction_range_idx "
                    + "on aggregate_transaction (agent_rollup_id, rollup_level, transaction_type, "
                    + "capture_time)");
            statement.execute("create table if not exists aggregate_needs_rollup ("
                    + "agent_rollup varchar(512) not null, "
                    + "rollup_level int not null, "
                    + "capture_time timestamptz not null, "
                    + "uniqueness uuid not null, "
                    + "transaction_types text[] not null, "
                    + "expires_at timestamptz not null, "
                    + "primary key (agent_rollup, rollup_level, capture_time, uniqueness))");
        }
    }

    private static void migrateToV8(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("create table if not exists aggregate_needs_rollup_from_child ("
                    + "agent_rollup varchar(512) not null, "
                    + "capture_time timestamptz not null, "
                    + "uniqueness uuid not null, "
                    + "child_agent_rollup varchar(512) not null, "
                    + "transaction_types text[] not null, "
                    + "expires_at timestamptz not null, "
                    + "primary key (agent_rollup, capture_time, uniqueness))");
        }
    }

    private static void migrateToV9(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("create table if not exists trace ("
                    + "agent_id varchar(512) not null, "
                    + "trace_id varchar(64) not null, "
                    + "capture_time timestamptz not null, "
                    + "duration_nanos bigint not null, "
                    + "slow boolean not null, "
                    + "error boolean not null, "
                    + "partial boolean not null, "
                    + "transaction_type varchar(512) not null, "
                    + "transaction_name varchar(1024) not null, "
                    + "headline text not null, "
                    + "user_col text not null, "
                    + "error_message text, "
                    + "header_bytes bytea not null, "
                    + "entries_bytes bytea, "
                    + "queries_bytes bytea, "
                    + "shared_query_texts_bytes bytea, "
                    + "main_profile_bytes bytea, "
                    + "aux_profile_bytes bytea, "
                    + "expires_at timestamptz not null, "
                    + "primary key (agent_id, trace_id))");
            statement.execute("create index if not exists trace_slow_idx "
                    + "on trace (agent_id, transaction_type, capture_time) where slow");
            statement.execute("create index if not exists trace_error_idx "
                    + "on trace (agent_id, transaction_type, capture_time) where error");
            statement.execute("create table if not exists gauge_value ("
                    + "agent_rollup varchar(512) not null, "
                    + "gauge_name varchar(1024) not null, "
                    + "capture_time timestamptz not null, "
                    + "value double precision not null, "
                    + "weight bigint not null, "
                    + "rollup_level int not null, "
                    + "expires_at timestamptz not null, "
                    + "primary key (agent_rollup, gauge_name, capture_time, rollup_level))");
            statement.execute("create index if not exists gauge_value_range_idx "
                    + "on gauge_value (agent_rollup, gauge_name, rollup_level, capture_time)");
            statement.execute("create table if not exists synthetic_result ("
                    + "agent_rollup varchar(512) not null, "
                    + "synthetic_monitor_id varchar(512) not null, "
                    + "capture_time timestamptz not null, "
                    + "total_duration_nanos double precision not null, "
                    + "execution_count bigint not null, "
                    + "error_count bigint not null, "
                    + "rollup_level int not null, "
                    + "display text not null, "
                    + "expires_at timestamptz not null, "
                    + "primary key (agent_rollup, synthetic_monitor_id, capture_time, "
                    + "rollup_level))");
            statement.execute("create index if not exists synthetic_result_range_idx "
                    + "on synthetic_result (agent_rollup, synthetic_monitor_id, rollup_level, "
                    + "capture_time)");
        }
    }

    private static void migrateToV10(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("create table if not exists full_query_text ("
                    + "full_query_text_sha1 varchar(64) not null primary key, "
                    + "full_query_text text not null, "
                    + "expires_at timestamptz not null)");
            statement.execute("create table if not exists full_query_text_check ("
                    + "agent_rollup varchar(512) not null, "
                    + "full_query_text_sha1 varchar(64) not null, "
                    + "expires_at timestamptz not null, "
                    + "primary key (agent_rollup, full_query_text_sha1))");
        }
    }

    private static void ensureSchemaVersionTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("create table if not exists schema_version ("
                    + "singleton int primary key default 1 check (singleton = 1), "
                    + "version int not null)");
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "insert into schema_version (singleton, version) values (1, 0) "
                        + "on conflict (singleton) do nothing")) {
            ps.executeUpdate();
        }
    }

    private static int readVersion(Connection connection) throws SQLException {
        // Row lock for the whole upgrade txn — concurrent Centrals cannot migrate in parallel.
        try (PreparedStatement ps = connection.prepareStatement(
                "select version from schema_version where singleton = 1 for update");
                ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return 0;
            }
            return rs.getInt(1);
        }
    }

    private static void writeVersion(Connection connection, int version) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "update schema_version set version = ? where singleton = 1")) {
            ps.setInt(1, version);
            ps.executeUpdate();
        }
    }
}
