/*
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.plugin.jdbc;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

import org.apache.commons.dbcp.BasicDataSource;
import org.apache.tomcat.jdbc.pool.DataSource;
import org.hsqldb.jdbc.JDBCDriver;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import io.informant.testkit.AppUnderTest;
import io.informant.testkit.AppUnderTestServices;
import io.informant.testkit.Container;
import io.informant.testkit.Metric;
import io.informant.testkit.Span;
import io.informant.testkit.Trace;
import io.informant.testkit.TraceMarker;

import static org.fest.assertions.api.Assertions.assertThat;

/**
 * Basic tests of the jdbc plugin.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class JdbcPluginTest {

    private static final String PLUGIN_ID = "jdbc";

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Container.create();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
    }

    @After
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void testStatement() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteStatementAndIterateOverResults.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(2);
        Span jdbcSpan = trace.getSpans().get(1);
        assertThat(jdbcSpan.getMessage().getText()).startsWith(
                "jdbc execution: select * from employee => 3 rows [connection: ");
    }

    @Test
    public void testStatementUsingPrevious() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteStatementAndUsePrevious.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(2);
        Span jdbcSpan = trace.getSpans().get(1);
        assertThat(jdbcSpan.getMessage().getText()).startsWith(
                "jdbc execution: select * from employee => 3 rows [connection: ");
    }

    @Test
    public void testStatementUsingRelativeForward() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteStatementAndUseRelativeForward.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(2);
        Span jdbcSpan = trace.getSpans().get(1);
        assertThat(jdbcSpan.getMessage().getText()).startsWith(
                "jdbc execution: select * from employee => 3 rows [connection: ");
    }

    @Test
    public void testStatementUsingRelativeBackward() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteStatementAndUseRelativeBackward.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(2);
        Span jdbcSpan = trace.getSpans().get(1);
        assertThat(jdbcSpan.getMessage().getText()).startsWith(
                "jdbc execution: select * from employee => 3 rows [connection: ");
    }

    @Test
    public void testStatementUsingAbsolute() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteStatementAndUseAbsolute.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(2);
        Span jdbcSpan = trace.getSpans().get(1);
        assertThat(jdbcSpan.getMessage().getText()).startsWith(
                "jdbc execution: select * from employee => 2 rows [connection: ");
    }

    @Test
    public void testStatementUsingFirst() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteStatementAndUseFirst.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(2);
        Span jdbcSpan = trace.getSpans().get(1);
        assertThat(jdbcSpan.getMessage().getText()).startsWith(
                "jdbc execution: select * from employee => 1 row [connection: ");
    }

    @Test
    public void testStatementUsingLast() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteStatementAndUseLast.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(2);
        Span jdbcSpan = trace.getSpans().get(1);
        assertThat(jdbcSpan.getMessage().getText()).startsWith(
                "jdbc execution: select * from employee => 3 rows [connection: ");
    }

    @Test
    public void testPreparedStatement() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecutePreparedStatementAndIterateOverResults.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(2);
        Span jdbcSpan = trace.getSpans().get(1);
        assertThat(jdbcSpan.getMessage().getText()).startsWith(
                "jdbc execution: select * from employee where name like ? => 1 row [connection: ");
    }

    @Test
    public void testPreparedStatementWithBindParameters() throws Exception {
        // given
        container.setPluginProperty(PLUGIN_ID, "captureBindParameters", true);
        // when
        container.executeAppUnderTest(ExecutePreparedStatementAndIterateOverResults.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(2);
        Span jdbcSpan = trace.getSpans().get(1);
        assertThat(jdbcSpan.getMessage().getText()).startsWith(
                "jdbc execution: select * from employee"
                        + " where name like ? ['john%'] => 1 row [connection: ");
    }

    @Test
    public void testPreparedStatementWithSetNull() throws Exception {
        // given
        container.setPluginProperty(PLUGIN_ID, "captureBindParameters", true);
        // whens
        container.executeAppUnderTest(ExecutePreparedStatementWithSetNull.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(2);
        Span jdbcSpan = trace.getSpans().get(1);
        assertThat(jdbcSpan.getMessage().getText()).startsWith(
                "jdbc execution: insert into employee values (?, ?) [NULL, NULL] [connection: ");
    }

    @Test
    public void testPreparedStatementWithBinary() throws Exception {
        // given
        container.setPluginProperty(PLUGIN_ID, "captureBindParameters", true);
        // when
        container.executeAppUnderTest(ExecutePreparedStatementWithBinary.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(2);
        Span jdbcSpan = trace.getSpans().get(1);
        assertThat(jdbcSpan.getMessage().getText()).startsWith(
                "jdbc execution: insert into employee values (?, ?) ['jane',"
                        + " 0x00010203040506070809] [connection: ");
    }

    @Test
    public void testCallableStatement() throws Exception {
        // given
        container.setPluginProperty(PLUGIN_ID, "captureBindParameters", true);
        // when
        container.executeAppUnderTest(ExecuteCallableStatement.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(2);
        Span jdbcSpan = trace.getSpans().get(1);
        assertThat(jdbcSpan.getMessage().getText()).startsWith(
                "jdbc execution: insert into employee values (?, ?) ['jane', NULL] [connection: ");
    }

    @Test
    public void testCommit() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteJdbcCommit.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(3);
        Span jdbcInsertSpan = trace.getSpans().get(1);
        assertThat(jdbcInsertSpan.getMessage().getText()).startsWith(
                "jdbc execution: insert into employee (name) values ('john doe') [connection: ");
        Span jdbcCommitSpan = trace.getSpans().get(2);
        assertThat(jdbcCommitSpan.getMessage().getText()).startsWith("jdbc commit [connection: ");
        assertThat(trace.getMetrics()).hasSize(4);
        // ordering is by total desc, so not fixed (though root span will be first since it
        // encompasses all other timings)
        assertThat(trace.getMetrics().get(0).getName()).isEqualTo("mock trace marker");
        assertThat(trace.getMetricNames()).containsOnly("mock trace marker", "jdbc execute",
                "jdbc commit", "jdbc statement close");
    }

    @Test
    public void testResultSetValueMetric() throws Exception {
        // given
        container.setPluginProperty(PLUGIN_ID, "captureResultSetGet", true);
        // when
        container.executeAppUnderTest(ExecuteStatementAndIterateOverResults.class);
        // then
        Trace trace = container.getLastTrace();
        boolean found = false;
        for (Metric metric : trace.getMetrics()) {
            if (metric.getName().equals("jdbc resultset value")) {
                found = true;
                break;
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    public void testMetadataMetricDisabledSpan() throws Exception {
        // given
        container.setPluginProperty(PLUGIN_ID, "captureDatabaseMetaDataSpans", false);
        // when
        container.executeAppUnderTest(AccessMetaData.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(1);
        assertThat(trace.getMetrics().size()).isEqualTo(2);
        assertThat(trace.getMetrics().get(0).getName()).isEqualTo("mock trace marker");
        assertThat(trace.getMetrics().get(1).getName()).isEqualTo("jdbc metadata");
    }

    @Test
    public void testMetadataMetricEnabledSpan() throws Exception {
        // given
        container.setPluginProperty(PLUGIN_ID, "captureDatabaseMetaDataSpans", true);
        // when
        container.executeAppUnderTest(AccessMetaData.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(2);
        assertThat(trace.getSpans().get(1).getMessage().getText()).startsWith("jdbc metadata:"
                + " DatabaseMetaData.getTables() [connection: ");
        assertThat(trace.getMetrics().size()).isEqualTo(2);
        assertThat(trace.getMetrics().get(0).getName()).isEqualTo("mock trace marker");
        assertThat(trace.getMetrics().get(1).getName()).isEqualTo("jdbc metadata");
    }

    @Test
    public void testBatchPreparedStatement() throws Exception {
        // given
        container.setPluginProperty(PLUGIN_ID, "captureBindParameters", true);
        // when
        container.executeAppUnderTest(ExecuteBatchPreparedStatement.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(3);
        assertThat(trace.getSpans().get(1).getMessage().getText()).startsWith("jdbc execution: 2 x"
                + " insert into employee (name) values (?) ['huckle'] ['sally'] [connection: ");
        assertThat(trace.getSpans().get(2).getMessage().getText()).startsWith("jdbc execution: 2 x"
                + " insert into employee (name) values (?) ['lowly'] ['pig will'] [connection: ");
    }

    @Test
    public void testBatchPreparedStatementWithoutClear() throws Exception {
        // given
        container.setPluginProperty(PLUGIN_ID, "captureBindParameters", true);
        // when
        container.executeAppUnderTest(ExecuteBatchPreparedStatementWithoutClear.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(3);
        assertThat(trace.getSpans().get(1).getMessage().getText()).startsWith("jdbc execution: 2 x"
                + " insert into employee (name) values (?) ['huckle'] ['sally'] [connection: ");
        assertThat(trace.getSpans().get(2).getMessage().getText()).startsWith("jdbc execution: 4 x"
                + " insert into employee (name) values (?) ['huckle'] ['sally'] ['lowly']"
                + " ['pig will'] [connection: ");
    }

    @Test
    public void testBatchStatement() throws Exception {
        // given
        container.setPluginProperty(PLUGIN_ID, "captureBindParameters", true);
        // when
        container.executeAppUnderTest(ExecuteBatchStatement.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(3);
        assertThat(trace.getSpans().get(1).getMessage().getText()).startsWith("jdbc execution:"
                + " insert into employee (name) values ('huckle'),"
                + " insert into employee (name) values ('sally') [connection: ");
        assertThat(trace.getSpans().get(2).getMessage().getText()).startsWith("jdbc execution:"
                + " insert into employee (name) values ('lowly'),"
                + " insert into employee (name) values ('pig will') [connection: ");
    }

    @Test
    public void testBatchStatementWithoutClear() throws Exception {
        // given
        container.setPluginProperty(PLUGIN_ID, "captureBindParameters", true);
        // when
        container.executeAppUnderTest(ExecuteBatchStatementWithoutClear.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(3);
        assertThat(trace.getSpans().get(1).getMessage().getText()).startsWith("jdbc execution:"
                + " insert into employee (name) values ('huckle'),"
                + " insert into employee (name) values ('sally') [connection: ");
        assertThat(trace.getSpans().get(2).getMessage().getText()).startsWith("jdbc execution:"
                + " insert into employee (name) values ('huckle'),"
                + " insert into employee (name) values ('sally'),"
                + " insert into employee (name) values ('lowly'),"
                + " insert into employee (name) values ('pig will') [connection: ");
    }

    // this test validates that lastJdbcMessageSupplier is cleared so that its numRows won't be
    // updated if the plugin is re-enabled in the middle of iterating over a different result set
    // (see related comments in StatementAspect)
    @Test
    public void testDisableReEnableMidIterating() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteStatementDisableReEnableMidIterating.class);
        // then
        Trace trace = container.getLastTrace();
        assertThat(trace.getSpans()).hasSize(2);
        assertThat(trace.getSpans().get(1).getMessage().getText()).startsWith(
                "jdbc execution: select * from employee where name like ? => 0 rows [connection: ");
    }

    // TODO make a release build profile that runs all tests against
    // Hsqldb, Oracle, SQLServer, MySQL, ...

    private static Connection createConnection() throws SQLException {
        return createHsqldbConnection();
    }

    private static Connection createHsqldbConnection() throws SQLException {
        // set up database
        Connection connection = JDBCDriver.getConnection("jdbc:hsqldb:mem:test", null);
        insertRecords(connection);
        return connection;
    }

    @SuppressWarnings("unused")
    private static Connection createCommonsDbcpWrappedConnection() throws SQLException {
        // set up database
        BasicDataSource ds = new BasicDataSource();
        ds.setDriverClassName("org.hsqldb.jdbc.JDBCDriver");
        ds.setUrl("jdbc:hsqldb:mem:test");
        Connection connection = ds.getConnection();
        insertRecords(connection);
        return connection;
    }

    // NOTE tomcat jdbc pool requires JDK 6
    @SuppressWarnings("unused")
    private static Connection createTomcatJdbcPoolWrappedConnection() throws SQLException {
        // set up database
        DataSource ds = new DataSource();
        ds.setDriverClassName("org.hsqldb.jdbc.JDBCDriver");
        ds.setUrl("jdbc:hsqldb:mem:test");
        ds.setJdbcInterceptors(
                "org.apache.tomcat.jdbc.pool.interceptor.StatementDecoratorInterceptor");
        Connection connection = ds.getConnection();
        insertRecords(connection);
        return connection;
    }

    private static void insertRecords(Connection connection) throws SQLException {
        Statement statement = connection.createStatement();
        try {
            try {
                // in case of previous failure mid-test
                statement.execute("drop table employee");
            } catch (SQLException e) {
            }
            statement.execute("create table employee (name varchar(100), misc binary(100))");
            statement.execute("insert into employee (name) values ('john doe')");
            statement.execute("insert into employee (name) values ('jane doe')");
            statement.execute("insert into employee (name) values ('sally doe')");
        } finally {
            statement.close();
        }
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
    @SuppressWarnings("unused")
    private static Connection createOracleConnection() throws SQLException {
        // set up database
        Connection connection = DriverManager.getConnection("jdbc:oracle:thin:@//localhost/orcl",
                "informant", "informant");
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
    @SuppressWarnings("unused")
    private static Connection createSqlServerConnection() throws ClassNotFoundException,
            SQLException {
        // set up database
        Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
        Connection connection = DriverManager.getConnection("jdbc:sqlserver://localhost",
                "sa", "Datac3rt");
        insertRecords(connection);
        return connection;
    }

    private static void closeConnection(Connection connection) throws SQLException {
        Statement statement = connection.createStatement();
        try {
            statement.execute("drop table employee");
        } finally {
            statement.close();
        }
        connection.close();
    }

    public static class ExecuteStatementAndIterateOverResults implements AppUnderTest, TraceMarker {
        private Connection connection;
        public void executeApp() throws Exception {
            connection = createConnection();
            try {
                traceMarker();
            } finally {
                closeConnection(connection);
            }
        }
        public void traceMarker() throws Exception {
            Statement statement = connection.createStatement();
            try {
                statement.execute("select * from employee");
                ResultSet rs = statement.getResultSet();
                while (rs.next()) {
                    rs.getString(1);
                }
            } finally {
                statement.close();
            }
        }
    }

    public static class ExecuteStatementAndUsePrevious implements AppUnderTest, TraceMarker {
        private Connection connection;
        public void executeApp() throws Exception {
            connection = createConnection();
            try {
                traceMarker();
            } finally {
                closeConnection(connection);
            }
        }
        public void traceMarker() throws Exception {
            Statement statement = connection.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
                    ResultSet.CONCUR_READ_ONLY);
            try {
                statement.execute("select * from employee");
                ResultSet rs = statement.getResultSet();
                rs.afterLast();
                while (rs.previous()) {
                    rs.getString(1);
                }
            } finally {
                statement.close();
            }
        }
    }

    public static class ExecuteStatementAndUseRelativeForward implements AppUnderTest, TraceMarker {
        private Connection connection;
        public void executeApp() throws Exception {
            connection = createConnection();
            try {
                traceMarker();
            } finally {
                closeConnection(connection);
            }
        }
        public void traceMarker() throws Exception {
            Statement statement = connection.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
                    ResultSet.CONCUR_READ_ONLY);
            try {
                statement.execute("select * from employee");
                ResultSet rs = statement.getResultSet();
                // need to position cursor on a valid row before calling relative(), at least for
                // sqlserver jdbc driver
                rs.next();
                rs.getString(1);
                while (rs.relative(1)) {
                    rs.getString(1);
                }
            } finally {
                statement.close();
            }
        }
    }

    public static class ExecuteStatementAndUseRelativeBackward implements AppUnderTest,
            TraceMarker {
        private Connection connection;
        public void executeApp() throws Exception {
            connection = createConnection();
            try {
                traceMarker();
            } finally {
                closeConnection(connection);
            }
        }
        public void traceMarker() throws Exception {
            Statement statement = connection.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
                    ResultSet.CONCUR_READ_ONLY);
            try {
                statement.execute("select * from employee");
                ResultSet rs = statement.getResultSet();
                rs.afterLast();
                // need to position cursor on a valid row before calling relative(), at least for
                // sqlserver jdbc driver
                rs.previous();
                rs.getString(1);
                while (rs.relative(-1)) {
                    rs.getString(1);
                }
            } finally {
                statement.close();
            }
        }
    }

    public static class ExecuteStatementAndUseAbsolute implements AppUnderTest, TraceMarker {
        private Connection connection;
        public void executeApp() throws Exception {
            connection = createConnection();
            try {
                traceMarker();
            } finally {
                closeConnection(connection);
            }
        }
        public void traceMarker() throws Exception {
            Statement statement = connection.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
                    ResultSet.CONCUR_READ_ONLY);
            try {
                statement.execute("select * from employee");
                ResultSet rs = statement.getResultSet();
                rs.absolute(2);
            } finally {
                statement.close();
            }
        }
    }

    public static class ExecuteStatementAndUseFirst implements AppUnderTest, TraceMarker {
        private Connection connection;
        public void executeApp() throws Exception {
            connection = createConnection();
            try {
                traceMarker();
            } finally {
                closeConnection(connection);
            }
        }
        public void traceMarker() throws Exception {
            Statement statement = connection.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
                    ResultSet.CONCUR_READ_ONLY);
            try {
                statement.execute("select * from employee");
                ResultSet rs = statement.getResultSet();
                rs.first();
            } finally {
                statement.close();
            }
        }
    }

    public static class ExecuteStatementAndUseLast implements AppUnderTest, TraceMarker {
        private Connection connection;
        public void executeApp() throws Exception {
            connection = createConnection();
            try {
                traceMarker();
            } finally {
                closeConnection(connection);
            }
        }
        public void traceMarker() throws Exception {
            Statement statement = connection.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
                    ResultSet.CONCUR_READ_ONLY);
            try {
                statement.execute("select * from employee");
                ResultSet rs = statement.getResultSet();
                rs.last();
            } finally {
                statement.close();
            }
        }
    }

    public static class ExecutePreparedStatementAndIterateOverResults implements AppUnderTest,
            TraceMarker {
        private Connection connection;
        public void executeApp() throws Exception {
            connection = createConnection();
            try {
                traceMarker();
            } finally {
                closeConnection(connection);
            }
        }
        public void traceMarker() throws Exception {
            PreparedStatement preparedStatement =
                    connection.prepareStatement("select * from employee where name like ?");
            try {
                preparedStatement.setString(1, "john%");
                preparedStatement.execute();
                ResultSet rs = preparedStatement.getResultSet();
                while (rs.next()) {
                    rs.getString(1);
                }
            } finally {
                preparedStatement.close();
            }
        }
    }

    public static class ExecutePreparedStatementWithSetNull implements AppUnderTest, TraceMarker {
        private Connection connection;
        public void executeApp() throws Exception {
            connection = createConnection();
            try {
                traceMarker();
            } finally {
                closeConnection(connection);
            }
        }
        public void traceMarker() throws Exception {
            PreparedStatement preparedStatement =
                    connection.prepareStatement("insert into employee values (?, ?)");
            try {
                preparedStatement.setNull(1, Types.VARCHAR);
                preparedStatement.setNull(2, Types.BINARY);
                preparedStatement.execute();
            } finally {
                preparedStatement.close();
            }
        }
    }

    public static class ExecutePreparedStatementWithBinary implements AppUnderTest, TraceMarker {
        static {
            JdbcPluginProperties.setDisplayBinaryParameterAsHex(
                    "insert into employee values (?, ?)", 2);
        }
        private Connection connection;
        public void executeApp() throws Exception {
            connection = createConnection();
            try {
                traceMarker();
            } finally {
                closeConnection(connection);
            }
        }
        public void traceMarker() throws Exception {
            PreparedStatement preparedStatement =
                    connection.prepareStatement("insert into employee values (?, ?)");
            try {
                preparedStatement.setString(1, "jane");
                byte[] bytes = new byte[10];
                for (int i = 0; i < 10; i++) {
                    bytes[i] = (byte) i;
                }
                preparedStatement.setBytes(2, bytes);
                preparedStatement.execute();
            } finally {
                preparedStatement.close();
            }
        }
    }

    public static class ExecuteCallableStatement implements AppUnderTest, TraceMarker {
        private Connection connection;
        public void executeApp() throws Exception {
            connection = createConnection();
            try {
                traceMarker();
            } finally {
                closeConnection(connection);
            }
        }
        public void traceMarker() throws Exception {
            CallableStatement callableStatement =
                    connection.prepareCall("insert into employee values (?, ?)");
            try {
                callableStatement.setString(1, "jane");
                callableStatement.setNull(2, Types.BINARY);
                callableStatement.execute();
            } finally {
                callableStatement.close();
            }
        }
    }

    public static class ExecuteJdbcCommit implements AppUnderTest, TraceMarker {
        private Connection connection;
        public void executeApp() throws Exception {
            connection = createConnection();
            connection.setAutoCommit(false);
            try {
                traceMarker();
            } finally {
                closeConnection(connection);
            }
        }
        public void traceMarker() throws Exception {
            Statement statement = connection.createStatement();
            try {
                statement.execute("insert into employee (name) values ('john doe')");
            } finally {
                statement.close();
            }
            connection.commit();
        }
    }

    public static class AccessMetaData implements AppUnderTest, TraceMarker {
        private Connection connection;
        public void executeApp() throws Exception {
            connection = createConnection();
            connection.setAutoCommit(false);
            try {
                traceMarker();
            } finally {
                closeConnection(connection);
            }
        }
        public void traceMarker() throws Exception {
            connection.getMetaData().getTables(null, null, null, null);
        }
    }

    public static class ExecuteBatchPreparedStatement implements AppUnderTest, TraceMarker {
        private Connection connection;
        public void executeApp() throws Exception {
            connection = createConnection();
            connection.setAutoCommit(false);
            try {
                traceMarker();
            } finally {
                closeConnection(connection);
            }
        }
        public void traceMarker() throws Exception {
            PreparedStatement preparedStatement =
                    connection.prepareStatement("insert into employee (name) values (?)");
            try {
                preparedStatement.setString(1, "huckle");
                preparedStatement.addBatch();
                preparedStatement.setString(1, "sally");
                preparedStatement.addBatch();
                preparedStatement.executeBatch();
                preparedStatement.clearBatch();
                preparedStatement.setString(1, "lowly");
                preparedStatement.addBatch();
                preparedStatement.setString(1, "pig will");
                preparedStatement.addBatch();
                preparedStatement.executeBatch();
            } finally {
                preparedStatement.close();
            }
        }
    }

    public static class ExecuteBatchPreparedStatementWithoutClear implements AppUnderTest,
            TraceMarker {
        private Connection connection;
        public void executeApp() throws Exception {
            connection = createConnection();
            connection.setAutoCommit(false);
            try {
                traceMarker();
            } finally {
                closeConnection(connection);
            }
        }
        public void traceMarker() throws Exception {
            PreparedStatement preparedStatement =
                    connection.prepareStatement("insert into employee (name) values (?)");
            try {
                preparedStatement.setString(1, "huckle");
                preparedStatement.addBatch();
                preparedStatement.setString(1, "sally");
                preparedStatement.addBatch();
                preparedStatement.executeBatch();
                // intentionally not calling preparedStatement.clearBatch()
                preparedStatement.setString(1, "lowly");
                preparedStatement.addBatch();
                preparedStatement.setString(1, "pig will");
                preparedStatement.addBatch();
                preparedStatement.executeBatch();
            } finally {
                preparedStatement.close();
            }
        }
    }

    public static class ExecuteBatchStatement implements AppUnderTest, TraceMarker {
        private Connection connection;
        public void executeApp() throws Exception {
            connection = createConnection();
            connection.setAutoCommit(false);
            try {
                traceMarker();
            } finally {
                closeConnection(connection);
            }
        }
        public void traceMarker() throws Exception {
            Statement statement = connection.createStatement();
            try {
                statement.addBatch("insert into employee (name) values ('huckle')");
                statement.addBatch("insert into employee (name) values ('sally')");
                statement.executeBatch();
                statement.clearBatch();
                statement.addBatch("insert into employee (name) values ('lowly')");
                statement.addBatch("insert into employee (name) values ('pig will')");
                statement.executeBatch();
            } finally {
                statement.close();
            }
        }
    }

    public static class ExecuteBatchStatementWithoutClear implements AppUnderTest, TraceMarker {
        private Connection connection;
        public void executeApp() throws Exception {
            connection = createConnection();
            connection.setAutoCommit(false);
            try {
                traceMarker();
            } finally {
                closeConnection(connection);
            }
        }
        public void traceMarker() throws Exception {
            Statement statement = connection.createStatement();
            try {
                statement.addBatch("insert into employee (name) values ('huckle')");
                statement.addBatch("insert into employee (name) values ('sally')");
                statement.executeBatch();
                // intentionally not calling statement.clearBatch()
                statement.addBatch("insert into employee (name) values ('lowly')");
                statement.addBatch("insert into employee (name) values ('pig will')");
                statement.executeBatch();
            } finally {
                statement.close();
            }
        }
    }

    public static class ExecuteStatementDisableReEnableMidIterating implements AppUnderTest,
            TraceMarker {
        private static final AppUnderTestServices services = AppUnderTestServices.get();
        private Connection connection;
        public void executeApp() throws Exception {
            connection = createConnection();
            try {
                traceMarker();
            } finally {
                closeConnection(connection);
            }
        }
        public void traceMarker() throws Exception {
            PreparedStatement preparedStatement =
                    connection.prepareStatement("select * from employee where name like ?");
            try {
                // pull back 0 records
                preparedStatement.setString(1, "nomatch%");
                preparedStatement.execute();
                ResultSet rs = preparedStatement.getResultSet();
                rs.next();
                // disable plugin and re-execute same prepared statement
                services.setPluginEnabled(PLUGIN_ID, false);
                preparedStatement.setString(1, "john%");
                preparedStatement.execute();
                // re-enable plugin and iterate over 1 record to make sure that these records are
                // not attributed to the previous execution
                services.setPluginEnabled(PLUGIN_ID, true);
                rs = preparedStatement.getResultSet();
                rs.next();
                rs.next();
                rs.next();
                rs.next();
            } finally {
                preparedStatement.close();
            }
        }
    }
}
