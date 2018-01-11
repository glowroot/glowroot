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

import com.datastax.driver.core.Cluster;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.Session;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.TransactionConfig;
import org.glowroot.wire.api.model.Proto.OptionalInt32;

import static org.assertj.core.api.Assertions.assertThat;

public class ConfigDaoIT {

    private static Cluster cluster;
    private static Session session;
    private static ClusterManager clusterManager;
    private static AgentConfigDao agentConfigDao;

    @BeforeClass
    public static void setUp() throws Exception {
        SharedSetupRunListener.startCassandra();
        cluster = Clusters.newCluster();
        session = new Session(cluster.newSession());
        session.createKeyspaceIfNotExists("glowroot_unit_tests");
        session.execute("use glowroot_unit_tests");
        clusterManager = ClusterManager.create();

        agentConfigDao = new AgentConfigDao(session, clusterManager);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        clusterManager.close();
        session.close();
        cluster.close();
        SharedSetupRunListener.stopCassandra();
    }

    @Before
    public void before() throws Exception {
        session.execute("truncate agent_config");
    }

    @Test
    public void shouldStoreAgentConfig() throws Exception {
        // given
        AgentConfig agentConfig = AgentConfig.getDefaultInstance();
        agentConfigDao.store("a", agentConfig);
        // when
        AgentConfig readAgentConfig = agentConfigDao.read("a");
        // then
        assertThat(readAgentConfig).isEqualTo(agentConfig);
    }

    @Test
    public void shouldNotOverwriteExistingAgentConfig() throws Exception {
        // given
        AgentConfig agentConfig = AgentConfig.getDefaultInstance();
        agentConfigDao.store("a", agentConfig);
        agentConfigDao.store("a", AgentConfig.newBuilder()
                .setTransactionConfig(TransactionConfig.newBuilder()
                        .setSlowThresholdMillis(OptionalInt32.newBuilder()
                                .setValue(1234)))
                .build());
        // when
        AgentConfig readAgentConfig = agentConfigDao.read("a");
        // then
        assertThat(readAgentConfig).isEqualTo(agentConfig);
    }
}
