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
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import com.google.common.collect.Sets;
import org.apache.commons.dbcp.BasicDataSource;
import org.apache.commons.dbcp.DelegatingConnection;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.agent.it.harness.trace.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class ConnectionAndTxLifecycleTest {

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
    public void testConnectionLifecycle() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID,
                "captureConnectionLifecycleTraceEntries", true);
        // when
        container.executeAppUnderTest(ExecuteGetConnectionAndConnectionClose.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(2);
        Trace.Entry entry1 = entries.get(0);
        assertThat(entry1.message()).isEqualTo("jdbc get connection");
        Trace.Entry entry2 = entries.get(1);
        assertThat(entry2.message()).isEqualTo("jdbc connection close");
    }

    @Test
    public void testConnectionLifecycleDisabled() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureGetConnection", false);
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureConnectionClose", false);
        // when
        container.executeAppUnderTest(ExecuteGetConnectionAndConnectionClose.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header.rootTimer().childTimers()).isEmpty();
        assertThat(header.entryCount()).isZero();
    }

    @Test
    public void testConnectionLifecyclePartiallyDisabled() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteGetConnectionAndConnectionClose.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header.rootTimer().childTimers()).hasSize(2);
        // ordering is by total desc, so order is not fixed
        Set<String> childTimerNames = Sets.newHashSet();
        childTimerNames.add(header.rootTimer().childTimers().get(0).name());
        childTimerNames.add(header.rootTimer().childTimers().get(1).name());
        assertThat(childTimerNames).containsOnly("jdbc get connection", "jdbc connection close");
        assertThat(header.entryCount()).isZero();
    }

    @Test
    public void testConnectionLifecycleGetConnectionThrows() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID,
                "captureConnectionLifecycleTraceEntries", true);
        // when
        container.executeAppUnderTest(ExecuteGetConnectionOnThrowingDataSource.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.message()).isEqualTo("jdbc get connection");
        assertThat(entry.error().get().message()).isEqualTo("A getconnection failure");
    }

    @Test
    public void testConnectionLifecycleGetConnectionThrowsDisabled() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureGetConnection", false);
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureConnectionClose", false);
        // when
        container.executeAppUnderTest(ExecuteGetConnectionOnThrowingDataSource.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header.rootTimer().childTimers()).isEmpty();
        assertThat(header.entryCount()).isZero();
    }

    @Test
    public void testConnectionLifecycleGetConnectionThrowsPartiallyDisabled() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteGetConnectionOnThrowingDataSource.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header.rootTimer().childTimers()).hasSize(1);
        assertThat(header.rootTimer().childTimers().get(0).name())
                .isEqualTo("jdbc get connection");
        assertThat(header.entryCount()).isZero();
    }

    @Test
    public void testConnectionLifecycleCloseConnectionThrows() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID,
                "captureConnectionLifecycleTraceEntries", true);
        // when
        container.executeAppUnderTest(ExecuteCloseConnectionOnThrowingDataSource.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(2);
        Trace.Entry entry1 = entries.get(0);
        assertThat(entry1.message()).isEqualTo("jdbc get connection");
        Trace.Entry entry2 = entries.get(1);
        assertThat(entry2.message()).isEqualTo("jdbc connection close");
        assertThat(entry2.error().get().message()).isEqualTo("A close failure");
    }

    @Test
    public void testConnectionLifecycleCloseConnectionThrowsDisabled() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureGetConnection", false);
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureConnectionClose", false);
        // when
        container.executeAppUnderTest(ExecuteCloseConnectionOnThrowingDataSource.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header.rootTimer().childTimers()).isEmpty();
        assertThat(header.entryCount()).isZero();
    }

    @Test
    public void testConnectionLifecycleCloseConnectionThrowsPartiallyDisabled() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteCloseConnectionOnThrowingDataSource.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header.rootTimer().childTimers()).hasSize(2);
        // ordering is by total desc, so order is not fixed
        Set<String> childTimerNames = Sets.newHashSet();
        childTimerNames.add(header.rootTimer().childTimers().get(0).name());
        childTimerNames.add(header.rootTimer().childTimers().get(1).name());
        assertThat(childTimerNames).containsOnly("jdbc get connection", "jdbc connection close");
        assertThat(header.entryCount()).isZero();
    }

    @Test
    public void testTransactionLifecycle() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID,
                "captureTransactionLifecycleTraceEntries", true);
        // when
        container.executeAppUnderTest(ExecuteSetAutoCommit.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries.size()).isBetween(2, 3);
        Trace.Entry entry1 = entries.get(0);
        assertThat(entry1.message()).isEqualTo("jdbc set autocommit: false");
        Trace.Entry entry2 = entries.get(1);
        assertThat(entry2.message()).isEqualTo("jdbc set autocommit: true");
        if (entries.size() == 3) {
            Trace.Entry entry3 = entries.get(2);
            assertThat(entry3.message()).isEqualTo("jdbc commit");
        }
    }

    @Test
    public void testTransactionLifecycleThrowing() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID,
                "captureTransactionLifecycleTraceEntries", true);
        // when
        container.executeAppUnderTest(ExecuteSetAutoCommitThrowing.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(2);
        Trace.Entry entry1 = entries.get(0);
        assertThat(entry1.message()).isEqualTo("jdbc set autocommit: false");
        assertThat(entry1.error().get().message()).isEqualTo("A setautocommit failure");
        Trace.Entry entry2 = entries.get(1);
        assertThat(entry2.message()).isEqualTo("jdbc set autocommit: true");
        assertThat(entry2.error().get().message()).isEqualTo("A setautocommit failure");
    }

    @Test
    public void testConnectionLifecycleAndTransactionLifecycleTogether() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID,
                "captureConnectionLifecycleTraceEntries", true);
        container.getConfigService().setPluginProperty(PLUGIN_ID,
                "captureTransactionLifecycleTraceEntries", true);
        // when
        container.executeAppUnderTest(ExecuteGetConnectionAndConnectionClose.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(2);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.message()).isEqualTo("jdbc get connection (autocommit: true)");
    }

    public static class ExecuteGetConnectionAndConnectionClose
            implements AppUnderTest, TransactionMarker {
        private BasicDataSource dataSource;
        @Override
        public void executeApp() throws Exception {
            dataSource = new BasicDataSource();
            dataSource.setDriverClassName("org.hsqldb.jdbc.JDBCDriver");
            dataSource.setUrl("jdbc:hsqldb:mem:test");
            // BasicDataSource opens and closes a test connection on first getConnection(),
            // so just getting that out of the way before starting transaction
            dataSource.getConnection().close();
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws Exception {
            dataSource.getConnection().close();
        }
    }

    public static class ExecuteGetConnectionOnThrowingDataSource
            implements AppUnderTest, TransactionMarker {
        private BasicDataSource dataSource;
        @Override
        public void executeApp() throws Exception {
            dataSource = new BasicDataSource() {
                @Override
                public Connection getConnection() throws SQLException {
                    throw new SQLException("A getconnection failure");
                }
            };
            dataSource.setDriverClassName("org.hsqldb.jdbc.JDBCDriver");
            dataSource.setUrl("jdbc:hsqldb:mem:test");
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws Exception {
            try {
                dataSource.getConnection();
            } catch (SQLException e) {
            }
        }
    }

    public static class ExecuteCloseConnectionOnThrowingDataSource
            implements AppUnderTest, TransactionMarker {
        private BasicDataSource dataSource;
        @Override
        public void executeApp() throws Exception {
            dataSource = new BasicDataSource() {
                private boolean first = true;
                @Override
                public Connection getConnection() throws SQLException {
                    if (first) {
                        // BasicDataSource opens and closes a test connection on first
                        // getConnection()
                        first = false;
                        return super.getConnection();
                    }
                    return new DelegatingConnection(super.getConnection()) {
                        @Override
                        public void close() throws SQLException {
                            throw new SQLException("A close failure");
                        }
                    };
                }
            };
            dataSource.setDriverClassName("org.hsqldb.jdbc.JDBCDriver");
            dataSource.setUrl("jdbc:hsqldb:mem:test");
            // BasicDataSource opens and closes a test connection on first getConnection(),
            // so just getting that out of the way before starting transaction
            dataSource.getConnection().close();
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws Exception {
            try {
                dataSource.getConnection().close();
            } catch (SQLException e) {
            }
        }
    }

    public static class ExecuteSetAutoCommit implements AppUnderTest, TransactionMarker {
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
            connection.setAutoCommit(false);
            connection.setAutoCommit(true);
        }
    }

    public static class ExecuteSetAutoCommitThrowing implements AppUnderTest, TransactionMarker {
        private Connection connection;
        @Override
        public void executeApp() throws Exception {
            connection = new DelegatingConnection(Connections.createConnection()) {
                @Override
                public void setAutoCommit(boolean autoCommit) throws SQLException {
                    throw new SQLException("A setautocommit failure");
                }
            };
            try {
                transactionMarker();
            } finally {
                Connections.closeConnection(connection);
            }
        }
        @Override
        public void transactionMarker() {
            try {
                connection.setAutoCommit(false);
            } catch (SQLException e) {
            }
            try {
                connection.setAutoCommit(true);
            } catch (SQLException e) {
            }
        }
    }
}
