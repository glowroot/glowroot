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
import java.sql.SQLException;
import java.util.List;

import org.apache.commons.dbcp.BasicDataSource;
import org.apache.commons.dbcp.DelegatingConnection;
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
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(2);
        TraceEntry entry1 = entries.get(0);
        assertThat(entry1.getMessage().getText()).isEqualTo("jdbc get connection");
        TraceEntry entry2 = entries.get(1);
        assertThat(entry2.getMessage().getText()).isEqualTo("jdbc connection close");
    }

    @Test
    public void testConnectionLifecycleDisabled() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureGetConnection", false);
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureConnectionClose", false);
        // when
        container.executeAppUnderTest(ExecuteGetConnectionAndConnectionClose.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getRootTimer().getNestedTimerNames()).isEmpty();
        assertThat(trace.getEntryCount()).isZero();
    }

    @Test
    public void testConnectionLifecyclePartiallyDisabled() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteGetConnectionAndConnectionClose.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getRootTimer().getNestedTimerNames()).contains("jdbc get connection");
        assertThat(trace.getRootTimer().getNestedTimerNames()).contains("jdbc connection close");
        assertThat(trace.getEntryCount()).isZero();
    }

    @Test
    public void testConnectionLifecycleGetConnectionThrows() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID,
                "captureConnectionLifecycleTraceEntries", true);
        // when
        container.executeAppUnderTest(ExecuteGetConnectionOnThrowingDataSource.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        TraceEntry entry = entries.get(0);
        assertThat(entry.getMessage().getText()).isEqualTo("jdbc get connection");
        assertThat(entry.getError().getMessage())
                .isEqualTo("java.sql.SQLException: A getconnection failure");
    }

    @Test
    public void testConnectionLifecycleGetConnectionThrowsDisabled() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureGetConnection", false);
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureConnectionClose", false);
        // when
        container.executeAppUnderTest(ExecuteGetConnectionOnThrowingDataSource.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getRootTimer().getNestedTimerNames()).isEmpty();
        assertThat(trace.getEntryCount()).isZero();
    }

    @Test
    public void testConnectionLifecycleGetConnectionThrowsPartiallyDisabled() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteGetConnectionOnThrowingDataSource.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getRootTimer().getNestedTimerNames()).contains("jdbc get connection");
        assertThat(trace.getRootTimer().getNestedTimerNames())
                .doesNotContain("jdbc connection close");
        assertThat(trace.getEntryCount()).isZero();
    }

    @Test
    public void testConnectionLifecycleCloseConnectionThrows() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID,
                "captureConnectionLifecycleTraceEntries", true);
        // when
        container.executeAppUnderTest(ExecuteCloseConnectionOnThrowingDataSource.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(2);
        TraceEntry entry1 = entries.get(0);
        assertThat(entry1.getMessage().getText()).isEqualTo("jdbc get connection");
        TraceEntry entry2 = entries.get(1);
        assertThat(entry2.getMessage().getText()).isEqualTo("jdbc connection close");
        assertThat(entry2.getError().getMessage())
                .isEqualTo("java.sql.SQLException: A close failure");
    }

    @Test
    public void testConnectionLifecycleCloseConnectionThrowsDisabled() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureGetConnection", false);
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureConnectionClose", false);
        // when
        container.executeAppUnderTest(ExecuteCloseConnectionOnThrowingDataSource.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getRootTimer().getNestedTimerNames()).isEmpty();
        assertThat(trace.getEntryCount()).isZero();
    }

    @Test
    public void testConnectionLifecycleCloseConnectionThrowsPartiallyDisabled() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ExecuteCloseConnectionOnThrowingDataSource.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getRootTimer().getNestedTimerNames()).contains("jdbc get connection");
        assertThat(trace.getRootTimer().getNestedTimerNames()).contains("jdbc connection close");
        assertThat(trace.getEntryCount()).isZero();
    }

    @Test
    public void testTransactionLifecycle() throws Exception {
        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID,
                "captureTransactionLifecycleTraceEntries", true);
        // when
        container.executeAppUnderTest(ExecuteSetAutoCommit.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries.size()).isBetween(2, 3);
        TraceEntry entry1 = entries.get(0);
        assertThat(entry1.getMessage().getText()).isEqualTo("jdbc set autocommit: false");
        TraceEntry entry2 = entries.get(1);
        assertThat(entry2.getMessage().getText()).isEqualTo("jdbc set autocommit: true");
        if (entries.size() == 3) {
            TraceEntry entry3 = entries.get(2);
            assertThat(entry3.getMessage().getText()).isEqualTo("jdbc commit");
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
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(2);
        TraceEntry entry1 = entries.get(0);
        assertThat(entry1.getMessage().getText()).isEqualTo("jdbc set autocommit: false");
        assertThat(entry1.getError().getMessage())
                .isEqualTo("java.sql.SQLException: A setautocommit failure");
        TraceEntry entry2 = entries.get(1);
        assertThat(entry2.getMessage().getText()).isEqualTo("jdbc set autocommit: true");
        assertThat(entry2.getError().getMessage())
                .isEqualTo("java.sql.SQLException: A setautocommit failure");
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
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(2);
        TraceEntry entry = entries.get(0);
        assertThat(entry.getMessage().getText())
                .isEqualTo("jdbc get connection (autocommit: true)");
    }

    public static class ExecuteGetConnectionAndConnectionClose
            implements AppUnderTest, TraceMarker {
        private BasicDataSource dataSource;
        @Override
        public void executeApp() throws Exception {
            dataSource = new BasicDataSource();
            dataSource.setDriverClassName("org.hsqldb.jdbc.JDBCDriver");
            dataSource.setUrl("jdbc:hsqldb:mem:test");
            // BasicDataSource opens and closes a test connection on first getConnection(),
            // so just getting that out of the way before starting trace
            dataSource.getConnection().close();
            traceMarker();
        }
        @Override
        public void traceMarker() throws Exception {
            dataSource.getConnection().close();
        }
    }

    public static class ExecuteGetConnectionOnThrowingDataSource
            implements AppUnderTest, TraceMarker {
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
            traceMarker();
        }
        @Override
        public void traceMarker() throws Exception {
            try {
                dataSource.getConnection();
            } catch (SQLException e) {
            }
        }
    }

    public static class ExecuteCloseConnectionOnThrowingDataSource
            implements AppUnderTest, TraceMarker {
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
            // so just getting that out of the way before starting trace
            dataSource.getConnection().close();
            traceMarker();
        }
        @Override
        public void traceMarker() throws Exception {
            try {
                dataSource.getConnection().close();
            } catch (SQLException e) {
            }
        }
    }

    public static class ExecuteSetAutoCommit implements AppUnderTest, TraceMarker {
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
            connection.setAutoCommit(false);
            connection.setAutoCommit(true);
        }
    }

    public static class ExecuteSetAutoCommitThrowing implements AppUnderTest, TraceMarker {
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
                traceMarker();
            } finally {
                Connections.closeConnection(connection);
            }
        }
        @Override
        public void traceMarker() {
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
