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
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.apache.commons.dbcp.DelegatingConnection;
import org.apache.commons.dbcp.DelegatingStatement;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.agent.it.harness.aggregate.Query;
import org.glowroot.agent.it.harness.trace.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class BatchTest {

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
    public void testBatchPreparedStatement() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteBatchPreparedStatement.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        List<Query> queries = container.getAggregateService().getQueries();
        assertThat(queries).hasSize(1);
        Query query = queries.get(0);
        assertThat(query.getQueryText()).isEqualTo("insert into employee (name) values (?)");
        assertThat(query.getExecutionCount()).isEqualTo(5);
        assertThat(query.getTotalRows()).isEqualTo(5);
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).message()).isEqualTo("jdbc execution: 3 x"
                + " insert into employee (name) values (?) ['huckle'] ['sally'] ['sally']"
                + " => 3 rows");
        assertThat(entries.get(1).message()).isEqualTo("jdbc execution: 2 x"
                + " insert into employee (name) values (?) ['lowly'] ['pig will'] => 2 rows");
    }

    @Test
    public void testBatchPreparedStatementWithoutCaptureBindParams() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureBindParameters", false);
        // when
        container.executeAppUnderTest(ExecuteBatchPreparedStatement.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        List<Query> queries = container.getAggregateService().getQueries();
        assertThat(queries).hasSize(1);
        Query query = queries.get(0);
        assertThat(query.getQueryText()).isEqualTo("insert into employee (name) values (?)");
        assertThat(query.getExecutionCount()).isEqualTo(5);
        assertThat(query.getTotalRows()).isEqualTo(5);
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).message()).isEqualTo(
                "jdbc execution: 3 x" + " insert into employee (name) values (?) => 3 rows");
        assertThat(entries.get(1).message()).isEqualTo(
                "jdbc execution: 2 x" + " insert into employee (name) values (?) => 2 rows");
    }

    @Test
    public void testBatchPreparedStatementWithoutClear() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteBatchPreparedStatementWithoutClear.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        List<Query> queries = container.getAggregateService().getQueries();
        assertThat(queries).hasSize(1);
        Query query = queries.get(0);
        assertThat(query.getQueryText()).isEqualTo("insert into employee (name) values (?)");
        assertThat(query.getExecutionCount()).isEqualTo(4);
        assertThat(query.getTotalRows()).isEqualTo(4);
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).message()).isEqualTo("jdbc execution: 2 x"
                + " insert into employee (name) values (?) ['huckle'] ['sally'] => 2 rows");
        assertThat(entries.get(1).message()).isEqualTo("jdbc execution: 2 x"
                + " insert into employee (name) values (?) ['lowly'] ['pig will'] => 2 rows");
    }

    @Test
    public void testBatchPreparedStatementWithoutClearWithoutCaptureBindParams() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureBindParameters", false);
        // when
        container.executeAppUnderTest(ExecuteBatchPreparedStatementWithoutClear.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        List<Query> queries = container.getAggregateService().getQueries();
        assertThat(queries).hasSize(1);
        Query query = queries.get(0);
        assertThat(query.getQueryText()).isEqualTo("insert into employee (name) values (?)");
        assertThat(query.getExecutionCount()).isEqualTo(4);
        assertThat(query.getTotalRows()).isEqualTo(4);
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).message()).isEqualTo(
                "jdbc execution: 2 x" + " insert into employee (name) values (?) => 2 rows");
        assertThat(entries.get(1).message()).isEqualTo(
                "jdbc execution: 2 x" + " insert into employee (name) values (?) => 2 rows");
    }

    @Test
    public void testBatchStatement() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteBatchStatement.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        List<Query> queries = container.getAggregateService().getQueries();
        assertThat(queries).hasSize(1);
        Query query = queries.get(0);
        assertThat(query.getQueryText()).isEqualTo("<batch sql>");
        assertThat(query.getExecutionCount()).isEqualTo(2);
        assertThat(query.getTotalRows()).isEqualTo(4);
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).message())
                .isEqualTo("jdbc execution:" + " insert into employee (name) values ('huckle'),"
                        + " insert into employee (name) values ('sally') => 2 rows");
        assertThat(entries.get(1).message())
                .isEqualTo("jdbc execution:" + " insert into employee (name) values ('lowly'),"
                        + " insert into employee (name) values ('pig will') => 2 rows");
    }

    @Test
    public void testBatchStatementNull() throws Exception {
        // given
        // when
        container.executeAppUnderTest(BatchStatementNull.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        List<Query> queries = container.getAggregateService().getQueries();
        assertThat(queries).hasSize(1);
        Query query = queries.get(0);
        assertThat(query.getQueryText()).isEqualTo("<batch sql>");
        assertThat(query.getExecutionCount()).isEqualTo(1);
        assertThat(query.getTotalRows()).isEqualTo(1);
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).message())
                .isEqualTo("jdbc execution: insert into employee (name) values ('1') => 1 row");
    }

    @Test
    public void testBatchStatementWithNoBatches() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteBatchStatementWithNoBatches.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        List<Query> queries = container.getAggregateService().getQueries();
        assertThat(queries).hasSize(1);
        Query query = queries.get(0);
        assertThat(query.getQueryText()).isEqualTo("<batch sql>");
        assertThat(query.getExecutionCount()).isEqualTo(1);
        assertThat(query.getTotalRows()).isEqualTo(0);
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).message())
                .isEqualTo("jdbc execution: (empty batch) => 0 rows");
    }

    @Test
    public void testBatchStatementWithoutClear() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteBatchStatementWithoutClear.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        List<Query> queries = container.getAggregateService().getQueries();
        assertThat(queries).hasSize(1);
        Query query = queries.get(0);
        assertThat(query.getQueryText()).isEqualTo("<batch sql>");
        assertThat(query.getExecutionCount()).isEqualTo(2);
        assertThat(query.getTotalRows()).isEqualTo(4);
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).message())
                .isEqualTo("jdbc execution:" + " insert into employee (name) values ('huckle'),"
                        + " insert into employee (name) values ('sally') => 2 rows");
        assertThat(entries.get(1).message())
                .isEqualTo("jdbc execution:" + " insert into employee (name) values ('lowly'),"
                        + " insert into employee (name) values ('pig will') => 2 rows");
    }

    public static class ExecuteBatchPreparedStatement implements AppUnderTest, TransactionMarker {
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
            PreparedStatement preparedStatement =
                    connection.prepareStatement("insert into employee (name) values (?)");
            try {
                preparedStatement.setString(1, "huckle");
                preparedStatement.addBatch();
                preparedStatement.setString(1, "sally");
                preparedStatement.addBatch();
                // add batch without re-setting params
                preparedStatement.addBatch();
                preparedStatement.executeBatch();
                preparedStatement.clearBatch();
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

    public static class ExecuteBatchPreparedStatementWithoutClear
            implements AppUnderTest, TransactionMarker {
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

    public static class ExecuteBatchStatement implements AppUnderTest, TransactionMarker {
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

    public static class BatchStatementNull implements AppUnderTest, TransactionMarker {
        private Connection delegatingConnection;
        @Override
        public void executeApp() throws Exception {
            Connection connection = Connections.createConnection();
            delegatingConnection = new DelegatingConnection(connection) {
                @Override
                public Statement createStatement() throws SQLException {
                    return new DelegatingStatement(this, super.createStatement()) {
                        @Override
                        public void addBatch(String sql) throws SQLException {
                            super.addBatch("insert into employee (name) values ('1')");
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
            Statement statement = delegatingConnection.createStatement();
            try {
                statement.addBatch(null);
                statement.executeBatch();
            } finally {
                statement.close();
            }
        }
    }

    public static class ExecuteBatchStatementWithNoBatches
            implements AppUnderTest, TransactionMarker {
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
            Statement statement = connection.createStatement();
            try {
                statement.executeBatch();
            } finally {
                statement.close();
            }
        }
    }

    public static class ExecuteBatchStatementWithoutClear
            implements AppUnderTest, TransactionMarker {
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
}
