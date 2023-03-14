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

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.NoNodeAvailableException;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import com.google.common.base.Stopwatch;
import com.google.common.io.Resources;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.glowroot.central.util.Session;
import org.glowroot.common.util.Clock;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.glowroot.central.repo.CqlSessionBuilders.MAX_CONCURRENT_QUERIES;

public class SchemaUpgradeIT {

    private static CqlSessionBuilder cqlSessionBuilder;
    private static Session session;

    @BeforeAll
    public static void setUp() throws Exception {
        SharedSetupRunListener.startCassandra();
        cqlSessionBuilder = CqlSessionBuilders.newCqlSessionBuilder();
        CqlSession wrappedSession = cqlSessionBuilder.build();
        updateSchemaWithRetry(wrappedSession, "drop keyspace if exists glowroot_upgrade_test");
        session = new Session(wrappedSession, "glowroot_upgrade_test", null,
                MAX_CONCURRENT_QUERIES, 0);
        URL url = Resources.getResource("glowroot-0.9.1-schema.cql");
        StringBuilder cql = new StringBuilder();
        for (String line : Resources.readLines(url, UTF_8)) {
            if (line.isEmpty()) {
                session.updateSchemaWithRetry(cql.toString());
                cql.setLength(0);
            } else {
                cql.append('\n');
                cql.append(line);
            }
        }
        restore("glowroot_upgrade_test");
    }

    @AfterAll
    public static void tearDown() throws Exception {
        if (!SharedSetupRunListener.isStarted()) {
            return;
        }
        session.close();
        SharedSetupRunListener.stopCassandra();
    }

    @Test
    public void shouldRead() throws Exception {
        // given
        SchemaUpgrade schemaUpgrade = new SchemaUpgrade(session, 0, Clock.systemClock(), false);
        // when
        schemaUpgrade.upgrade();
        // then don't throw exception
    }

    static void updateSchemaWithRetry(CqlSession wrappedSession,
                                      String query) throws InterruptedException {
        Stopwatch stopwatch = Stopwatch.createStarted();
        while (stopwatch.elapsed(SECONDS) < 60) {
            try {
                wrappedSession.execute(query);
                return;
            } catch (NoNodeAvailableException e) {
            }
            SECONDS.sleep(1);
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
        CqlSession session = CqlSessionBuilders.newCqlSessionBuilder().build();
        for (TableMetadata table : session.getMetadata().getKeyspace(keyspace).get().getTables().values()) {
            // limiting MAXBATCHSIZE to avoid "Batch too large" errors
            ProcessBuilder processBuilder = new ProcessBuilder(cqlsh, "-e",
                    "copy " + keyspace + "." + table.getName().asInternal() + " from '" + backupFolder
                            + table.getName().asInternal() + ".csv' with NULL='NULL.NULL.NULL.NULL' and"
                            + " NUMPROCESSES = 1 and MAXBATCHSIZE = 1");
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            CassandraWrapper.ConsoleOutputPipe consoleOutputPipe =
                    new CassandraWrapper.ConsoleOutputPipe(process.getInputStream(), System.out);
            ExecutorService consolePipeExecutorService = Executors.newSingleThreadExecutor();
            consolePipeExecutorService.submit(consoleOutputPipe);
            process.waitFor();
        }
        session.close();
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
        CqlSession session = CqlSessionBuilders.newCqlSessionBuilder().build();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        for (TableMetadata table : session.getMetadata().getKeyspace(keyspace).get().getTables().values()) {
            ProcessBuilder processBuilder = new ProcessBuilder(cqlsh, "-e",
                    "copy " + keyspace + "." + table.getName().asInternal() + " to '" + backupFolder
                            + table.getName().asInternal() + ".csv' with NULL='NULL.NULL.NULL.NULL' and"
                            + " NUMPROCESSES = 1");
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            executor.submit(new CassandraWrapper.ConsoleOutputPipe(process.getInputStream(), System.out));
            process.waitFor();
        }
        executor.shutdown();
        session.close();
    }
}
