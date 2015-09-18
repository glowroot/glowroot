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
import org.glowroot.container.TransactionMarker;
import org.glowroot.container.aggregate.Query;
import org.glowroot.container.trace.Trace;

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
        Trace.Header header = container.getTraceService().getLastTrace();
        List<Query> queries = container.getAggregateService().getQueries();
        assertThat(queries).hasSize(1);
        Query query = queries.get(0);
        assertThat(query.isActive()).isFalse();
        assertThat(query.getExecutionCount()).isEqualTo(1);
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.active().or(false)).isFalse();
        assertThat(entry.message()).isEqualTo(
                "jdbc execution: select * from employee where name like ? ['john%'] => 1 row");
    }

    @Test
    public void testPreparedStatementQuery() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecutePreparedStatementQueryAndIterateOverResults.class);
        // then
        Trace.Header header = container.getTraceService().getLastTrace();
        List<Query> queries = container.getAggregateService().getQueries();
        assertThat(queries).hasSize(1);
        Query query = queries.get(0);
        assertThat(query.isActive()).isFalse();
        assertThat(query.getExecutionCount()).isEqualTo(1);
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.active().or(false)).isFalse();
        assertThat(entry.message()).isEqualTo(
                "jdbc execution: select * from employee where name like ? ['john%'] => 1 row");
    }

    @Test
    public void testPreparedStatementUpdate() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecutePreparedStatementUpdate.class);
        // then
        Trace.Header header = container.getTraceService().getLastTrace();
        List<Query> queries = container.getAggregateService().getQueries();
        assertThat(queries).hasSize(1);
        Query query = queries.get(0);
        assertThat(query.isActive()).isFalse();
        assertThat(query.getExecutionCount()).isEqualTo(1);
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.active().or(false)).isFalse();
        assertThat(entry.message())
                .isEqualTo("jdbc execution: update employee set name = ? ['nobody'] => 3 rows");
    }

    @Test
    public void testPreparedStatementLargeParamSetFirst() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecutePreparedStatementLargeParamSetFirst.class);
        // then
        Trace.Header header = container.getTraceService().getLastTrace();
        List<Query> queries = container.getAggregateService().getQueries();
        assertThat(queries).hasSize(1);
        Query query = queries.get(0);
        assertThat(query.isActive()).isFalse();
        assertThat(query.getExecutionCount()).isEqualTo(1);
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.active().or(false)).isFalse();
        assertThat(entry.message())
                .startsWith("jdbc execution: select * from employee where name like ?");
    }

    @Test
    public void testPreparedStatementNullSql() throws Exception {
        // given
        // when
        container.executeAppUnderTest(PreparedStatementNullSql.class);
        // then
        Trace.Header header = container.getTraceService().getLastTrace();
        assertThat(header.entryCount()).isZero();
    }

    @Test
    public void testPreparedStatementThrowing() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecutePreparedStatementThrowing.class);
        // then
        Trace.Header header = container.getTraceService().getLastTrace();
        List<Query> queries = container.getAggregateService().getQueries();
        assertThat(queries).hasSize(1);
        Query query = queries.get(0);
        assertThat(query.isActive()).isFalse();
        assertThat(query.getExecutionCount()).isEqualTo(1);
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.active().or(false)).isFalse();
        assertThat(entry.message())
                .isEqualTo("jdbc execution: select * from employee where name like ? ['john%']");
        assertThat(entry.error().get().message()).isEqualTo("An execute failure");
    }

    @Test
    public void testPreparedStatementWithTonsOfBindParameters() throws Exception {
        // given
        // when
        container.executeAppUnderTest(
                ExecutePreparedStatementWithTonsOfBindParametersAndIterateOverResults.class);
        // then
        Trace.Header header = container.getTraceService().getLastTrace();
        List<Query> queries = container.getAggregateService().getQueries();
        assertThat(queries).hasSize(1);
        Query query = queries.get(0);
        assertThat(query.isActive()).isFalse();
        assertThat(query.getExecutionCount()).isEqualTo(1);
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.active().or(false)).isFalse();
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

        assertThat(entry.message()).isEqualTo(sql.toString());
    }

    @Test
    public void testPreparedStatementWithoutBindParameters() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureBindParameters", false);
        // when
        container.executeAppUnderTest(ExecutePreparedStatementAndIterateOverResults.class);
        // then
        Trace.Header header = container.getTraceService().getLastTrace();
        List<Query> queries = container.getAggregateService().getQueries();
        assertThat(queries).hasSize(1);
        Query query = queries.get(0);
        assertThat(query.isActive()).isFalse();
        assertThat(query.getExecutionCount()).isEqualTo(1);
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.active().or(false)).isFalse();
        assertThat(entry.message())
                .isEqualTo("jdbc execution: select * from employee where name like ? => 1 row");
    }

    @Test
    public void testPreparedStatementWithSetNull() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureBindParameters", true);
        // whens
        container.executeAppUnderTest(ExecutePreparedStatementWithSetNull.class);
        // then
        Trace.Header header = container.getTraceService().getLastTrace();
        List<Query> queries = container.getAggregateService().getQueries();
        assertThat(queries).hasSize(1);
        Query query = queries.get(0);
        assertThat(query.isActive()).isFalse();
        assertThat(query.getExecutionCount()).isEqualTo(1);
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.active().or(false)).isFalse();
        assertThat(entry.message()).isEqualTo(
                "jdbc execution: insert into employee (name, misc) values (?, ?) [NULL, NULL]");
    }

    @Test
    public void testPreparedStatementWithBinary() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureBindParameters", true);
        // when
        container.executeAppUnderTest(ExecutePreparedStatementWithBinary.class);
        // then
        Trace.Header header = container.getTraceService().getLastTrace();
        List<Query> queries = container.getAggregateService().getQueries();
        assertThat(queries).hasSize(2);
        Query query1 = queries.get(0);
        assertThat(query1.isActive()).isFalse();
        assertThat(query1.getExecutionCount()).isEqualTo(1);
        Query query2 = queries.get(1);
        assertThat(query2.isActive()).isFalse();
        assertThat(query2.getExecutionCount()).isEqualTo(1);
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(2);
        Trace.Entry entry1 = entries.get(0);
        assertThat(entry1.active().or(false)).isFalse();
        assertThat(entry1.message()).isEqualTo(
                "jdbc execution: insert into employee (name, misc) values (?, ?) ['jane',"
                        + " 0x00010203040506070809]");
        Trace.Entry entry2 = entries.get(1);
        assertThat(entry2.active().or(false)).isFalse();
        assertThat(entry2.message()).isEqualTo(
                "jdbc execution: insert /**/ into employee (name, misc) values (?, ?) ['jane',"
                        + " {10 bytes}]");
    }

    @Test
    public void testPreparedStatementWithBinaryUsingSetObject() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureBindParameters", true);
        // when
        container.executeAppUnderTest(ExecutePreparedStatementWithBinaryUsingSetObject.class);
        // then
        Trace.Header header = container.getTraceService().getLastTrace();
        List<Query> queries = container.getAggregateService().getQueries();
        assertThat(queries).hasSize(2);
        Query query1 = queries.get(0);
        assertThat(query1.isActive()).isFalse();
        assertThat(query1.getExecutionCount()).isEqualTo(1);
        Query query2 = queries.get(1);
        assertThat(query2.isActive()).isFalse();
        assertThat(query2.getExecutionCount()).isEqualTo(1);
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(2);
        Trace.Entry entry1 = entries.get(0);
        assertThat(entry1.active().or(false)).isFalse();
        assertThat(entry1.message()).isEqualTo(
                "jdbc execution: insert into employee (name, misc) values (?, ?) ['jane',"
                        + " 0x00010203040506070809]");
        Trace.Entry entry2 = entries.get(1);
        assertThat(entry2.active().or(false)).isFalse();
        assertThat(entry2.message()).isEqualTo(
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
        Trace.Header header = container.getTraceService().getLastTrace();
        List<Query> queries = container.getAggregateService().getQueries();
        assertThat(queries).hasSize(1);
        Query query = queries.get(0);
        assertThat(query.isActive()).isFalse();
        assertThat(query.getExecutionCount()).isEqualTo(1);
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.active().or(false)).isFalse();
        assertThat(entry.message()).isEqualTo(
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
        Trace.Header header = container.getTraceService().getLastTrace();
        List<Query> queries = container.getAggregateService().getQueries();
        assertThat(queries).hasSize(1);
        Query query = queries.get(0);
        assertThat(query.isActive()).isFalse();
        assertThat(query.getExecutionCount()).isEqualTo(1);
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.active().or(false)).isFalse();
        assertThat(entry.message()).isEqualTo(
                "jdbc execution: insert into employee (name, misc2) values (?, ?) ['jane',"
                        + " {stream:StringReader}]");
    }

    @Test
    public void testPreparedStatementWithClear() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureBindParameters", true);
        // when
        container.executeAppUnderTest(ExecutePreparedStatementWithClear.class);
        // then
        Trace.Header header = container.getTraceService().getLastTrace();
        List<Query> queries = container.getAggregateService().getQueries();
        assertThat(queries).hasSize(1);
        Query query = queries.get(0);
        assertThat(query.isActive()).isFalse();
        assertThat(query.getExecutionCount()).isEqualTo(1);
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.active().or(false)).isFalse();
        assertThat(entry.message()).isEqualTo(
                "jdbc execution: select * from employee where name like ? ['john%'] => 1 row");
    }

    @Test
    public void testPreparedStatementThatHasInternalGlowrootToken() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecutePreparedStatementThatHasInternalGlowrootToken.class);
        // then
        Trace.Header header = container.getTraceService().getLastTrace();
        List<Query> queries = container.getAggregateService().getQueries();
        assertThat(queries).hasSize(1);
        Query query = queries.get(0);
        assertThat(query.isActive()).isFalse();
        assertThat(query.getExecutionCount()).isEqualTo(1);
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.active().or(false)).isFalse();
        assertThat(entry.message()).isEqualTo(
                "jdbc execution: select * from employee where name like ? ['{}'] => 0 rows");
    }

    public static class ExecutePreparedStatementAndIterateOverResults
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

    public static class ExecutePreparedStatementQueryAndIterateOverResults
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
            PreparedStatement preparedStatement =
                    connection.prepareStatement("select * from employee where name like ?");
            try {
                preparedStatement.setString(1, "john%");
                ResultSet rs = preparedStatement.executeQuery();
                while (rs.next()) {
                    rs.getString(1);
                }
            } finally {
                preparedStatement.close();
            }
        }
    }

    public static class ExecutePreparedStatementUpdate implements AppUnderTest, TransactionMarker {
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
            PreparedStatement preparedStatement =
                    connection.prepareStatement("update employee set name = ?");
            try {
                preparedStatement.setString(1, "nobody");
                preparedStatement.executeUpdate();
            } finally {
                preparedStatement.close();
            }
        }
    }

    public static class ExecutePreparedStatementLargeParamSetFirst
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
            String sql = "select * from employee where name like ?";
            for (int i = 0; i < 99; i++) {
                sql += " and name like ?";
            }
            PreparedStatement preparedStatement = connection.prepareStatement(sql);
            try {
                preparedStatement.setString(100, "john%");
                for (int i = 0; i < 99; i++) {
                    preparedStatement.setString(i + 1, "john%");
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

    public static class PreparedStatementNullSql implements AppUnderTest, TransactionMarker {
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
                transactionMarker();
            } finally {
                Connections.closeConnection(connection);
            }
        }
        @Override
        public void transactionMarker() throws Exception {
            delegatingConnection.prepareStatement(null);
        }
    }

    public static class ExecutePreparedStatementThrowing
            implements AppUnderTest, TransactionMarker {
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
                transactionMarker();
            } finally {
                Connections.closeConnection(connection);
            }
        }
        @Override
        public void transactionMarker() throws Exception {
            PreparedStatement preparedStatement = delegatingConnection
                    .prepareStatement("select * from employee where name like ?");
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

    public static class ExecutePreparedStatementWithSetNull
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

    public static class ExecutePreparedStatementWithBinary
            implements AppUnderTest, TransactionMarker {
        static {
            JdbcPluginProperties.setDisplayBinaryParameterAsHex(
                    "insert into employee (name, misc) values (?, ?)", 2);
        }
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
            byte[] bytes = new byte[10];
            for (int i = 0; i < 10; i++) {
                bytes[i] = (byte) i;
            }
            PreparedStatement preparedStatement =
                    connection.prepareStatement("insert into employee (name, misc) values (?, ?)");
            PreparedStatement preparedStatement2 = connection
                    .prepareStatement("insert /**/ into employee (name, misc) values (?, ?)");
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

    public static class ExecutePreparedStatementWithBinaryUsingSetObject
            implements AppUnderTest, TransactionMarker {
        static {
            JdbcPluginProperties.setDisplayBinaryParameterAsHex(
                    "insert into employee (name, misc) values (?, ?)", 2);
        }
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
            byte[] bytes = new byte[10];
            for (int i = 0; i < 10; i++) {
                bytes[i] = (byte) i;
            }
            PreparedStatement preparedStatement =
                    connection.prepareStatement("insert into employee (name, misc) values (?, ?)");
            PreparedStatement preparedStatement2 = connection
                    .prepareStatement("insert /**/ into employee (name, misc) values (?, ?)");
            try {
                preparedStatement.setString(1, "jane");
                preparedStatement.setObject(2, bytes);
                preparedStatement.execute();
                preparedStatement2.setString(1, "jane");
                preparedStatement2.setObject(2, bytes);
                preparedStatement2.execute();
            } finally {
                preparedStatement.close();
                preparedStatement2.close();
            }
        }
    }

    public static class ExecutePreparedStatementWithBinaryStream
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

    public static class ExecutePreparedStatementWithCharacterStream
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

    public static class ExecutePreparedStatementWithClear
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
            PreparedStatement preparedStatement =
                    connection.prepareStatement("select * from employee where name like ?");
            try {
                preparedStatement.setString(1, "na%");
                preparedStatement.clearParameters();
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

    public static class ExecutePreparedStatementThatHasInternalGlowrootToken
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
            PreparedStatement preparedStatement =
                    connection.prepareStatement("select * from employee where name like ?");
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
