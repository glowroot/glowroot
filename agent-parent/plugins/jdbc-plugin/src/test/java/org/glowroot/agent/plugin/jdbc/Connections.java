/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.agent.plugin.jdbc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import org.apache.commons.dbcp.BasicDataSource;
import org.h2.jdbc.JdbcConnection;
import org.hsqldb.jdbc.JDBCDriver;

public class Connections {

    private static final ConnectionType connectionType;

    static {
        String jdbcConnectionType = System.getProperty("glowroot.test.jdbcConnectionType");
        if (jdbcConnectionType == null) {
            connectionType = ConnectionType.H2;
        } else {
            connectionType = ConnectionType.valueOf(jdbcConnectionType);
        }
    }

    enum ConnectionType {
        HSQLDB, H2, COMMONS_DBCP_WRAPPED, TOMCAT_JDBC_POOL_WRAPPED, POSTGRES, ORACLE, SQLSERVER
    }

    static Connection createConnection() throws Exception {
        switch (connectionType) {
            case HSQLDB:
                return createHsqldbConnection();
            case H2:
                return createH2Connection();
            case COMMONS_DBCP_WRAPPED:
                return createCommonsDbcpWrappedConnection();
            case TOMCAT_JDBC_POOL_WRAPPED:
                return createTomcatJdbcPoolWrappedConnection();
            case POSTGRES:
                return createPostgresConnection();
            case ORACLE:
                return createOracleConnection();
            case SQLSERVER:
                return createSqlServerConnection();
            default:
                throw new IllegalStateException("Unexpected connection type: " + connectionType);
        }
    }

    static void closeConnection(Connection connection) throws SQLException {
        Statement statement = connection.createStatement();
        try {
            statement.execute("drop table employee");
        } finally {
            statement.close();
        }
        connection.close();
    }

    private static Connection createHsqldbConnection() throws SQLException {
        // set up database
        Connection connection = JDBCDriver.getConnection("jdbc:hsqldb:mem:test", null);
        insertRecords(connection);
        return connection;
    }

    private static Connection createH2Connection() throws SQLException {
        // set up database
        Connection connection =
                new JdbcConnection("jdbc:h2:mem:;db_close_on_exit=false", new Properties());
        insertRecords(connection);
        return connection;
    }

    private static Connection createCommonsDbcpWrappedConnection() throws SQLException {
        // set up database
        BasicDataSource ds = new BasicDataSource();
        ds.setDriverClassName("org.hsqldb.jdbc.JDBCDriver");
        ds.setUrl("jdbc:hsqldb:mem:test");
        Connection connection = ds.getConnection();
        insertRecords(connection);
        return connection;
    }

    private static Connection createTomcatJdbcPoolWrappedConnection() throws SQLException {
        // set up database
        org.apache.tomcat.jdbc.pool.DataSource ds = new org.apache.tomcat.jdbc.pool.DataSource();
        ds.setDriverClassName("org.hsqldb.jdbc.JDBCDriver");
        ds.setUrl("jdbc:hsqldb:mem:test");
        ds.setJdbcInterceptors(
                "org.apache.tomcat.jdbc.pool.interceptor.StatementDecoratorInterceptor");
        Connection connection = ds.getConnection();
        insertRecords(connection);
        return connection;
    }

    private static Connection createPostgresConnection() throws SQLException {
        // set up database
        Connection connection = DriverManager.getConnection("jdbc:postgresql://localhost/glowroot",
                "glowroot", "glowroot");
        insertRecords(connection, "bytea", "text");
        return connection;
    }

    // need to add the oracle driver to the path in order to use this, e.g. install into local repo:
    //
    // mvn install:install-file -Dfile=ojdbc6.jar -DgroupId=com.oracle -DartifactId=ojdbc6
    // -Dversion=11.2.0.3 -Dpackaging=jar -DgeneratePom=true
    //
    // then add to pom.xml
    //
    // <dependency>
    // <groupId>com.oracle</groupId>
    // <artifactId>ojdbc6</artifactId>
    // <version>11.2.0.3</version>
    // <scope>test</scope>
    // </dependency>
    private static Connection createOracleConnection() throws SQLException {
        // set up database
        Connection connection = DriverManager.getConnection("jdbc:oracle:thin:@//localhost/orcl",
                "glowroot", "glowroot");
        insertRecords(connection);
        return connection;
    }

    // need to add the sqlserver driver to the path in order to use this, e.g. install into local
    // repo:
    //
    // mvn install:install-file -Dfile=sqljdbc.jar -DgroupId=com.microsoft -DartifactId=sqljdbc
    // -Dversion=4.0 -Dpackaging=jar -DgeneratePom=true
    //
    // then add to pom.xml
    //
    // <dependency>
    // <groupId>com.microsoft</groupId>
    // <artifactId>sqljdbc</artifactId>
    // <version>4.0</version>
    // <scope>test</scope>
    // </dependency>
    private static Connection createSqlServerConnection() throws Exception {
        // set up database
        Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
        Connection connection =
                DriverManager.getConnection("jdbc:sqlserver://localhost", "sa", "password");
        insertRecords(connection);
        return connection;
    }

    private static void insertRecords(Connection connection) throws SQLException {
        insertRecords(connection, "blob", "clob");
    }

    private static void insertRecords(Connection connection, String binaryTypeName,
            String clobTypeName) throws SQLException {
        Statement statement = connection.createStatement();
        try {
            try {
                // in case of previous failure mid-test
                statement.execute("drop table employee");
            } catch (SQLException e) {
            }
            statement.execute("create table employee (name varchar(100), misc " + binaryTypeName
                    + ", misc2 " + clobTypeName + ")");
            statement.execute("insert into employee (name) values ('john doe')");
            statement.execute("insert into employee (name) values ('jane doe')");
            statement.execute("insert into employee (name) values ('sally doe')");
        } finally {
            statement.close();
        }
    }
}
