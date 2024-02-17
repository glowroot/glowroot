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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.google.common.util.concurrent.MoreExecutors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.Session;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.TransactionConfig;
import org.glowroot.wire.api.model.Proto.OptionalInt32;

import static org.assertj.core.api.Assertions.assertThat;

public class ConfigDaoIT {

    private static ClusterManager clusterManager;
    private static CqlSessionBuilder cqlSessionBuilder;
    private static Session session;
    private static ExecutorService asyncExecutor;
    private static AgentConfigDao agentConfigDao;

    @BeforeAll
    public static void setUp() throws Exception {
        SharedSetupRunListener.startCassandra();
        clusterManager = ClusterManager.create();
        cqlSessionBuilder = CqlSessionBuilders.newCqlSessionBuilder();
        session = new Session(cqlSessionBuilder.build(), "glowroot_unit_tests", null, 0);
        asyncExecutor = Executors.newCachedThreadPool();
        AgentDisplayDao agentDisplayDao =
                new AgentDisplayDao(session, clusterManager, MoreExecutors.directExecutor(), 10);
        agentConfigDao = new AgentConfigDao(session, agentDisplayDao, clusterManager, 10);
    }

    @AfterAll
    public static void tearDown() throws Exception {
        if (!SharedSetupRunListener.isStarted()) {
            return;
        }
        try (var se = session;
             var cm = clusterManager) {
            if (asyncExecutor != null) {
                asyncExecutor.shutdown();
            }
        } finally {
            SharedSetupRunListener.stopCassandra();
        }
    }

    @BeforeEach
    public void before() throws Exception {
        session.updateSchemaWithRetry("truncate agent_config");
    }

    @Test
    public void shouldStoreAgentConfig() throws Exception {
        // given
        AgentConfig agentConfig = AgentConfig.getDefaultInstance();
        agentConfigDao.store("a", agentConfig, false);
        // when
        AgentConfig readAgentConfig = agentConfigDao.read("a");
        // then
        assertThat(readAgentConfig).isEqualTo(agentConfig);
    }

    @Test
    public void shouldNotOverwriteExistingAgentConfig() throws Exception {
        // given
        AgentConfig agentConfig = AgentConfig.getDefaultInstance();
        agentConfigDao.store("a", agentConfig, false);
        agentConfigDao.store("a", AgentConfig.newBuilder()
                .setTransactionConfig(TransactionConfig.newBuilder()
                        .setSlowThresholdMillis(OptionalInt32.newBuilder()
                                .setValue(1234)))
                .build(), false);
        // when
        AgentConfig readAgentConfig = agentConfigDao.read("a");
        // then
        assertThat(readAgentConfig).isEqualTo(agentConfig);
    }
}
