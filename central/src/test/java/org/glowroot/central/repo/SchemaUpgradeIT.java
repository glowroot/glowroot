/*
 * Copyright 2016-2018 the original author or authors.
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
package org.glowroot.central.repo;

import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.TableMetadata;
import com.datastax.driver.core.exceptions.NoHostAvailableException;
import com.google.common.base.Charsets;
import com.google.common.base.Stopwatch;
import com.google.common.io.Resources;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.central.repo.CassandraWrapper.ConsoleOutputPipe;
import org.glowroot.central.util.Session;
import org.glowroot.common.util.Clock;

import static java.util.concurrent.TimeUnit.SECONDS;

public class SchemaUpgradeIT {

    private static Cluster cluster;
    private static Session session;

    @BeforeClass
    public static void setUp() throws Exception {
        SharedSetupRunListener.startCassandra();
        cluster = Clusters.newCluster();
        com.datastax.driver.core.Session wrappedSession = cluster.newSession();
        updateSchemaWithRetry(wrappedSession, "drop keyspace if exists glowroot_upgrade_test");
        session = new Session(wrappedSession, "glowroot_upgrade_test");
        URL url = Resources.getResource("glowroot-0.9.1-schema.cql");
        StringBuilder cql = new StringBuilder();
        for (String line : Resources.readLines(url, Charsets.UTF_8)) {
            if (line.isEmpty()) {
                session.execute(cql.toString());
                cql.setLength(0);
            } else {
                cql.append('\n');
                cql.append(line);
            }
        }
        restore("glowroot_upgrade_test");
    }

    @AfterClass
    public static void tearDown() throws Exception {
        session.close();
        cluster.close();
        SharedSetupRunListener.stopCassandra();
    }

    @Test
    public void shouldRead() throws Exception {
        // given
        SchemaUpgrade schemaUpgrade = new SchemaUpgrade(session, Clock.systemClock(), false);
        // when
        schemaUpgrade.upgrade();
        // then don't throw exception
    }

    private static void updateSchemaWithRetry(com.datastax.driver.core.Session wrappedSession,
            String query) throws InterruptedException {
        Stopwatch stopwatch = Stopwatch.createStarted();
        while (stopwatch.elapsed(SECONDS) < 60) {
            try {
                wrappedSession.execute(query);
                return;
            } catch (NoHostAvailableException e) {
            }
            Thread.sleep(1000);
        }
        // try one last time and let exception bubble up
        wrappedSession.execute(query);
    }

    private static void restore(String keyspace) throws Exception {
        String cqlsh =
                "cassandra/apache-cassandra-" + CassandraWrapper.CASSANDRA_VERSION + "/bin/cqlsh";
        if (System.getProperty("os.name").startsWith("Windows")) {
            cqlsh += ".bat";
        }
        String backupFolder = "src/test/resources/backup-0.9.1/";
        Cluster cluster = Clusters.newCluster();
        for (TableMetadata table : cluster.getMetadata().getKeyspace(keyspace).getTables()) {
            // limiting MAXBATCHSIZE to avoid "Batch too large" errors
            ProcessBuilder processBuilder = new ProcessBuilder(cqlsh, "-e",
                    "copy " + keyspace + "." + table.getName() + " from '" + backupFolder
                            + table.getName() + ".csv' with NULL='NULL.NULL.NULL.NULL' and"
                            + " NUMPROCESSES = 1 and MAXBATCHSIZE = 1");
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            ConsoleOutputPipe consoleOutputPipe =
                    new ConsoleOutputPipe(process.getInputStream(), System.out);
            ExecutorService consolePipeExecutorService = Executors.newSingleThreadExecutor();
            consolePipeExecutorService.submit(consoleOutputPipe);
            process.waitFor();
        }
    }

    // this is used for creating the backup files
    @SuppressWarnings("unused")
    private static void backup(String keyspace) throws Exception {
        String cqlsh =
                "cassandra/apache-cassandra-" + CassandraWrapper.CASSANDRA_VERSION + "/bin/cqlsh";
        if (System.getProperty("os.name").startsWith("Windows")) {
            cqlsh += ".bat";
        }
        String backupFolder = "src/test/resources/backup-0.9.1/";
        Cluster cluster = Clusters.newCluster();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        for (TableMetadata table : cluster.getMetadata().getKeyspace(keyspace).getTables()) {
            ProcessBuilder processBuilder = new ProcessBuilder(cqlsh, "-e",
                    "copy " + keyspace + "." + table.getName() + " to '" + backupFolder
                            + table.getName() + ".csv' with NULL='NULL.NULL.NULL.NULL' and"
                            + " NUMPROCESSES = 1");
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            executor.submit(new ConsoleOutputPipe(process.getInputStream(), System.out));
            process.waitFor();
        }
        executor.shutdown();
        cluster.close();
    }
}
