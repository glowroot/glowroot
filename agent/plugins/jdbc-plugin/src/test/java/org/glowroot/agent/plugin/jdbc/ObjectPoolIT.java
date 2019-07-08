/*
 * Copyright 2019 the original author or authors.
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
import java.util.Iterator;

import javax.sql.DataSource;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.agent.it.harness.impl.JavaagentContainer;
import org.glowroot.agent.plugin.jdbc.Connections.ConnectionType;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

public class ObjectPoolIT {

    private static final String PLUGIN_ID = "jdbc";

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = JavaagentContainer.create();
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
    public void testReturningCommonsDbcpConnection() throws Exception {

        // this is needed for multi-lib-tests, to isolate changing one version at a time
        assumeTrue(Connections.getConnectionType().equals(ConnectionType.COMMONS_DBCP_WRAPPED));

        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureConnectionPoolLeaks",
                true);

        // when
        Trace trace = container.execute(ReturnCommonsDbcpConnection.class);

        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.hasError()).isFalse();

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();
        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void testLeakingCommonsDbcpConnection() throws Exception {

        // this is needed for multi-lib-tests, to isolate changing one version at a time
        assumeTrue(Connections.getConnectionType().equals(ConnectionType.COMMONS_DBCP_WRAPPED));

        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureConnectionPoolLeaks",
                true);

        // when
        Trace trace = container.execute(LeakCommonsDbcpConnection.class);

        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.hasError()).isTrue();
        assertThat(header.getError().getMessage()).startsWith("Resource leaked");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();
        Trace.Entry entry = i.next();
        assertThat(entry.getMessage()).startsWith("Resource leaked");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void testReturningCommonsDbcp2Connection() throws Exception {

        // this is needed for multi-lib-tests, to isolate changing one version at a time
        assumeTrue(Connections.getConnectionType().equals(ConnectionType.COMMONS_DBCP2_WRAPPED));

        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureConnectionPoolLeaks",
                true);

        // when
        Trace trace = container.execute(ReturnCommonsDbcp2Connection.class);

        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.hasError()).isFalse();

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();
        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void testLeakingCommonsDbcp2Connection() throws Exception {

        // this is needed for multi-lib-tests, to isolate changing one version at a time
        assumeTrue(Connections.getConnectionType().equals(ConnectionType.COMMONS_DBCP2_WRAPPED));

        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureConnectionPoolLeaks",
                true);

        // when
        Trace trace = container.execute(LeakCommonsDbcp2Connection.class);

        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.hasError()).isTrue();
        assertThat(header.getError().getMessage()).startsWith("Resource leaked");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();
        Trace.Entry entry = i.next();
        assertThat(entry.getMessage()).startsWith("Resource leaked");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void testReturningTomcatConnection() throws Exception {

        // this is needed for multi-lib-tests, to isolate changing one version at a time
        assumeTrue(Connections.getConnectionType().equals(ConnectionType.TOMCAT_JDBC_POOL_WRAPPED));

        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureConnectionPoolLeaks",
                true);

        // when
        Trace trace = container.execute(ReturnTomcatConnection.class);

        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.hasError()).isFalse();

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();
        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void testLeakingTomcatConnection() throws Exception {

        // this is needed for multi-lib-tests, to isolate changing one version at a time
        assumeTrue(Connections.getConnectionType().equals(ConnectionType.TOMCAT_JDBC_POOL_WRAPPED));

        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureConnectionPoolLeaks",
                true);

        // when
        Trace trace = container.execute(LeakTomcatConnection.class);

        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.hasError()).isTrue();
        assertThat(header.getError().getMessage()).startsWith("Resource leaked");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();
        Trace.Entry entry = i.next();
        assertThat(entry.getMessage()).startsWith("Resource leaked");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void testReturningTomcatAsyncConnection() throws Exception {

        // this is needed for multi-lib-tests, to isolate changing one version at a time
        assumeTrue(Connections.getConnectionType().equals(ConnectionType.TOMCAT_JDBC_POOL_WRAPPED));

        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureConnectionPoolLeaks",
                true);

        // when
        Trace trace = container.execute(ReturnTomcatAsyncConnection.class);

        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.hasError()).isFalse();

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();
        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void testLeakingTomcatAsyncConnection() throws Exception {

        // this is needed for multi-lib-tests, to isolate changing one version at a time
        assumeTrue(Connections.getConnectionType().equals(ConnectionType.TOMCAT_JDBC_POOL_WRAPPED));

        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureConnectionPoolLeaks",
                true);

        // when
        Trace trace = container.execute(LeakTomcatAsyncConnection.class);

        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.hasError()).isTrue();
        assertThat(header.getError().getMessage()).startsWith("Resource leaked");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();
        Trace.Entry entry = i.next();
        assertThat(entry.getMessage()).startsWith("Resource leaked");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void testReturningGlassfishConnection() throws Exception {

        // this is needed for multi-lib-tests, to isolate changing one version at a time
        assumeTrue(
                Connections.getConnectionType().equals(ConnectionType.GLASSFISH_JDBC_POOL_WRAPPED));

        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureConnectionPoolLeaks",
                true);

        // when
        Trace trace = container.execute(ReturnGlassfishConnection.class);

        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.hasError()).isFalse();

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();
        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void testLeakingGlassfishConnection() throws Exception {

        // this is needed for multi-lib-tests, to isolate changing one version at a time
        assumeTrue(
                Connections.getConnectionType().equals(ConnectionType.GLASSFISH_JDBC_POOL_WRAPPED));

        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureConnectionPoolLeaks",
                true);

        // when
        Trace trace = container.execute(LeakGlassfishConnection.class);

        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.hasError()).isTrue();
        assertThat(header.getError().getMessage()).startsWith("Resource leaked");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();
        Trace.Entry entry = i.next();
        assertThat(entry.getMessage()).startsWith("Resource leaked");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void testReturningHikariConnection() throws Exception {

        // this is needed for multi-lib-tests, to isolate changing one version at a time
        assumeTrue(Connections.getConnectionType().equals(ConnectionType.HIKARI_CP_WRAPPED));

        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureConnectionPoolLeaks",
                true);

        // when
        Trace trace = container.execute(ReturnHikariConnection.class);

        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.hasError()).isFalse();

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();
        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void testLeakingHikariConnection() throws Exception {

        // this is needed for multi-lib-tests, to isolate changing one version at a time
        assumeTrue(Connections.getConnectionType().equals(ConnectionType.HIKARI_CP_WRAPPED));

        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureConnectionPoolLeaks",
                true);

        // when
        Trace trace = container.execute(LeakHikariConnection.class);

        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.hasError()).isTrue();
        assertThat(header.getError().getMessage()).startsWith("Resource leaked");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();
        Trace.Entry entry = i.next();
        assertThat(entry.getMessage()).startsWith("Resource leaked");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void testReturningBitronixConnection() throws Exception {

        // this is needed for multi-lib-tests, to isolate changing one version at a time
        assumeTrue(Connections.getConnectionType().equals(ConnectionType.BITRONIX_WRAPPED));

        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureConnectionPoolLeaks",
                true);

        // when
        Trace trace = container.execute(ReturnBitronixConnection.class);

        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.hasError()).isFalse();

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();
        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void testLeakingBitronixConnection() throws Exception {

        // this is needed for multi-lib-tests, to isolate changing one version at a time
        assumeTrue(Connections.getConnectionType().equals(ConnectionType.BITRONIX_WRAPPED));

        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureConnectionPoolLeaks",
                true);

        // when
        Trace trace = container.execute(LeakBitronixConnection.class);

        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.hasError()).isTrue();
        assertThat(header.getError().getMessage()).startsWith("Resource leaked");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();
        Trace.Entry entry = i.next();
        assertThat(entry.getMessage()).startsWith("Resource leaked");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void testLeakingMultipleConnections() throws Exception {

        // this is needed for multi-lib-tests, to isolate changing one version at a time
        assumeTrue(Connections.getConnectionType().equals(ConnectionType.COMMONS_DBCP_WRAPPED));

        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureConnectionPoolLeaks",
                true);

        // when
        Trace trace = container.execute(LeakMultipleDbcpConnections.class);

        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.hasError()).isTrue();
        assertThat(header.getError().getMessage()).startsWith("Resource leaked");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();
        Trace.Entry entry = i.next();
        assertThat(entry.getMessage()).startsWith("Resource leaked");
        assertThat(entry.getLocationStackTraceElementCount()).isZero();
        entry = i.next();
        assertThat(entry.getMessage()).startsWith("Resource leaked");
        assertThat(entry.getLocationStackTraceElementCount()).isZero();

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void testLeakingConnectionWithLocationStackTrace() throws Exception {

        // this is needed for multi-lib-tests, to isolate changing one version at a time
        assumeTrue(Connections.getConnectionType().equals(ConnectionType.COMMONS_DBCP_WRAPPED));

        // given
        container.getConfigService().setPluginProperty(PLUGIN_ID, "captureConnectionPoolLeaks",
                true);
        container.getConfigService().setPluginProperty(PLUGIN_ID,
                "captureConnectionPoolLeakDetails", true);

        // when
        Trace trace = container.execute(LeakCommonsDbcpConnection.class);

        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.hasError()).isTrue();
        assertThat(header.getError().getMessage()).startsWith("Resource leaked");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();
        Trace.Entry entry = i.next();
        assertThat(entry.getMessage()).startsWith("Resource leaked");
        assertThat(entry.getLocationStackTraceElementCount()).isGreaterThan(0);
        assertThat(entry.getLocationStackTraceElement(0).getClassName())
                .isEqualTo("org.apache.commons.pool.impl.GenericObjectPool");
        assertThat(entry.getLocationStackTraceElement(0).getMethodName()).isEqualTo("borrowObject");

        assertThat(i.hasNext()).isFalse();
    }

    public static class ReturnCommonsDbcpConnection implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws Exception {
            DataSource ds = Connections.createCommonsDbcpWrappedDataSource();
            ds.getConnection().close();
        }
    }

    public static class LeakCommonsDbcpConnection implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws Exception {
            DataSource ds = Connections.createCommonsDbcpWrappedDataSource();
            ds.getConnection();
        }
    }

    public static class ReturnCommonsDbcp2Connection implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws Exception {
            DataSource ds = Connections.createCommonsDbcp2DataSource();
            ds.getConnection().close();
        }
    }

    public static class LeakCommonsDbcp2Connection implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws Exception {
            DataSource ds = Connections.createCommonsDbcp2DataSource();
            ds.getConnection();
        }
    }

    public static class ReturnTomcatConnection implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws Exception {
            DataSource ds = Connections.createTomcatJdbcPoolWrappedDataSource();
            ds.getConnection().close();
        }
    }

    public static class LeakTomcatConnection implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws Exception {
            DataSource ds = Connections.createTomcatJdbcPoolWrappedDataSource();
            ds.getConnection();
        }
    }

    public static class ReturnTomcatAsyncConnection implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws Exception {
            org.apache.tomcat.jdbc.pool.DataSource ds =
                    Connections.createTomcatJdbcPoolWrappedDataSource();
            ds.getConnectionAsync().get().close();
        }
    }

    public static class LeakTomcatAsyncConnection implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws Exception {
            org.apache.tomcat.jdbc.pool.DataSource ds =
                    Connections.createTomcatJdbcPoolWrappedDataSource();
            ds.getConnectionAsync().get();
        }
    }

    public static class ReturnGlassfishConnection implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws Exception {
            DataSource ds = Connections.createGlassfishJdbcPoolWrappedDataSource();
            Connection connection = ds.getConnection();
            Connections.hackGlassfishConnection(connection);
            connection.close();
        }
    }

    public static class LeakGlassfishConnection implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws Exception {
            DataSource ds = Connections.createGlassfishJdbcPoolWrappedDataSource();
            Connection connection = ds.getConnection();
            Connections.hackGlassfishConnection(connection);
        }
    }

    public static class ReturnHikariConnection implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws Exception {
            DataSource ds = Connections.createHikariCpDataSource();
            ds.getConnection().close();
        }
    }

    public static class LeakHikariConnection implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws Exception {
            DataSource ds = Connections.createHikariCpDataSource();
            ds.getConnection();
        }
    }

    public static class ReturnBitronixConnection implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws Exception {
            DataSource ds = Connections.createBitronixWrappedDataSource();
            ds.getConnection().close();
        }
    }

    public static class LeakBitronixConnection implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws Exception {
            DataSource ds = Connections.createBitronixWrappedDataSource();
            ds.getConnection();
        }
    }

    public static class LeakMultipleDbcpConnections implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws Exception {
            DataSource ds = Connections.createCommonsDbcpWrappedDataSource();
            ds.getConnection();
            ds.getConnection();
        }
    }
}
