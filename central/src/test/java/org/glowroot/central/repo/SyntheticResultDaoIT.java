/*
 * Copyright 2016-2023 the original author or authors.
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

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import com.google.common.collect.Lists;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.Session;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.config.ImmutableCentralStorageConfig;
import org.glowroot.common2.repo.SyntheticResult;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.CassandraContainer;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

public class SyntheticResultDaoIT {

    public static final CassandraContainer cassandra
            = (CassandraContainer) new CassandraContainer("cassandra:3.11.16").withExposedPorts(9042);

    private ClusterManager clusterManager;
    private CqlSessionBuilder cqlSessionBuilder;
    private Session session;
    private static ExecutorService asyncExecutor;
    private SyntheticResultDaoImpl syntheticResultDao;

    @BeforeAll
    public static void beforeAll() throws Exception {
        asyncExecutor = Executors.newCachedThreadPool();
        cassandra.start();
    }

    @BeforeEach
    public void beforeEach() throws Exception {
        clusterManager = ClusterManager.create();
        cqlSessionBuilder = CqlSession
                .builder()
                .addContactPoint(cassandra.getContactPoint())
                .withLocalDatacenter(cassandra.getLocalDatacenter())
                .withConfigLoader(DriverConfigLoader.fromClasspath("datastax-driver.conf"));
        session = new Session(cqlSessionBuilder.build(), "glowroot_unit_tests", null, 0);
        CentralConfigDao centralConfigDao = new CentralConfigDao(session, clusterManager);
        AgentDisplayDao agentDisplayDao =
                new AgentDisplayDao(session, clusterManager, asyncExecutor, 10);
        AgentConfigDao agentConfigDao =
                new AgentConfigDao(session, agentDisplayDao, clusterManager, 10, asyncExecutor);
        UserDao userDao = new UserDao(session, clusterManager);
        RoleDao roleDao = new RoleDao(session, clusterManager);
        ConfigRepositoryImpl configRepository =
                new ConfigRepositoryImpl(centralConfigDao, agentConfigDao, userDao, roleDao, "");
        syntheticResultDao = new SyntheticResultDaoImpl(session, configRepository, asyncExecutor, 0,
                Clock.systemClock());
    }

    @AfterAll
    public static void afterAll() throws Exception {
        if (asyncExecutor != null) {
            asyncExecutor.shutdown();
        }
        cassandra.stop();
    }

    @AfterEach
    public void afterEach() throws Exception {
        try (var se = session;
             var cm = clusterManager) {
        }
    }

    @Test
    public void shouldRollup() throws Exception {
        syntheticResultDao.truncateAll();
        syntheticResultDao.store("one", "11223344", "login page", 60001, SECONDS.toNanos(1), null).toCompletableFuture().join();
        syntheticResultDao.store("one", "11223344", "login page", 120002, SECONDS.toNanos(3), null).toCompletableFuture().join();
        syntheticResultDao.store("one", "11223344", "login page", 360000, SECONDS.toNanos(7), null).toCompletableFuture().join();

        // check non-rolled up data
        List<SyntheticResult> syntheticResults =
                syntheticResultDao.readSyntheticResults("one", "11223344", 0, 300000, 0).toCompletableFuture().join();
        assertThat(syntheticResults).hasSize(2);
        SyntheticResult result1 = syntheticResults.get(0);
        SyntheticResult result2 = syntheticResults.get(1);
        assertThat(result1.captureTime()).isEqualTo(60001);
        assertThat(result1.totalDurationNanos()).isEqualTo(SECONDS.toNanos(1));
        assertThat(result1.executionCount()).isEqualTo(1);
        assertThat(result1.errorIntervals()).isEmpty();
        assertThat(result2.captureTime()).isEqualTo(120002);
        assertThat(result2.totalDurationNanos()).isEqualTo(SECONDS.toNanos(3));
        assertThat(result2.executionCount()).isEqualTo(1);
        assertThat(result2.errorIntervals()).isEmpty();

        // rollup
        List<Integer> rollupExpirationHours = Lists.newArrayList(
                ImmutableCentralStorageConfig.builder().build().rollupExpirationHours());
        rollupExpirationHours.add(0, rollupExpirationHours.get(0));
        syntheticResultDao.rollup("one").toCompletableFuture().join();
        syntheticResultDao.rollup("one").toCompletableFuture().join();
        syntheticResultDao.rollup("one").toCompletableFuture().join();

        // check rolled-up data after rollup
        syntheticResults = syntheticResultDao.readSyntheticResults("one", "11223344", 0, 300000, 1).toCompletableFuture().join();
        assertThat(syntheticResults).hasSize(1);
        result1 = syntheticResults.get(0);
        assertThat(result1.captureTime()).isEqualTo(300000);
        assertThat(result1.totalDurationNanos()).isEqualTo(SECONDS.toNanos(4));
        assertThat(result1.executionCount()).isEqualTo(2);
        assertThat(result1.errorIntervals()).isEmpty();
    }
}
