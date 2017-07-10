/*
 * Copyright 2016-2017 the original author or authors.
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

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.KeyspaceMetadata;
import com.google.common.collect.Lists;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.Session;
import org.glowroot.common.config.CentralStorageConfig;
import org.glowroot.common.config.ImmutableCentralStorageConfig;
import org.glowroot.common.util.Clock;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValue;

import static org.assertj.core.api.Assertions.assertThat;

public class GaugeValueDaoIT {

    private static Cluster cluster;
    private static Session session;
    private static ClusterManager clusterManager;
    private static AgentRollupDao agentRollupDao;
    private static GaugeValueDao gaugeValueDao;

    @BeforeClass
    public static void setUp() throws Exception {
        SharedSetupRunListener.startCassandra();
        cluster = Clusters.newCluster();
        session = new Session(cluster.newSession());
        session.createKeyspaceIfNotExists("glowroot_unit_tests");
        session.execute("use glowroot_unit_tests");
        KeyspaceMetadata keyspaceMetadata =
                cluster.getMetadata().getKeyspace("glowroot_unit_tests");

        clusterManager = ClusterManager.create();
        CentralConfigDao centralConfigDao = new CentralConfigDao(session, clusterManager);
        agentRollupDao = new AgentRollupDao(session, clusterManager);
        AgentConfigDao agentConfigDao = new AgentConfigDao(session, clusterManager);
        UserDao userDao = new UserDao(session, keyspaceMetadata, clusterManager);
        RoleDao roleDao = new RoleDao(session, keyspaceMetadata, clusterManager);
        ConfigRepositoryImpl configRepository = new ConfigRepositoryImpl(agentRollupDao,
                agentConfigDao, centralConfigDao, userDao, roleDao, "");
        CentralStorageConfig storageConfig = configRepository.getCentralStorageConfig();
        configRepository.updateCentralStorageConfig(
                ImmutableCentralStorageConfig
                        .copyOf(storageConfig)
                        .withRollupExpirationHours(0, 0, 0, 0),
                storageConfig.version());
        gaugeValueDao = new GaugeValueDao(session, agentRollupDao, configRepository, clusterManager,
                Clock.systemClock());
    }

    @AfterClass
    public static void tearDown() throws Exception {
        clusterManager.close();
        session.close();
        cluster.close();
        SharedSetupRunListener.stopCassandra();
    }

    @Test
    public void shouldRollup() throws Exception {
        gaugeValueDao.truncateAll();
        gaugeValueDao.store("one", createData(60013));
        gaugeValueDao.store("one", createData(65009));
        gaugeValueDao.store("one", createData(360000));

        // check non-rolled up data
        List<GaugeValue> gaugeValues =
                gaugeValueDao.readGaugeValues("one", "the gauge:attr1", 0, 300000, 0);
        assertThat(gaugeValues).hasSize(2);
        assertThat(gaugeValues.get(0).getValue()).isEqualTo(500);
        assertThat(gaugeValues.get(0).getWeight()).isEqualTo(1);
        assertThat(gaugeValues.get(1).getValue()).isEqualTo(500);
        assertThat(gaugeValues.get(1).getWeight()).isEqualTo(1);

        // rollup
        List<Integer> rollupExpirationHours = Lists.newArrayList(
                ImmutableCentralStorageConfig.builder().build().rollupExpirationHours());
        rollupExpirationHours.add(0, rollupExpirationHours.get(0));
        gaugeValueDao.rollup("one", null, true);
        gaugeValueDao.rollup("one", null, true);
        gaugeValueDao.rollup("one", null, true);

        // check rolled-up data after rollup
        gaugeValues = gaugeValueDao.readGaugeValues("one", "the gauge:attr1", 0, 300000, 1);
        assertThat(gaugeValues).hasSize(1);
        assertThat(gaugeValues.get(0).getValue()).isEqualTo(500);
        assertThat(gaugeValues.get(0).getWeight()).isEqualTo(2);
    }

    @Test
    public void shouldRollupFromChildren() throws Exception {

        agentRollupDao.store("one", "the parent");

        gaugeValueDao.truncateAll();
        gaugeValueDao.store("one", createData(60013));
        gaugeValueDao.store("one", createData(65009));
        gaugeValueDao.store("one", createData(360000));

        // rollup
        gaugeValueDao.rollup("one", "the parent", true);
        gaugeValueDao.rollup("the parent", null, false);

        // check rolled-up data after rollup
        List<GaugeValue> gaugeValues =
                gaugeValueDao.readGaugeValues("the parent", "the gauge:attr1", 0, 300000, 1);
        assertThat(gaugeValues).hasSize(1);
        assertThat(gaugeValues.get(0).getValue()).isEqualTo(500);
        assertThat(gaugeValues.get(0).getWeight()).isEqualTo(2);
    }

    @Test
    public void shouldRollupFromGrandChildren() throws Exception {

        agentRollupDao.store("one", "the gp/the parent");

        gaugeValueDao.truncateAll();
        gaugeValueDao.store("one", createData(60013));
        gaugeValueDao.store("one", createData(65009));
        gaugeValueDao.store("one", createData(360000));

        // rollup
        gaugeValueDao.rollup("one", "the gp/the parent", true);
        gaugeValueDao.rollup("the gp/the parent", "the gp", false);
        gaugeValueDao.rollup("the gp", null, false);

        // check rolled-up data after rollup
        List<GaugeValue> gaugeValues =
                gaugeValueDao.readGaugeValues("the gp", "the gauge:attr1", 0, 300000, 1);
        assertThat(gaugeValues).hasSize(1);
        assertThat(gaugeValues.get(0).getValue()).isEqualTo(500);
        assertThat(gaugeValues.get(0).getWeight()).isEqualTo(2);
    }

    private static List<GaugeValue> createData(int captureTime) {
        List<GaugeValue> gaugeValues = Lists.newArrayList();
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
