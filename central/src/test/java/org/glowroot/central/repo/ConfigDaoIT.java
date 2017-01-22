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
import com.datastax.driver.core.Session;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.central.util.Sessions;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;

import static org.assertj.core.api.Assertions.assertThat;

public class ConfigDaoIT {

    private static Cluster cluster;
    private static Session session;
    private static ConfigDao configDao;

    @BeforeClass
    public static void setUp() throws Exception {
        SharedSetupRunListener.startCassandra();
        cluster = Clusters.newCluster();
        session = cluster.newSession();
        Sessions.createKeyspaceIfNotExists(session, "glowroot_unit_tests");
        session.execute("use glowroot_unit_tests");

        configDao = new ConfigDao(session);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        session.close();
        cluster.close();
        SharedSetupRunListener.stopCassandra();
    }

    @Before
    public void before() {
        session.execute("truncate config");
    }

    @Test
    public void shouldStoreAgentConfig() throws Exception {
        // given
        AgentConfig agentConfig = AgentConfig.newBuilder()
                .setAgentVersion("123")
                .build();
        configDao.store("a", null, agentConfig);
        // when
        AgentConfig readAgentConfig = configDao.read("a");
        // then
        assertThat(readAgentConfig).isEqualTo(agentConfig);
    }

    @Test
    public void shouldNotOverwriteExistingAgentConfig() throws Exception {
        // given
        AgentConfig agentConfig = AgentConfig.newBuilder()
                .setAgentVersion("123")
                .build();
        configDao.store("a", null, agentConfig);
        configDao.store("a", null, AgentConfig.newBuilder()
                .setAgentVersion("456")
                .build());
        // when
        AgentConfig readAgentConfig = configDao.read("a");
        // then
        assertThat(readAgentConfig).isEqualTo(agentConfig);
    }
}
