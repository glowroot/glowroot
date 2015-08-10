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
package org.glowroot.plugin.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.apache.commons.dbcp.DelegatingConnection;
import org.apache.commons.dbcp.DelegatingStatement;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.TraceMarker;
import org.glowroot.container.trace.Trace;
import org.glowroot.container.trace.TraceEntry;

import static org.assertj.core.api.Assertions.assertThat;

public class StatementTest {

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
    public void testStatement() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteStatementAndIterateOverResults.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        TraceEntry entry = entries.get(0);
        assertThat(entry.getMessage().getText())
                .isEqualTo("jdbc execution: select * from employee => 3 rows");
    }

    @Test
    public void testStatementQuery() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteStatementQueryAndIterateOverResults.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        TraceEntry entry = entries.get(0);
        assertThat(entry.getMessage().getText())
                .isEqualTo("jdbc execution: select * from employee => 3 rows");
    }

    @Test
    public void testStatementUpdate() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteStatementUpdate.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        TraceEntry entry = entries.get(0);
        assertThat(entry.getMessage().getText())
                .isEqualTo("jdbc execution: update employee set name = 'nobody' => 3 rows");
    }

    @Test
    public void testNullStatement() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteNullStatement.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getEntryCount()).isZero();
    }

    @Test
    public void testStatementThrowing() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteStatementThrowing.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        TraceEntry entry = entries.get(0);
        assertThat(entry.getMessage().getText())
                .isEqualTo("jdbc execution: select * from employee");
        assertThat(entry.getError().getMessage()).isEqualTo("An execute failure");
    }

    @Test
    public void testStatementUsingPrevious() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteStatementAndUsePrevious.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        TraceEntry entry = entries.get(0);
        assertThat(entry.getMessage().getText())
                .isEqualTo("jdbc execution: select * from employee => 3 rows");
    }

    @Test
    public void testStatementUsingRelativeForward() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteStatementAndUseRelativeForward.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        TraceEntry entry = entries.get(0);
        assertThat(entry.getMessage().getText())
                .isEqualTo("jdbc execution: select * from employee => 3 rows");
    }

    @Test
    public void testStatementUsingRelativeBackward() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteStatementAndUseRelativeBackward.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        TraceEntry entry = entries.get(0);
        assertThat(entry.getMessage().getText())
                .isEqualTo("jdbc execution: select * from employee => 3 rows");
    }

    @Test
    public void testStatementUsingAbsolute() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteStatementAndUseAbsolute.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        TraceEntry entry = entries.get(0);
        assertThat(entry.getMessage().getText())
                .isEqualTo("jdbc execution: select * from employee => 2 rows");
    }

    @Test
    public void testStatementUsingFirst() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteStatementAndUseFirst.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        TraceEntry entry = entries.get(0);
        assertThat(entry.getMessage().getText())
                .isEqualTo("jdbc execution: select * from employee => 1 row");
    }

    @Test
    public void testStatementUsingLast() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteStatementAndUseLast.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        TraceEntry entry = entries.get(0);
        assertThat(entry.getMessage().getText())
                .isEqualTo("jdbc execution: select * from employee => 3 rows");
    }

    public static class ExecuteStatementAndIterateOverResults implements AppUnderTest, TraceMarker {
        private Connection connection;
        @Override
        public void executeApp() throws Exception {
            connection = Connections.createConnection();
            try {
                traceMarker();
            } finally {
                Connections.closeConnection(connection);
            }
        }
        @Override
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

    public static class ExecuteStatementQueryAndIterateOverResults
            implements AppUnderTest, TraceMarker {
        private Connection connection;
        @Override
        public void executeApp() throws Exception {
            connection = Connections.createConnection();
            try {
                traceMarker();
            } finally {
                Connections.closeConnection(connection);
            }
        }
        @Override
        public void traceMarker() throws Exception {
            Statement statement = connection.createStatement();
            try {
                ResultSet rs = statement.executeQuery("select * from employee");
                while (rs.next()) {
                    rs.getString(1);
                }
            } finally {
                statement.close();
            }
        }
    }

    public static class ExecuteStatementUpdate implements AppUnderTest, TraceMarker {
        private Connection connection;
        @Override
        public void executeApp() throws Exception {
            connection = Connections.createConnection();
            try {
                traceMarker();
            } finally {
                Connections.closeConnection(connection);
            }
        }
        @Override
        public void traceMarker() throws Exception {
            Statement statement = connection.createStatement();
            try {
                statement.executeUpdate("update employee set name = 'nobody'");
            } finally {
                statement.close();
            }
        }
    }

    public static class ExecuteNullStatement implements AppUnderTest, TraceMarker {
        private Connection delegatingConnection;
        @Override
        public void executeApp() throws Exception {
            Connection connection = Connections.createConnection();
            delegatingConnection = new DelegatingConnection(connection) {
                @Override
                public Statement createStatement() throws SQLException {
                    return new DelegatingStatement(this, super.createStatement()) {
                        @Override
                        public boolean execute(String sql) throws SQLException {
                            return super.execute("select 1 from employee");
                        }
                    };
                }
            };
            try {
                traceMarker();
            } finally {
                Connections.closeConnection(connection);
            }
        }
        @Override
        public void traceMarker() throws Exception {
            Statement statement = delegatingConnection.createStatement();
            try {
                statement.execute(null);
            } finally {
                statement.close();
            }
        }
    }

    public static class ExecuteStatementThrowing implements AppUnderTest, TraceMarker {
        private Connection delegatingConnection;
        @Override
        public void executeApp() throws Exception {
            Connection connection = Connections.createConnection();
            delegatingConnection = new DelegatingConnection(connection) {
                @Override
                public Statement createStatement() throws SQLException {
                    return new DelegatingStatement(this, super.createStatement()) {
                        @Override
                        public boolean execute(String sql) throws SQLException {
                            throw new SQLException("An execute failure");
                        }
                    };
                }
            };
            try {
                traceMarker();
            } finally {
                Connections.closeConnection(connection);
            }
        }
        @Override
        public void traceMarker() throws Exception {
            Statement statement = delegatingConnection.createStatement();
            try {
                statement.execute("select * from employee");
            } catch (SQLException e) {
            } finally {
                statement.close();
            }
        }
    }

    public static class ExecuteStatementAndUsePrevious implements AppUnderTest, TraceMarker {
        private Connection connection;
        @Override
        public void executeApp() throws Exception {
            connection = Connections.createConnection();
            try {
                traceMarker();
            } finally {
                Connections.closeConnection(connection);
            }
        }
        @Override
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
        @Override
        public void executeApp() throws Exception {
            connection = Connections.createConnection();
            try {
                traceMarker();
            } finally {
                Connections.closeConnection(connection);
            }
        }
        @Override
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

    public static class ExecuteStatementAndUseRelativeBackward
            implements AppUnderTest, TraceMarker {
        private Connection connection;
        @Override
        public void executeApp() throws Exception {
            connection = Connections.createConnection();
            try {
                traceMarker();
            } finally {
                Connections.closeConnection(connection);
            }
        }
        @Override
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
        @Override
        public void executeApp() throws Exception {
            connection = Connections.createConnection();
            try {
                traceMarker();
            } finally {
                Connections.closeConnection(connection);
            }
        }
        @Override
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
        @Override
        public void executeApp() throws Exception {
            connection = Connections.createConnection();
            try {
                traceMarker();
            } finally {
                Connections.closeConnection(connection);
            }
        }
        @Override
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
        @Override
        public void executeApp() throws Exception {
            connection = Connections.createConnection();
            try {
                traceMarker();
            } finally {
                Connections.closeConnection(connection);
            }
        }
        @Override
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
}
