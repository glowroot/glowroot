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

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.PoolingOptions;
import com.google.common.collect.Lists;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.Session;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.config.ImmutableCentralStorageConfig;
import org.glowroot.common2.repo.SyntheticResult;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

public class SyntheticResultDaoIT {

    private static ClusterManager clusterManager;
    private static Cluster cluster;
    private static Session session;
    private static ExecutorService asyncExecutor;
    private static SyntheticResultDaoImpl syntheticResultDao;

    @BeforeClass
    public static void setUp() throws Exception {
        SharedSetupRunListener.startCassandra();
        clusterManager = ClusterManager.create();
        cluster = Clusters.newCluster();
        session = new Session(cluster.newSession(), "glowroot_unit_tests", null,
                PoolingOptions.DEFAULT_MAX_QUEUE_SIZE);
        CentralConfigDao centralConfigDao = new CentralConfigDao(session, clusterManager);
        AgentConfigDao agentConfigDao = new AgentConfigDao(session, clusterManager);
        UserDao userDao = new UserDao(session, clusterManager);
        RoleDao roleDao = new RoleDao(session, clusterManager);
        ConfigRepositoryImpl configRepository =
                new ConfigRepositoryImpl(centralConfigDao, agentConfigDao, userDao, roleDao, "");
        asyncExecutor = Executors.newCachedThreadPool();
        syntheticResultDao = new SyntheticResultDaoImpl(session, configRepository, asyncExecutor,
                Clock.systemClock());
    }

    @AfterClass
    public static void tearDown() throws Exception {
        asyncExecutor.shutdown();
        session.close();
        cluster.close();
        clusterManager.close();
        SharedSetupRunListener.stopCassandra();
    }

    @Test
    public void shouldRollup() throws Exception {
        syntheticResultDao.truncateAll();
        syntheticResultDao.store("one", "11223344", "login page", 60001, SECONDS.toNanos(1), null);
        syntheticResultDao.store("one", "11223344", "login page", 120002, SECONDS.toNanos(3), null);
        syntheticResultDao.store("one", "11223344", "login page", 360000, SECONDS.toNanos(7), null);

        // check non-rolled up data
        List<SyntheticResult> syntheticResults =
                syntheticResultDao.readSyntheticResults("one", "11223344", 0, 300000, 0);
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
        syntheticResultDao.rollup("one");
        syntheticResultDao.rollup("one");
        syntheticResultDao.rollup("one");

        // check rolled-up data after rollup
        syntheticResults = syntheticResultDao.readSyntheticResults("one", "11223344", 0, 300000, 1);
        assertThat(syntheticResults).hasSize(1);
        result1 = syntheticResults.get(0);
        assertThat(result1.captureTime()).isEqualTo(300000);
        assertThat(result1.totalDurationNanos()).isEqualTo(SECONDS.toNanos(4));
        assertThat(result1.executionCount()).isEqualTo(2);
        assertThat(result1.errorIntervals()).isEmpty();
    }
}
