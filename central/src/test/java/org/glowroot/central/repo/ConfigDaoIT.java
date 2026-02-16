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
import com.google.common.util.concurrent.MoreExecutors;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.Session;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.TransactionConfig;
import org.glowroot.wire.api.model.Proto.OptionalInt32;
import org.junit.jupiter.api.*;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
public class ConfigDaoIT {

    @Container
    public final CassandraContainer cassandra
            = new CassandraContainer("cassandra:3.11.16").withExposedPorts(9042);

    private static ExecutorService asyncExecutor;
    private ClusterManager clusterManager;
    private CqlSessionBuilder cqlSessionBuilder;
    private Session session;
    private AgentConfigDao agentConfigDao;

    @BeforeAll
    public static void setUp() throws Exception {
        asyncExecutor = Executors.newCachedThreadPool();
    }

    @AfterAll
    public static void afterAll() throws Exception {
        if (asyncExecutor != null) {
            asyncExecutor.shutdown();
        }
    }

    @BeforeEach
    public void before() throws Exception {
        clusterManager = ClusterManager.create();
        cqlSessionBuilder = CqlSession
                .builder()
                .addContactPoint(cassandra.getContactPoint())
                .withLocalDatacenter(cassandra.getLocalDatacenter())
                .withConfigLoader(DriverConfigLoader.fromClasspath("datastax-driver.conf"));
        session = new Session(cqlSessionBuilder.build(), "glowroot_unit_tests", null, 0);
        AgentDisplayDao agentDisplayDao =
                new AgentDisplayDao(session, clusterManager, MoreExecutors.directExecutor(), 10);
        agentConfigDao = new AgentConfigDao(session, agentDisplayDao, clusterManager, 10, MoreExecutors.directExecutor());

        session.updateSchemaWithRetry("truncate agent_config");
    }

    @AfterEach
    public void tearDown() throws Exception {
        try(var se = session; var cm = clusterManager) {}
    }

    @Test
    public void shouldStoreAgentConfig() throws Exception {
        // given
        AgentConfig agentConfig = AgentConfig.getDefaultInstance();
        agentConfigDao.store("a", agentConfig, false).toCompletableFuture().get();
        // when
        AgentConfig readAgentConfig = agentConfigDao.readAsync("a").get();
        // then
        assertThat(readAgentConfig).isEqualTo(agentConfig);
    }

    @Test
    public void shouldNotOverwriteExistingAgentConfig() throws Exception {
        // given
        AgentConfig agentConfig = AgentConfig.getDefaultInstance();
        agentConfigDao.store("a", agentConfig, false).toCompletableFuture().get();
        agentConfigDao.store("a", AgentConfig.newBuilder()
                .setTransactionConfig(TransactionConfig.newBuilder()
                        .setSlowThresholdMillis(OptionalInt32.newBuilder()
                                .setValue(1234)))
                .build(), false).toCompletableFuture().get();
        // when
        AgentConfig readAgentConfig = agentConfigDao.readAsync("a").get();
        // then
        assertThat(readAgentConfig).isEqualTo(agentConfig);
    }
}
