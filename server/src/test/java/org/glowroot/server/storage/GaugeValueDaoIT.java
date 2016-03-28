/*
 * Copyright 2016 the original author or authors.
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
package org.glowroot.server.storage;

import java.util.List;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Session;
import com.google.common.collect.Lists;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValue;

import static org.assertj.core.api.Assertions.assertThat;

public class GaugeValueDaoIT {

    private static Session session;
    private static GaugeValueDao gaugeValueDao;

    @BeforeClass
    public static void setUp() throws Exception {
        CassandraWrapper.start();
        Cluster cluster = Cluster.builder().addContactPoint("127.0.0.1").build();
        session = cluster.newSession();
        session.execute("create keyspace if not exists glowroot with replication ="
                + " { 'class' : 'SimpleStrategy', 'replication_factor' : 1 }");
        session.execute("use glowroot");

        AgentDao agentDao = new AgentDao(session);
        ServerConfigDao serverConfigDao = new ServerConfigDao(session);
        ConfigRepository configRepository = new ConfigRepositoryImpl(agentDao, serverConfigDao);
        gaugeValueDao = new GaugeValueDao(session, agentDao, configRepository);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        session.close();
        CassandraWrapper.stop();
    }

    @Test
    public void shouldRollup() throws Exception {
        gaugeValueDao.truncateAll();
        gaugeValueDao.store("one", createData(60013));
        gaugeValueDao.store("one", createData(65009));

        // check non-rolled up data
        List<GaugeValue> gaugeValues =
                gaugeValueDao.readGaugeValues("one", "the gauge:attr1", 0, 300000, 0);
        assertThat(gaugeValues).hasSize(2);
        assertThat(gaugeValues.get(0).getValue()).isEqualTo(500);
        assertThat(gaugeValues.get(0).getWeight()).isEqualTo(1);
        assertThat(gaugeValues.get(1).getValue()).isEqualTo(500);
        assertThat(gaugeValues.get(1).getWeight()).isEqualTo(1);

        // check that rolled-up data does not exist before rollup
        gaugeValues = gaugeValueDao.readGaugeValues("one", "the gauge:attr1", 0, 300000, 1);
        assertThat(gaugeValues).isEmpty();

        // rollup
        gaugeValueDao.rollup(300001);
        gaugeValueDao.rollup(300001);
        gaugeValueDao.rollup(300001);

        // check rolled-up data after rollup
        gaugeValues = gaugeValueDao.readGaugeValues("one", "the gauge:attr1", 0, 300000, 1);
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
