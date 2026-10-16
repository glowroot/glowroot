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
 * </ul>
 */
public final class PostgresSchemaUpgrade {

    static final int CURRENT_VERSION = 4;

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
        try (PreparedStatement ps = connection.prepareStatement(
                "select version from schema_version where singleton = 1");
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
