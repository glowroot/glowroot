/*
 * Copyright 2011-2014 the original author or authors.
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

import java.io.File;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.hsqldb.jdbc.JDBCDriver;

import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.TraceMarker;
import org.glowroot.container.config.GeneralConfig;
import org.glowroot.container.config.PluginConfig;
import org.glowroot.container.javaagent.JavaagentContainer;
import org.glowroot.container.trace.Trace;
import org.glowroot.container.trace.TraceMetric;

/**
 * Performance test of the jdbc plugin.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class JdbcPluginPerformanceMain {

    private static final String PLUGIN_ID = "jdbc";
    private static final String DB_NAME = "testdb";

    public static void main(String... args) throws Exception {
        setUpTestDatabase();
        testWithGlowroot();
    }

    private static void setUpTestDatabase() throws Exception {
        // set up database
        new File(DB_NAME + ".properties").delete();
        new File(DB_NAME + ".script").delete();
        Connection connection = createConnection();
        Statement statement = null;
        try {
            statement = connection.createStatement();
            statement.execute("create table employee (name varchar(100))");
            for (int i = 0; i < 1000; i++) {
                statement.execute("insert into employee (name) values ('john doe " + i + "')");
            }
        } finally {
            if (statement != null) {
                statement.close();
            }
            connection.close();
        }
    }

    @SuppressWarnings("unused")
    private static void testWithoutGlowroot() throws Exception {
        System.out.print("without glowroot:         ");
        new ExecuteJdbcSelectAndIterateOverResults().executeApp();
    }

    private static void testWithGlowroot() throws Exception {
        System.out.print("with glowroot:            ");
        Container container = setUpContainer();
        container.executeAppUnderTest(ExecuteJdbcSelectAndIterateOverResults.class);
        Trace trace = container.getTraceService().getLastTrace();
        System.out.format("%s %d %d%n", trace.getRootTraceMetric().getName(),
                trace.getRootTraceMetric().getTotal(), trace.getRootTraceMetric().getCount());
        for (TraceMetric traceMetric : trace.getRootTraceMetric().getNestedTraceMetrics()) {
            System.out.format("%s %d %d%n", traceMetric.getName(), traceMetric.getTotal(),
                    traceMetric.getCount());
        }
        container.close();
    }

    @SuppressWarnings("unused")
    private static void testWithGlowrootDisabled() throws Exception {
        System.out.print("with glowroot disabled:   ");
        Container container = setUpContainer();
        GeneralConfig config = container.getConfigService().getGeneralConfig();
        config.setEnabled(false);
        container.getConfigService().updateGeneralConfig(config);
        container.executeAppUnderTest(ExecuteJdbcSelectAndIterateOverResults.class);
        container.close();
    }

    @SuppressWarnings("unused")
    private static void testWithGlowrootJdbcPluginDisabled() throws Exception {
        System.out.print("with jdbc plugin disabled: ");
        Container container = setUpContainer();
        PluginConfig config = container.getConfigService().getPluginConfig(PLUGIN_ID);
        config.setEnabled(false);
        container.getConfigService().updatePluginConfig(PLUGIN_ID, config);
        container.executeAppUnderTest(ExecuteJdbcSelectAndIterateOverResults.class);
        container.close();
    }

    @SuppressWarnings("unused")
    private static void testWithGlowrootResultSetNextDisabled() throws Exception {
        System.out.print("with jdbc result set next trace metric disabled: ");
        Container container = setUpContainer();
        PluginConfig config = container.getConfigService().getPluginConfig(PLUGIN_ID);
        config.setProperty("captureResultSetNext", false);
        container.getConfigService().updatePluginConfig(PLUGIN_ID, config);
        container.executeAppUnderTest(ExecuteJdbcSelectAndIterateOverResults.class);
        container.close();
    }

    private static Container setUpContainer() throws Exception {
        Container container = JavaagentContainer.createWithFileDb();
        GeneralConfig generalConfig = container.getConfigService().getGeneralConfig();
        generalConfig.setStoreThresholdMillis(60000);
        container.getConfigService().updateGeneralConfig(generalConfig);
        return container;
    }

    private static Connection createConnection() throws SQLException {
        return JDBCDriver.getConnection("jdbc:hsqldb:file:" + DB_NAME + ";shutdown=true", null);
    }

    @SuppressWarnings("unused")
    private static void printGarbageCollectionStats() {
        for (GarbageCollectorMXBean mbean : ManagementFactory.getGarbageCollectorMXBeans()) {
            System.out.format("%s %d %d%n", mbean.getName(), mbean.getCollectionTime(),
                    mbean.getCollectionCount());
        }
    }

    public static class ExecuteJdbcSelectAndIterateOverResults implements AppUnderTest,
            TraceMarker {

        private Connection connection;
        @Override
        public void executeApp() throws Exception {
            connection = createConnection();
            try {
                for (int i = 0; i < 1000; i++) {
                    long startTick = System.nanoTime();
                    traceMarker();
                    System.out.format("%d milliseconds%n",
                            (System.nanoTime() - startTick) / 1000000);
                }
            } finally {
                connection.close();
            }
        }
        @Override
        public void traceMarker() throws Exception {
            inner();
        }
        private void inner() throws SQLException {
            Statement statement = connection.createStatement();
            try {
                statement.execute("select e.name, e2.name from employee e, employee e2");
                ResultSet rs = statement.getResultSet();
                while (rs.next()) {
                    rs.getString(1);
                    rs.getString(2);
                }
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                statement.close();
            }
        }
    }
}
