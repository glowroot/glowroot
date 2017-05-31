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

import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.Sessions;
import org.glowroot.common.repo.AgentRollupRepository.AgentRollup;

import static org.assertj.core.api.Assertions.assertThat;

public class AgentDaoIT {

    private static Cluster cluster;
    private static Session session;
    private static ClusterManager clusterManager;
    private static AgentRollupDao agentRollupDao;

    @BeforeClass
    public static void setUp() throws Exception {
        SharedSetupRunListener.startCassandra();
        cluster = Clusters.newCluster();
        session = cluster.newSession();
        Sessions.createKeyspaceIfNotExists(session, "glowroot_unit_tests");
        session.execute("use glowroot_unit_tests");
        clusterManager = ClusterManager.create();

        agentRollupDao = new AgentRollupDao(session, clusterManager);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        clusterManager.close();
        session.close();
        cluster.close();
        SharedSetupRunListener.stopCassandra();
    }

    @Before
    public void before() {
        session.execute("truncate agent_rollup");
    }

    @Test
    public void shouldReadAgentRollups() throws Exception {
        // given
        agentRollupDao.store("a", null);
        // when
        List<AgentRollup> agentRollups = agentRollupDao.readAgentRollups();
        // then
        assertThat(agentRollups).hasSize(1);
        AgentRollup agentRollup = agentRollups.get(0);
        assertThat(agentRollup.id()).isEqualTo("a");
        assertThat(agentRollup.display()).isEqualTo("a");
    }

    @Test
    public void shouldReadNullAgentRollup() throws Exception {
        // given
        agentRollupDao.store("a", null);
        // when
        List<String> agentRollupIds = agentRollupDao.readAgentRollupIds("a");
        // then
        assertThat(agentRollupIds).containsExactly("a");
    }

    @Test
    public void shouldReadSingleLevelAgentRollup() throws Exception {
        // given
        agentRollupDao.store("a", "x");
        // when
        List<String> agentRollupIds = agentRollupDao.readAgentRollupIds("a");
        // then
        assertThat(agentRollupIds).containsExactly("a", "x");
    }

    @Test
    public void shouldReadMultiLevelAgentRollup() throws Exception {
        // given
        agentRollupDao.store("a", "x/y/z");
        // when
        List<String> agentRollupIds = agentRollupDao.readAgentRollupIds("a");
        // then
        assertThat(agentRollupIds).containsExactly("a", "x/y/z", "x/y", "x");
    }

    @Test
    public void shouldNotInsertInvalidRow() throws Exception {
        // when
        agentRollupDao.updateLastCaptureTime("a", 5).get();
        // then
        assertThat(agentRollupDao.readAgentRollups()).isEmpty();
    }

    @Test
    public void shouldCalculateRollupIds() {
        assertThat(AgentRollupDao.getAgentRollupIds("a")).containsExactly("a");
        assertThat(AgentRollupDao.getAgentRollupIds("a/b")).containsExactly("a", "a/b");
        assertThat(AgentRollupDao.getAgentRollupIds("a/b/c")).containsExactly("a", "a/b", "a/b/c");
    }
}
