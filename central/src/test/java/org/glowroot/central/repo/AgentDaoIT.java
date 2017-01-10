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
import com.datastax.driver.core.Session;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.central.util.Sessions;
import org.glowroot.common.repo.AgentRepository.AgentRollup;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.Environment;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.HostInfo;

import static org.assertj.core.api.Assertions.assertThat;

public class AgentDaoIT {

    private static Cluster cluster;
    private static Session session;
    private static AgentDao agentDao;

    @BeforeClass
    public static void setUp() throws Exception {
        SharedSetupRunListener.startCassandra();
        cluster = Clusters.newCluster();
        session = cluster.newSession();
        Sessions.createKeyspaceIfNotExists(session, "glowroot_unit_tests");
        session.execute("use glowroot_unit_tests");

        agentDao = new AgentDao(session);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        session.close();
        cluster.close();
        SharedSetupRunListener.stopCassandra();
    }

    @Before
    public void before() {
        session.execute("truncate agent");
        session.execute("truncate agent_rollup");
    }

    @Test
    public void shouldStoreAgentConfig() throws Exception {
        // given
        AgentConfig agentConfig = AgentConfig.newBuilder()
                .setAgentVersion("123")
                .build();
        agentDao.store("a", null, Environment.getDefaultInstance(), agentConfig);
        // when
        AgentConfig readAgentConfig = agentDao.readAgentConfig("a");
        // then
        assertThat(readAgentConfig).isEqualTo(agentConfig);
    }

    @Test
    public void shouldNotOverwriteExistingAgentConfig() throws Exception {
        // given
        AgentConfig agentConfig = AgentConfig.newBuilder()
                .setAgentVersion("123")
                .build();
        agentDao.store("a", null, Environment.getDefaultInstance(), agentConfig);
        agentDao.store("a", null, Environment.getDefaultInstance(), AgentConfig.newBuilder()
                .setAgentVersion("456")
                .build());
        // when
        AgentConfig readAgentConfig = agentDao.readAgentConfig("a");
        // then
        assertThat(readAgentConfig).isEqualTo(agentConfig);
    }

    @Test
    public void shouldStoreEnvironment() throws Exception {
        // given
        Environment environment = Environment.newBuilder()
                .setHostInfo(HostInfo.newBuilder()
                        .setHostName("hosty"))
                .build();
        agentDao.store("a", null, environment, AgentConfig.getDefaultInstance());
        // when
        Environment readEnvironment = agentDao.readEnvironment("a");
        // then
        assertThat(readEnvironment).isEqualTo(environment);
    }

    @Test
    public void shouldReadAgentRollups() throws Exception {
        // given
        agentDao.store("a", null, Environment.getDefaultInstance(),
                AgentConfig.getDefaultInstance());
        // when
        List<AgentRollup> agentRollups = agentDao.readAgentRollups();
        // then
        assertThat(agentRollups).hasSize(1);
        AgentRollup agentRollup = agentRollups.get(0);
        assertThat(agentRollup.id()).isEqualTo("a");
        assertThat(agentRollup.display()).isEqualTo("a");
    }

    @Test
    public void shouldReadNullAgentRollup() throws Exception {
        // given
        agentDao.store("a", null, Environment.getDefaultInstance(),
                AgentConfig.getDefaultInstance());
        // when
        List<String> agentRollupIds = agentDao.readAgentRollupIds("a");
        // then
        assertThat(agentRollupIds).containsExactly("a");
    }

    @Test
    public void shouldReadSingleLevelAgentRollup() throws Exception {
        // given
        agentDao.store("a", "x", Environment.getDefaultInstance(),
                AgentConfig.getDefaultInstance());
        // when
        List<String> agentRollupIds = agentDao.readAgentRollupIds("a");
        // then
        assertThat(agentRollupIds).containsExactly("a", "x");
    }

    @Test
    public void shouldReadMultiLevelAgentRollup() throws Exception {
        // given
        agentDao.store("a", "x/y/z", Environment.getDefaultInstance(),
                AgentConfig.getDefaultInstance());
        // when
        List<String> agentRollupIds = agentDao.readAgentRollupIds("a");
        // then
        assertThat(agentRollupIds).containsExactly("a", "x/y/z", "x/y", "x");
    }

    @Test
    public void shouldCalculateRollupIds() {
        assertThat(AgentDao.getAgentRollupIds("a")).containsExactly("a");
        assertThat(AgentDao.getAgentRollupIds("a/b")).containsExactly("a", "a/b");
        assertThat(AgentDao.getAgentRollupIds("a/b/c")).containsExactly("a", "a/b", "a/b/c");
    }
}
