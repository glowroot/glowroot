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
import org.glowroot.wire.api.model.ConfigOuterClass.Config.PluginConfig;
import org.glowroot.wire.api.model.ConfigOuterClass.Config.PluginProperty;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class BatchIT {

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
        Trace trace = container.execute(ExecuteBatchPreparedStatement.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).getMessage()).isEqualTo("jdbc execution: 3 x"
                + " insert into employee (name) values (?) ['huckle'] ['sally'] ['sally']"
                + " => 3 rows");
        assertThat(entries.get(1).getMessage()).isEqualTo("jdbc execution: 2 x"
                + " insert into employee (name) values (?) ['lowly'] ['pig will'] => 2 rows");
    }

    @Test
    public void testBatchPreparedStatementWithoutCaptureBindParams() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureBindParameters", false);
        // when
        Trace trace = container.execute(ExecuteBatchPreparedStatement.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).getMessage())
                .isEqualTo("jdbc execution: 3 x insert into employee (name) values (?) => 3 rows");
        assertThat(entries.get(1).getMessage())
                .isEqualTo("jdbc execution: 2 x insert into employee (name) values (?) => 2 rows");
    }

    @Test
    public void testBatchPreparedStatementWithoutClear() throws Exception {
        // given
        // when
        Trace trace = container.execute(ExecuteBatchPreparedStatementWithoutClear.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).getMessage()).isEqualTo("jdbc execution: 2 x"
                + " insert into employee (name) values (?) ['huckle'] ['sally'] => 2 rows");
        assertThat(entries.get(1).getMessage()).isEqualTo("jdbc execution: 2 x"
                + " insert into employee (name) values (?) ['lowly'] ['pig will'] => 2 rows");
    }

    @Test
    public void testBatchPreparedStatementWithoutClearWithoutCaptureBindParams() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureBindParameters", false);
        container.getConfigService().updatePluginConfig(PluginConfig.newBuilder()
                .setId(PLUGIN_ID)
                .addProperty(PluginProperty.newBuilder()
                        .setName("captureBindParameters")
                        .setBval(false))
                .build());
        // when
        Trace trace = container.execute(ExecuteBatchPreparedStatementWithoutClear.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).getMessage())
                .isEqualTo("jdbc execution: 2 x insert into employee (name) values (?) => 2 rows");
        assertThat(entries.get(1).getMessage())
                .isEqualTo("jdbc execution: 2 x insert into employee (name) values (?) => 2 rows");
    }

    @Test
    public void testBatchStatement() throws Exception {
        // given
        // when
        Trace trace = container.execute(ExecuteBatchStatement.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).getMessage())
                .isEqualTo("jdbc execution: insert into employee (name) values ('huckle'),"
                        + " insert into employee (name) values ('sally') => 2 rows");
        assertThat(entries.get(1).getMessage())
                .isEqualTo("jdbc execution: insert into employee (name) values ('lowly'),"
                        + " insert into employee (name) values ('pig will') => 2 rows");
    }

    @Test
    public void testBatchStatementNull() throws Exception {
        // given
        // when
        Trace trace = container.execute(BatchStatementNull.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getMessage())
                .isEqualTo("jdbc execution: insert into employee (name) values ('1') => 1 row");
    }

    @Test
    public void testBatchStatementWithNoBatches() throws Exception {
        // given
        // when
        Trace trace = container.execute(ExecuteBatchStatementWithNoBatches.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getMessage())
                .isEqualTo("jdbc execution: (empty batch) => 0 rows");
    }

    @Test
    public void testBatchStatementWithoutClear() throws Exception {
        // given
        // when
        Trace trace = container.execute(ExecuteBatchStatementWithoutClear.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).getMessage())
                .isEqualTo("jdbc execution: insert into employee (name) values ('huckle'),"
                        + " insert into employee (name) values ('sally') => 2 rows");
        assertThat(entries.get(1).getMessage())
                .isEqualTo("jdbc execution: insert into employee (name) values ('lowly'),"
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
