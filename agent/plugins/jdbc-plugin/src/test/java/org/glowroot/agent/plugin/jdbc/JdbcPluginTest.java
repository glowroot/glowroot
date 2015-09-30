/*
 * Copyright 2011-2015 the original author or authors.
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

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.harness.AppUnderTest;
import org.glowroot.agent.harness.Container;
import org.glowroot.agent.harness.Containers;
import org.glowroot.agent.harness.TraceEntryMarker;
import org.glowroot.agent.harness.TransactionMarker;
import org.glowroot.agent.harness.aggregate.Query;
import org.glowroot.agent.harness.config.PluginConfig;
import org.glowroot.agent.harness.trace.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class JdbcPluginTest {

    private static final String PLUGIN_ID = "jdbc";

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.getSharedContainer();
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
    public void testCallableStatement() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureBindParameters", true);
        // when
        container.executeAppUnderTest(ExecuteCallableStatement.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        List<Query> queries = container.getAggregateService().getQueries();
        assertThat(queries).hasSize(1);
        Query query = queries.get(0);
        assertThat(query.getQueryText())
                .isEqualTo("insert into employee (name, misc) values (?, ?)");
        assertThat(query.getExecutionCount()).isEqualTo(1);
        assertThat(query.getTotalRows()).isEqualTo(0);
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(1);
        Trace.Entry jdbcEntry = entries.get(0);
        assertThat(jdbcEntry.message()).isEqualTo(
                "jdbc execution: insert into employee (name, misc) values (?, ?) ['jane', NULL]");
    }

    @Test
    public void testWithoutResultSetValueTimerNormal() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteStatementAndIterateOverResults.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        boolean found = findExtendedTimerName(header.rootTimer(), "jdbc execute");
        assertThat(found).isFalse();
    }

    @Test
    public void testWithResultSetValueTimerNormal() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureResultSetGet", true);
        // when
        container.executeAppUnderTest(ExecuteStatementAndIterateOverResults.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        boolean found = findExtendedTimerName(header.rootTimer(), "jdbc execute");
        assertThat(found).isFalse();
    }

    @Test
    public void testWithoutResultSetValueTimerUnderSeparateTraceEntry() throws Exception {
        // given
        // when
        container.executeAppUnderTest(GetResultSetValueUnderSeparateTraceEntry.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        boolean found = findExtendedTimerName(header.rootTimer(), "jdbc execute");
        assertThat(found).isFalse();
    }

    @Test
    public void testWithResultSetValueTimerUnderSeparateTraceEntry() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureResultSetGet", true);
        // when
        container.executeAppUnderTest(GetResultSetValueUnderSeparateTraceEntry.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        boolean found = findExtendedTimerName(header.rootTimer(), "jdbc execute");
        assertThat(found).isTrue();
    }

    @Test
    public void testResultSetValueTimerUsingColumnName() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureResultSetGet", true);
        // when
        container.executeAppUnderTest(ExecuteStatementAndIterateOverResultsUsingColumnName.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        boolean found = findExtendedTimerName(header.rootTimer(), "jdbc execute");
        assertThat(found).isFalse();
    }

    @Test
    public void testResultSetValueTimerUsingColumnNameUnderSeparateTraceEntry() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureResultSetGet", true);
        // when
        container.executeAppUnderTest(
                ExecuteStatementAndIterateOverResultsUsingColumnNameUnderSeparateTraceEntry.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        boolean found = findExtendedTimerName(header.rootTimer(), "jdbc execute");
        assertThat(found).isTrue();
    }

    @Test
    public void testWithResultSetNavigateTimerNormal() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteStatementAndIterateOverResults.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        boolean found = findExtendedTimerName(header.rootTimer(), "jdbc execute");
        assertThat(found).isFalse();
    }

    @Test
    public void testWithResultSetNavigateTimerUnderSeparateTraceEntry() throws Exception {
        // given
        // when
        container.executeAppUnderTest(IterateOverResultsUnderSeparateTraceEntry.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        boolean found = findExtendedTimerName(header.rootTimer(), "jdbc execute");
        assertThat(found).isTrue();
    }

    @Test
    public void testWithoutResultSetNavigateTimerUnderSeparateTraceEntry() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureResultSetNavigate",
                false);
        // when
        container.executeAppUnderTest(IterateOverResultsUnderSeparateTraceEntry.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        boolean found = findExtendedTimerName(header.rootTimer(), "jdbc execute");
        assertThat(found).isFalse();
    }

    @Test
    public void testDefaultStackTraceThreshold() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteStatementAndIterateOverResults.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        List<Query> queries = container.getAggregateService().getQueries();
        assertThat(queries).hasSize(1);
        Query query = queries.get(0);
        assertThat(query.getQueryText()).isEqualTo("select * from employee");
        assertThat(query.getExecutionCount()).isEqualTo(1);
        assertThat(query.getTotalRows()).isEqualTo(3);
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(1);
        Trace.Entry jdbcEntry = entries.get(0);
        assertThat(jdbcEntry.message())
                .isEqualTo("jdbc execution: select * from employee => 3 rows");
        assertThat(jdbcEntry.locationStackTraceElements()).isEmpty();
    }

    @Test
    public void testZeroStackTraceThreshold() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "stackTraceThresholdMillis", 0);
        // when
        container.executeAppUnderTest(ExecuteStatementAndIterateOverResults.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(1);
        Trace.Entry jdbcEntry = entries.get(0);
        assertThat(jdbcEntry.message())
                .isEqualTo("jdbc execution: select * from employee => 3 rows");
        assertThat(jdbcEntry.locationStackTraceElements()).isNotEmpty();
    }

    @Test
    public void testNullStackTraceThreshold() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "stackTraceThresholdMillis",
                null);
        // when
        container.executeAppUnderTest(ExecuteStatementAndIterateOverResults.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(1);
        Trace.Entry jdbcEntry = entries.get(0);
        assertThat(jdbcEntry.message())
                .isEqualTo("jdbc execution: select * from employee => 3 rows");
        assertThat(jdbcEntry.locationStackTraceElements()).isEmpty();
    }

    @Test
    public void testPluginDisabled() throws Exception {
        // given
        PluginConfig pluginConfig = container.getConfigService().getPluginConfig(PLUGIN_ID);
        pluginConfig.setEnabled(false);
        container.getConfigService().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        container.executeAppUnderTest(ExecuteStatementAndIterateOverResults.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header.entryCount()).isZero();
    }

    @Test
    public void testLotsOfStatements() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteLotsOfStatementAndIterateOverResults.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        Trace.Timer jdbcExecuteTimer = null;
        for (Trace.Timer nestedTimer : header.rootTimer().childTimers()) {
            if (nestedTimer.name().equals("jdbc execute")) {
                jdbcExecuteTimer = nestedTimer;
                break;
            }
        }
        assertThat(jdbcExecuteTimer).isNotNull();
        List<Query> queries = container.getAggregateService().getQueries();
        assertThat(queries).hasSize(1);
        Query query = queries.get(0);
        assertThat(query.getQueryText()).isEqualTo("select * from employee");
        assertThat(query.getExecutionCount()).isEqualTo(4000);
        assertThat(query.getTotalRows()).isEqualTo(12000);
    }

    private boolean findExtendedTimerName(Trace.Timer timer, String timerName) {
        if (timer.name().equals(timerName) && timer.extended().or(false)) {
            return true;
        }
        for (Trace.Timer nestedTimer : timer.childTimers()) {
            if (findExtendedTimerName(nestedTimer, timerName)) {
                return true;
            }
        }
        return false;
    }

    public static class ExecuteStatementAndIterateOverResults
            implements AppUnderTest, TransactionMarker {
        private Connection connection;
        @Override
        public void executeApp() throws Exception {
            connection = Connections.createConnection();
            try {
                transactionMarker();
            } finally {
                Connections.closeConnection(connection);
            }
        }
        @Override
        public void transactionMarker() throws Exception {
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

    public static class ExecuteLotsOfStatementAndIterateOverResults
            implements AppUnderTest, TransactionMarker {
        private Connection connection;
        @Override
        public void executeApp() throws Exception {
            connection = Connections.createConnection();
            try {
                transactionMarker();
            } finally {
                Connections.closeConnection(connection);
            }
        }
        @Override
        public void transactionMarker() throws Exception {
            Statement statement = connection.createStatement();
            try {
                for (int i = 0; i < 4000; i++) {
                    statement.execute("select * from employee");
                    ResultSet rs = statement.getResultSet();
                    while (rs.next()) {
                        rs.getString(1);
                    }
                }
            } finally {
                statement.close();
            }
        }
    }

    public static class IterateOverResultsUnderSeparateTraceEntry
            implements AppUnderTest, TransactionMarker, TraceEntryMarker {
        private Connection connection;
        private Statement statement;
        @Override
        public void executeApp() throws Exception {
            connection = Connections.createConnection();
            try {
                transactionMarker();
            } finally {
                Connections.closeConnection(connection);
            }
        }
        @Override
        public void transactionMarker() throws Exception {
            statement = connection.createStatement();
            try {
                statement.execute("select * from employee");
                traceEntryMarker();
            } finally {
                statement.close();
            }
        }
        @Override
        public void traceEntryMarker() throws SQLException {
            ResultSet rs = statement.getResultSet();
            while (rs.next()) {
                rs.getString(1);
            }
        }
    }

    public static class GetResultSetValueUnderSeparateTraceEntry
            implements AppUnderTest, TransactionMarker, TraceEntryMarker {
        private Connection connection;
        private ResultSet rs;
        @Override
        public void executeApp() throws Exception {
            connection = Connections.createConnection();
            try {
                transactionMarker();
            } finally {
                Connections.closeConnection(connection);
            }
        }
        @Override
        public void transactionMarker() throws Exception {
            Statement statement = connection.createStatement();
            try {
                statement.execute("select * from employee");
                rs = statement.getResultSet();
                while (rs.next()) {
                    traceEntryMarker();
                }
            } finally {
                statement.close();
            }
        }
        @Override
        public void traceEntryMarker() throws SQLException {
            rs.getString(1);
        }
    }

    public static class ExecuteStatementAndIterateOverResultsUsingColumnName
            implements AppUnderTest, TransactionMarker {
        private Connection connection;
        @Override
        public void executeApp() throws Exception {
            connection = Connections.createConnection();
            try {
                transactionMarker();
            } finally {
                Connections.closeConnection(connection);
            }
        }
        @Override
        public void transactionMarker() throws Exception {
            Statement statement = connection.createStatement();
            try {
                statement.execute("select * from employee");
                ResultSet rs = statement.getResultSet();
                while (rs.next()) {
                    rs.getString("name");
                }
            } finally {
                statement.close();
            }
        }
    }

    public static class ExecuteStatementAndIterateOverResultsUsingColumnNameUnderSeparateTraceEntry
            implements AppUnderTest, TransactionMarker, TraceEntryMarker {
        private Connection connection;
        private Statement statement;
        @Override
        public void executeApp() throws Exception {
            connection = Connections.createConnection();
            try {
                transactionMarker();
            } finally {
                Connections.closeConnection(connection);
            }
        }
        @Override
        public void transactionMarker() throws Exception {
            statement = connection.createStatement();
            try {
                statement.execute("select * from employee");
                traceEntryMarker();
            } finally {
                statement.close();
            }
        }
        @Override
        public void traceEntryMarker() throws SQLException {
            ResultSet rs = statement.getResultSet();
            while (rs.next()) {
                rs.getString("name");
            }
        }
    }

    public static class ExecuteCallableStatement implements AppUnderTest, TransactionMarker {
        private Connection connection;
        @Override
        public void executeApp() throws Exception {
            connection = Connections.createConnection();
            try {
                transactionMarker();
            } finally {
                Connections.closeConnection(connection);
            }
        }
        @Override
        public void transactionMarker() throws Exception {
            CallableStatement callableStatement =
                    connection.prepareCall("insert into employee (name, misc) values (?, ?)");
            try {
                callableStatement.setString(1, "jane");
                callableStatement.setNull(2, Types.BINARY);
                callableStatement.execute();
            } finally {
                callableStatement.close();
            }
        }
    }

    public static class AccessMetaData implements AppUnderTest, TransactionMarker {
        private Connection connection;
        @Override
        public void executeApp() throws Exception {
            connection = Connections.createConnection();
            connection.setAutoCommit(false);
            try {
                transactionMarker();
            } finally {
                Connections.closeConnection(connection);
            }
        }
        @Override
        public void transactionMarker() throws Exception {
            connection.getMetaData().getTables(null, null, null, null);
        }
    }
}
