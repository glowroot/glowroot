/*
 * Copyright 2015-2019 the original author or authors.
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

import java.lang.reflect.UndeclaredThrowableException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import javax.sql.DataSource;

import bitronix.tm.resource.jdbc.PoolingDataSource;
import com.sun.gjc.spi.CPManagedConnectionFactory;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.commons.dbcp.BasicDataSource;
import org.glassfish.api.jdbc.SQLTraceListener;
import org.glassfish.api.jdbc.SQLTraceRecord;
import org.h2.jdbc.JdbcConnection;
import org.hsqldb.jdbc.JDBCDriver;
import org.hsqldb.jdbc.pool.JDBCXADataSource;

import org.glowroot.agent.weaving.IsolatedWeavingClassLoader;

public class Connections {

    private static final ConnectionType connectionType;

    private static volatile int nextUniqueNum;

    static {
        String jdbcConnectionType = System.getProperty("glowroot.test.jdbcConnectionType");
        if (jdbcConnectionType == null) {
            connectionType = ConnectionType.HSQLDB;
        } else {
            connectionType = ConnectionType.valueOf(jdbcConnectionType);
        }
    }

    enum ConnectionType {
        HSQLDB, H2, COMMONS_DBCP_WRAPPED, COMMONS_DBCP2_WRAPPED, TOMCAT_JDBC_POOL_WRAPPED,
        GLASSFISH_JDBC_POOL_WRAPPED, HIKARI_CP_WRAPPED, BITRONIX_WRAPPED, POSTGRES, ORACLE, MSSQL
    }

    static Connection createConnection() throws Exception {
        switch (connectionType) {
            case HSQLDB:
                return createHsqldbConnection();
            case H2:
                return createH2Connection();
            case COMMONS_DBCP_WRAPPED:
                return createCommonsDbcpWrappedConnection();
            case COMMONS_DBCP2_WRAPPED:
                return initConnection(createCommonsDbcp2DataSource());
            case TOMCAT_JDBC_POOL_WRAPPED:
                return initConnection(createTomcatJdbcPoolWrappedDataSource());
            case GLASSFISH_JDBC_POOL_WRAPPED:
                return initConnection(createGlassfishJdbcPoolWrappedDataSource());
            case HIKARI_CP_WRAPPED:
                return initConnection(createHikariCpDataSource());
            case BITRONIX_WRAPPED:
                return initConnection(createBitronixWrappedDataSource());
            case POSTGRES:
                return createPostgresConnection();
            case ORACLE:
                return createOracleConnection();
            case MSSQL:
                return createMssqlConnection();
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

    static ConnectionType getConnectionType() {
        return connectionType;
    }

    private static Connection createHsqldbConnection() throws SQLException {
        Connection connection = JDBCDriver.getConnection("jdbc:hsqldb:mem:test", null);
        insertRecords(connection);
        return connection;
    }

    private static Connection createH2Connection() throws SQLException {
        Connection connection =
                new JdbcConnection("jdbc:h2:mem:;db_close_on_exit=false", new Properties());
        insertRecords(connection);
        return connection;
    }

    static Connection createCommonsDbcpWrappedConnection() throws Exception {
        DataSource ds = createCommonsDbcpWrappedDataSource();
        return initConnection(ds);
    }

    static BasicDataSource createCommonsDbcpWrappedDataSource() {
        BasicDataSource ds = new BasicDataSource();
        ds.setDriverClassName("org.hsqldb.jdbc.JDBCDriver");
        ds.setUrl("jdbc:hsqldb:mem:test");
        return ds;
    }

    static org.apache.commons.dbcp2.BasicDataSource createCommonsDbcp2DataSource() {
        org.apache.commons.dbcp2.BasicDataSource ds =
                new org.apache.commons.dbcp2.BasicDataSource();
        ds.setDriverClassName("org.hsqldb.jdbc.JDBCDriver");
        ds.setUrl("jdbc:hsqldb:mem:test");
        return ds;
    }

    static org.apache.tomcat.jdbc.pool.DataSource createTomcatJdbcPoolWrappedDataSource() {
        org.apache.tomcat.jdbc.pool.DataSource ds = new org.apache.tomcat.jdbc.pool.DataSource();
        ds.setDriverClassName("org.hsqldb.jdbc.JDBCDriver");
        ds.setUrl("jdbc:hsqldb:mem:test");
        ds.setJdbcInterceptors(
                "org.apache.tomcat.jdbc.pool.interceptor.StatementDecoratorInterceptor");
        return ds;
    }

    static DataSource createGlassfishJdbcPoolWrappedDataSource() {
        CPManagedConnectionFactory connectionFactory = new CPManagedConnectionFactory();
        connectionFactory.setClassName("org.hsqldb.jdbc.pool.JDBCPooledDataSource");
        connectionFactory.setDriverProperties("setUrl=jdbc:hsqldb:mem:test==");
        connectionFactory.setDelimiter("=");
        connectionFactory.setEscapeCharacter("\\");
        connectionFactory.setStatementWrapping("true");
        connectionFactory.setSqlTraceListeners(
                "org.glowroot.agent.plugin.jdbc.Connections$GlassfishSQLTraceListener");
        return (DataSource) connectionFactory.createConnectionFactory();
    }

    static HikariDataSource createHikariCpDataSource() throws AssertionError {
        if (Connections.class.getClassLoader() instanceof IsolatedWeavingClassLoader) {
            try {
                Class.forName("com.zaxxer.hikari.proxy.JavassistProxyFactory");
                throw new AssertionError("Old HikariCP versions define proxies using"
                        + " ClassLoader.defineClass() which is final so IsolatedWeavingClassLoader"
                        + " cannot override and weave them, must use JavaagentContainer");
            } catch (ClassNotFoundException e) {
            }
        }
        HikariConfig config = new HikariConfig();
        config.setDriverClassName("org.hsqldb.jdbc.JDBCDriver");
        config.setJdbcUrl("jdbc:hsqldb:mem:test");
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        return new HikariDataSource(config);
    }

    static PoolingDataSource createBitronixWrappedDataSource() throws AssertionError {
        if (Connections.class.getClassLoader() instanceof IsolatedWeavingClassLoader) {
            throw new AssertionError("Bitronix loads JdbcProxyFactory implementation using a"
                    + " parent-first class loader, which bypasses IsolatedWeavingClassLoader, must"
                    + " use JavaagentContainer");
        }
        PoolingDataSource ds = new PoolingDataSource();
        ds.setClassName(JDBCXADataSource.class.getName());
        Properties props = new Properties();
        props.setProperty("url", "jdbc:hsqldb:mem:test");
        ds.setDriverProperties(props);
        ds.setMaxPoolSize(1);
        ds.setUniqueName("unique-name-" + nextUniqueNum++);
        ds.setAllowLocalTransactions(true);
        return ds;
    }

    static void hackGlassfishConnection(Connection connection) throws Exception {
        Class<?> hackClass =
                Class.forName("org.glowroot.agent.plugin.jdbc.GlassfishConnectionHack");
        hackClass.getMethod("hack", Connection.class).invoke(null, connection);
    }

    private static Connection initConnection(DataSource ds) throws Exception {
        Connection connection = ds.getConnection();
        insertRecords(connection);
        if (connection.getClass().getName().startsWith("com.sun.gjc.spi.")) {
            hackGlassfishConnection(connection);
        }
        return connection;
    }

    private static Connection createPostgresConnection() throws SQLException {
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
        Connection connection =
                DriverManager.getConnection("jdbc:oracle:thin:@localhost", "glowroot", "glowroot");
        insertRecords(connection);
        return connection;
    }

    private static Connection createMssqlConnection() throws Exception {
        Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
        Connection connection =
                DriverManager.getConnection("jdbc:sqlserver://localhost", "sa", "password");
        insertRecords(connection, "varbinary(max)", "varchar(max)");
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
            } catch (UndeclaredThrowableException e) {
            }
            statement.execute("create table employee (id integer identity, name varchar(100), misc "
                    + binaryTypeName + ", misc2 " + clobTypeName + ")");
            statement.execute("insert into employee (name) values ('john doe')");
            statement.execute("insert into employee (name) values ('jane doe')");
            statement.execute("insert into employee (name) values ('sally doe')");
        } finally {
            statement.close();
        }
    }

    public static class GlassfishSQLTraceListener implements SQLTraceListener {
        @Override
        public void sqlTrace(SQLTraceRecord record) {}
    }
}
