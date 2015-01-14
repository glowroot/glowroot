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

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

import org.apache.commons.dbcp.DelegatingConnection;
import org.apache.commons.dbcp.DelegatingPreparedStatement;
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

public class PreparedStatementTest {

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
    public void testPreparedStatement() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecutePreparedStatementAndIterateOverResults.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(2);
        TraceEntry jdbcEntry = entries.get(1);
        assertThat(jdbcEntry.getMessage().getText()).isEqualTo(
                "jdbc execution: select * from employee where name like ? ['john%'] => 1 row");
    }

    @Test
    public void testPreparedStatementNullSql() throws Exception {
        // given
        // when
        container.executeAppUnderTest(PreparedStatementNullSql.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
    }

    @Test
    public void testPreparedStatementThrowing() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecutePreparedStatementThrowing.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(2);
        TraceEntry jdbcEntry = entries.get(1);
        assertThat(jdbcEntry.getMessage().getText()).isEqualTo(
                "jdbc execution: select * from employee where name like ? ['john%']");
        assertThat(jdbcEntry.getError().getText()).isEqualTo(
                "java.sql.SQLException: An execute failure");
    }

    @Test
    public void testPreparedStatementWithTonsOfBindParameters() throws Exception {
        // given
        // when
        container.executeAppUnderTest(
                ExecutePreparedStatementWithTonsOfBindParametersAndIterateOverResults.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(2);
        TraceEntry jdbcEntry = entries.get(1);
        StringBuilder sql =
                new StringBuilder("jdbc execution: select * from employee where name like ?");
        for (int i = 0; i < 200; i++) {
            sql.append(" and name like ?");
        }
        sql.append(" ['john%'");
        for (int i = 0; i < 200; i++) {
            sql.append(", 'john%'");
        }
        sql.append("] => 1 row");

        assertThat(jdbcEntry.getMessage().getText()).isEqualTo(sql.toString());
    }

    @Test
    public void testPreparedStatementWithoutBindParameters() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureBindParameters", false);
        // when
        container.executeAppUnderTest(ExecutePreparedStatementAndIterateOverResults.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(2);
        TraceEntry jdbcEntry = entries.get(1);
        assertThat(jdbcEntry.getMessage().getText()).isEqualTo(
                "jdbc execution: select * from employee where name like ? => 1 row");
    }

    @Test
    public void testPreparedStatementWithSetNull() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureBindParameters", true);
        // whens
        container.executeAppUnderTest(ExecutePreparedStatementWithSetNull.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(2);
        TraceEntry jdbcEntry = entries.get(1);
        assertThat(jdbcEntry.getMessage().getText()).isEqualTo(
                "jdbc execution: insert into employee (name, misc) values (?, ?) [NULL, NULL]");
    }

    @Test
    public void testPreparedStatementWithBinary() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureBindParameters", true);
        // when
        container.executeAppUnderTest(ExecutePreparedStatementWithBinary.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(3);
        TraceEntry jdbcEntry = entries.get(1);
        assertThat(jdbcEntry.getMessage().getText()).isEqualTo(
                "jdbc execution: insert into employee (name, misc) values (?, ?) ['jane',"
                        + " 0x00010203040506070809]");
        jdbcEntry = entries.get(2);
        assertThat(jdbcEntry.getMessage().getText()).isEqualTo(
                "jdbc execution: insert /**/ into employee (name, misc) values (?, ?) ['jane',"
                        + " {10 bytes}]");
    }

    @Test
    public void testPreparedStatementWithBinaryStream() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureBindParameters", true);
        // when
        container.executeAppUnderTest(ExecutePreparedStatementWithBinaryStream.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(2);
        TraceEntry jdbcEntry = entries.get(1);
        assertThat(jdbcEntry.getMessage().getText()).isEqualTo(
                "jdbc execution: insert into employee (name, misc) values (?, ?) ['jane',"
                        + " {stream:ByteArrayInputStream}]");
    }

    @Test
    public void testPreparedStatementWithCharacterStream() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureBindParameters", true);
        // when
        container.executeAppUnderTest(ExecutePreparedStatementWithCharacterStream.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(2);
        TraceEntry jdbcEntry = entries.get(1);
        assertThat(jdbcEntry.getMessage().getText()).isEqualTo(
                "jdbc execution: insert into employee (name, misc2) values (?, ?) ['jane',"
                        + " {stream:StringReader}]");
    }

    @Test
    public void testPreparedStatementThatHasInternalGlowrootToken() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecutePreparedStatementThatHasInternalGlowrootToken.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(2);
        TraceEntry jdbcEntry = entries.get(1);
        assertThat(jdbcEntry.getMessage().getText()).isEqualTo(
                "jdbc execution: select * from employee where name like ? ['{}'] => 0 rows");
    }

    public static class ExecutePreparedStatementAndIterateOverResults implements AppUnderTest,
            TraceMarker {
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

    public static class PreparedStatementNullSql implements AppUnderTest, TraceMarker {
        private Connection delegatingConnection;
        @Override
        public void executeApp() throws Exception {
            Connection connection = Connections.createConnection();
            delegatingConnection = new DelegatingConnection(connection) {
                @Override
                public PreparedStatement prepareStatement(String sql) throws SQLException {
                    return super.prepareStatement("select 1 from employee");
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
            delegatingConnection.prepareStatement(null);
        }
    }

    public static class ExecutePreparedStatementThrowing implements AppUnderTest, TraceMarker {
        private Connection delegatingConnection;
        @Override
        public void executeApp() throws Exception {
            Connection connection = Connections.createConnection();
            delegatingConnection = new DelegatingConnection(connection) {
                @Override
                public PreparedStatement prepareStatement(String sql) throws SQLException {
                    return new DelegatingPreparedStatement(this, super.prepareStatement(sql)) {
                        @Override
                        public boolean execute() throws SQLException {
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
            PreparedStatement preparedStatement = delegatingConnection.prepareStatement(
                    "select * from employee where name like ?");
            try {
                preparedStatement.setString(1, "john%");
                preparedStatement.execute();
            } catch (SQLException e) {
            } finally {
                preparedStatement.close();
            }
        }
    }

    public static class ExecutePreparedStatementWithTonsOfBindParametersAndIterateOverResults
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
            StringBuilder sql = new StringBuilder("select * from employee where name like ?");
            for (int i = 0; i < 200; i++) {
                sql.append(" and name like ?");
            }
            PreparedStatement preparedStatement = connection.prepareStatement(sql.toString());
            try {
                for (int i = 1; i < 202; i++) {
                    preparedStatement.setString(i, "john%");
                }
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
            PreparedStatement preparedStatement =
                    connection.prepareStatement("insert into employee (name, misc) values (?, ?)");
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
                    "insert into employee (name, misc) values (?, ?)", 2);
        }
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
            byte[] bytes = new byte[10];
            for (int i = 0; i < 10; i++) {
                bytes[i] = (byte) i;
            }
            PreparedStatement preparedStatement =
                    connection.prepareStatement("insert into employee (name, misc) values (?, ?)");
            PreparedStatement preparedStatement2 = connection.prepareStatement(
                    "insert /**/ into employee (name, misc) values (?, ?)");
            try {
                preparedStatement.setString(1, "jane");
                preparedStatement.setBytes(2, bytes);
                preparedStatement.execute();
                preparedStatement2.setString(1, "jane");
                preparedStatement2.setBytes(2, bytes);
                preparedStatement2.execute();
            } finally {
                preparedStatement.close();
                preparedStatement2.close();
            }
        }
    }

    public static class ExecutePreparedStatementWithBinaryStream implements AppUnderTest,
            TraceMarker {
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
            byte[] bytes = new byte[10];
            for (int i = 0; i < 10; i++) {
                bytes[i] = (byte) i;
            }
            PreparedStatement preparedStatement =
                    connection.prepareStatement("insert into employee (name, misc) values (?, ?)");
            try {
                preparedStatement.setString(1, "jane");
                preparedStatement.setBinaryStream(2, new ByteArrayInputStream(bytes));
                preparedStatement.execute();
            } finally {
                preparedStatement.close();
            }
        }
    }

    public static class ExecutePreparedStatementWithCharacterStream implements AppUnderTest,
            TraceMarker {
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
            PreparedStatement preparedStatement =
                    connection.prepareStatement("insert into employee (name, misc2) values (?, ?)");
            try {
                preparedStatement.setString(1, "jane");
                preparedStatement.setCharacterStream(2, new StringReader("abc"));
                preparedStatement.execute();
            } finally {
                preparedStatement.close();
            }
        }
    }

    public static class ExecutePreparedStatementThatHasInternalGlowrootToken implements
            AppUnderTest, TraceMarker {
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
            PreparedStatement preparedStatement = connection.prepareStatement(
                    "select * from employee where name like ?");
            try {
                preparedStatement.setString(1, "{}");
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

}
