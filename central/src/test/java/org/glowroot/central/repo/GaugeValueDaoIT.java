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
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.Session;
import org.glowroot.central.v09support.GaugeValueDaoWithV09Support;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.config.ImmutableCentralStorageConfig;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueMessage.GaugeValue;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.CassandraContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

public class GaugeValueDaoIT {
    public static final CassandraContainer cassandra
            = (CassandraContainer) new CassandraContainer("cassandra:3.11.15").withExposedPorts(9042);

    private ClusterManager clusterManager;
    private Session session;
    private static ExecutorService asyncExecutor;
    private AgentConfigDao agentConfigDao;
    private GaugeValueDao gaugeValueDao;
    private CqlSessionBuilder cqlSessionBuilder;

    @BeforeAll
    public static void beforeAll() {
        cassandra.start();
        asyncExecutor = Executors.newCachedThreadPool();
    }

    @BeforeEach
    public void setUp() throws Exception {
        clusterManager = ClusterManager.create();
        cqlSessionBuilder = CqlSession
                .builder()
                .addContactPoint(cassandra.getContactPoint())
                .withLocalDatacenter(cassandra.getLocalDatacenter())
                .withConfigLoader(DriverConfigLoader.fromClasspath("datastax-driver.conf"));
        session = new Session(cqlSessionBuilder.build(), "glowroot_unit_tests", null,
                0);
        CentralConfigDao centralConfigDao = new CentralConfigDao(session, clusterManager);
        AgentDisplayDao agentDisplayDao =
                new AgentDisplayDao(session, clusterManager, asyncExecutor, 10);
        agentConfigDao = new AgentConfigDao(session, agentDisplayDao, clusterManager, 10, asyncExecutor);
        UserDao userDao = new UserDao(session, clusterManager);
        RoleDao roleDao = new RoleDao(session, clusterManager);
        ConfigRepositoryImpl configRepository = new ConfigRepositoryImpl(centralConfigDao,
                agentConfigDao, userDao, roleDao, "");
        gaugeValueDao = new GaugeValueDaoWithV09Support(ImmutableSet.of(), 0, Clock.systemClock(),
                new GaugeValueDaoImpl(session, configRepository, clusterManager, asyncExecutor, 0,
                        Clock.systemClock()));
    }

    @AfterAll
    public static void afterAll() throws Exception {
        cassandra.stop();
        if (asyncExecutor != null) {
            asyncExecutor.shutdown();
        }
    }

    @AfterEach
    public void tearDown() throws Exception {
        try (var se = session;
             var cm = clusterManager) {
        }
    }

    @Test
    public void shouldRollup() throws Exception {
        gaugeValueDao.truncateAll();
        gaugeValueDao.store("one", createData(60013));
        gaugeValueDao.store("one", createData(65009));
        gaugeValueDao.store("one", createData(360000));

        // check non-rolled up data
        List<GaugeValue> gaugeValues =
                gaugeValueDao.readGaugeValues("one", "the gauge:attr1", 0, 300000, 0, CassandraProfile.web).toCompletableFuture().get();
        assertThat(gaugeValues).hasSize(2);
        assertThat(gaugeValues.get(0).getValue()).isEqualTo(500);
        assertThat(gaugeValues.get(0).getWeight()).isEqualTo(1);
        assertThat(gaugeValues.get(1).getValue()).isEqualTo(500);
        assertThat(gaugeValues.get(1).getWeight()).isEqualTo(1);

        // rollup
        List<Integer> rollupExpirationHours = Lists.newArrayList(
                ImmutableCentralStorageConfig.builder().build().rollupExpirationHours());
        rollupExpirationHours.add(0, rollupExpirationHours.get(0));
        gaugeValueDao.rollup("one").toCompletableFuture().get();
        gaugeValueDao.rollup("one").toCompletableFuture().get();
        gaugeValueDao.rollup("one").toCompletableFuture().get();

        // check rolled-up data after rollup
        gaugeValues = gaugeValueDao.readGaugeValues("one", "the gauge:attr1", 0, 300000, 1, CassandraProfile.web).toCompletableFuture().get();
        assertThat(gaugeValues).hasSize(1);
        assertThat(gaugeValues.get(0).getValue()).isEqualTo(500);
        assertThat(gaugeValues.get(0).getWeight()).isEqualTo(2);
    }

    @Test
    public void shouldRollupFromChildren() throws Exception {
        gaugeValueDao.truncateAll();
        gaugeValueDao.store("the parent::one", createData(60013)).toCompletableFuture().join();
        gaugeValueDao.store("the parent::one", createData(65009)).toCompletableFuture().join();
        gaugeValueDao.store("the parent::one", createData(360000)).toCompletableFuture().join();

        // rollup
        // need to roll up children first, since gauge values initial roll up from children is
        // done on the 1-min aggregates of the children
        gaugeValueDao.rollup("the parent::one").toCompletableFuture().get();
        gaugeValueDao.rollup("the parent::").toCompletableFuture().get();

        // check rolled-up data after rollup
        List<GaugeValue> gaugeValues =
                gaugeValueDao.readGaugeValues("the parent::", "the gauge:attr1", 0, 300000, 1, CassandraProfile.web).toCompletableFuture().get();
        assertThat(gaugeValues).hasSize(1);
        assertThat(gaugeValues.get(0).getValue()).isEqualTo(500);
        assertThat(gaugeValues.get(0).getWeight()).isEqualTo(2);
    }

    @Test
    public void shouldRollupFromGrandChildren() throws Exception {
        gaugeValueDao.truncateAll();
        gaugeValueDao.store("the gp::the parent::one", createData(60013)).toCompletableFuture().join();
        gaugeValueDao.store("the gp::the parent::one", createData(65009)).toCompletableFuture().join();
        gaugeValueDao.store("the gp::the parent::one", createData(360000)).toCompletableFuture().join();

        // rollup
        // need to roll up children first, since gauge values initial roll up from children is
        // done on the 1-min aggregates of the children
        gaugeValueDao.rollup("the gp::the parent::one").toCompletableFuture().get();
        gaugeValueDao.rollup("the gp::the parent::").toCompletableFuture().get();
        gaugeValueDao.rollup("the gp::").toCompletableFuture().get();

        // check rolled-up data after rollup
        List<GaugeValue> gaugeValues =
                gaugeValueDao.readGaugeValues("the gp::", "the gauge:attr1", 0, 300000, 1, CassandraProfile.web).toCompletableFuture().get();
        assertThat(gaugeValues).hasSize(1);
        assertThat(gaugeValues.get(0).getValue()).isEqualTo(500);
        assertThat(gaugeValues.get(0).getWeight()).isEqualTo(2);
    }

    private static List<GaugeValue> createData(int captureTime) {
        List<GaugeValue> gaugeValues = new ArrayList<>();
        gaugeValues.add(GaugeValue.newBuilder()
                .setGaugeName("the gauge:attr1")
                .setCaptureTime(captureTime)
                .setValue(500)
                .setWeight(1)
                .build());
        gaugeValues.add(GaugeValue.newBuilder()
                .setGaugeName("the gauge:attr2[counter]")
                .setCaptureTime(captureTime)
                .setValue(600)
                .setWeight(5000)
                .build());
        return gaugeValues;
    }
}
