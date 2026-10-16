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
 * Minimal Postgres schema migrator for the storage spike (central_config only).
 */
public final class PostgresSchemaUpgrade {

    static final int CURRENT_VERSION = 1;

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
