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

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TraceEntryMarker;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class JdbcPluginIT {

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
        Trace trace = container.execute(ExecuteCallableStatement.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry jdbcEntry = entries.get(0);
        assertThat(jdbcEntry.getMessage()).isEqualTo(
                "jdbc execution: insert into employee (name, misc) values (?, ?) ['jane', NULL]");
    }

    @Test
    public void testWithoutResultSetValueTimerNormal() throws Exception {
        // given
        // when
        Trace trace = container.execute(ExecuteStatementAndIterateOverResults.class);
        // then
        boolean found = findExtendedTimerName(trace.getHeader().getRootTimer(), "jdbc execute");
        assertThat(found).isFalse();
    }

    @Test
    public void testWithResultSetValueTimerNormal() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureResultSetGet", true);
        // when
        Trace trace = container.execute(ExecuteStatementAndIterateOverResults.class);
        // then
        boolean found = findExtendedTimerName(trace.getHeader().getRootTimer(), "jdbc execute");
        assertThat(found).isFalse();
    }

    @Test
    public void testWithoutResultSetValueTimerUnderSeparateTraceEntry() throws Exception {
        // given
        // when
        Trace trace = container.execute(GetResultSetValueUnderSeparateTraceEntry.class);
        // then
        boolean found = findExtendedTimerName(trace.getHeader().getRootTimer(), "jdbc execute");
        assertThat(found).isFalse();
    }

    @Test
    public void testWithResultSetValueTimerUnderSeparateTraceEntry() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureResultSetGet", true);
        // when
        Trace trace = container.execute(GetResultSetValueUnderSeparateTraceEntry.class);
        // then
        boolean found = findExtendedTimerName(trace.getHeader().getRootTimer(), "jdbc execute");
        assertThat(found).isTrue();
    }

    @Test
    public void testResultSetValueTimerUsingColumnName() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureResultSetGet", true);
        // when
        Trace trace = container.execute(ExecuteStatementAndIterateOverResultsUsingColumnName.class);
        // then
        boolean found = findExtendedTimerName(trace.getHeader().getRootTimer(), "jdbc execute");
        assertThat(found).isFalse();
    }

    @Test
    public void testResultSetValueTimerUsingColumnNameUnderSeparateTraceEntry() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureResultSetGet", true);
        // when
        Trace trace = container.execute(
                ExecuteStatementAndIterateOverResultsUsingColumnNameUnderSeparateTraceEntry.class);
        // then
        boolean found = findExtendedTimerName(trace.getHeader().getRootTimer(), "jdbc execute");
        assertThat(found).isTrue();
    }

    @Test
    public void testWithResultSetNavigateTimerNormal() throws Exception {
        // given
        // when
        Trace trace = container.execute(ExecuteStatementAndIterateOverResults.class);
        // then
        boolean found = findExtendedTimerName(trace.getHeader().getRootTimer(), "jdbc execute");
        assertThat(found).isFalse();
    }

    @Test
    public void testWithResultSetNavigateTimerUnderSeparateTraceEntry() throws Exception {
        // given
        // when
        Trace trace = container.execute(IterateOverResultsUnderSeparateTraceEntry.class);
        // then
        boolean found = findExtendedTimerName(trace.getHeader().getRootTimer(), "jdbc execute");
        assertThat(found).isTrue();
    }

    @Test
    public void testWithoutResultSetNavigateTimerUnderSeparateTraceEntry() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureResultSetNavigate",
                false);
        // when
        Trace trace = container.execute(IterateOverResultsUnderSeparateTraceEntry.class);
        // then
        boolean found = findExtendedTimerName(trace.getHeader().getRootTimer(), "jdbc execute");
        assertThat(found).isFalse();
    }

    @Test
    public void testDefaultStackTraceThreshold() throws Exception {
        // given
        // when
        Trace trace = container.execute(ExecuteStatementAndIterateOverResults.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry jdbcEntry = entries.get(0);
        assertThat(jdbcEntry.getMessage())
                .isEqualTo("jdbc execution: select * from employee => 3 rows");
        assertThat(jdbcEntry.getLocationStackTraceElementList()).isEmpty();
    }

    @Test
    public void testZeroStackTraceThreshold() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "stackTraceThresholdMillis", 0.0);
        // when
        Trace trace = container.execute(ExecuteStatementAndIterateOverResults.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry jdbcEntry = entries.get(0);
        assertThat(jdbcEntry.getMessage())
                .isEqualTo("jdbc execution: select * from employee => 3 rows");
        assertThat(jdbcEntry.getLocationStackTraceElementList()).isNotEmpty();
    }

    @Test
    public void testNullStackTraceThreshold() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "stackTraceThresholdMillis",
                (Double) null);
        // when
        Trace trace = container.execute(ExecuteStatementAndIterateOverResults.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry jdbcEntry = entries.get(0);
        assertThat(jdbcEntry.getMessage())
                .isEqualTo("jdbc execution: select * from employee => 3 rows");
        assertThat(jdbcEntry.getLocationStackTraceElementList()).isEmpty();
    }

    @Test
    public void testPluginDisabled() throws Exception {
        // given
        container.getConfigService().disablePlugin(PLUGIN_ID);
        // when
        Trace trace = container.execute(ExecuteStatementAndIterateOverResults.class);
        // then
        assertThat(trace.getHeader().getEntryCount()).isZero();
    }

    private boolean findExtendedTimerName(Trace.Timer timer, String timerName) {
        if (timer.getName().equals(timerName) && timer.getExtended()) {
            return true;
        }
        for (Trace.Timer nestedTimer : timer.getChildTimerList()) {
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
