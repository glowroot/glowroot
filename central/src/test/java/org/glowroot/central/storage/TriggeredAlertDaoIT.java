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
package org.glowroot.central.storage;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Session;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.common.config.ImmutableCentralStorageConfig;
import org.glowroot.common.repo.ConfigRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// NOTE this is mostly a copy of TriggeredAlertDaoTest in glowroot-agent
public class TriggeredAlertDaoIT {

    private static final String AGENT_ID = "xyz";

    private static Cluster cluster;
    private static Session session;
    private static TriggeredAlertDao triggeredAlertDao;

    @BeforeClass
    public static void setUp() throws Exception {
        SharedSetupRunListener.startCassandra();
        cluster = Clusters.newCluster();
        session = cluster.newSession();
        Sessions.createKeyspaceIfNotExists(session, "glowroot_unit_tests");
        session.execute("use glowroot_unit_tests");

        ConfigRepository configRepository = mock(ConfigRepository.class);
        when(configRepository.getStorageConfig())
                .thenReturn(ImmutableCentralStorageConfig.builder().build());
        triggeredAlertDao = new TriggeredAlertDao(session, configRepository);

        session.execute("truncate triggered_alert");
    }

    @AfterClass
    public static void tearDown() throws Exception {
        session.close();
        cluster.close();
        SharedSetupRunListener.stopCassandra();
    }

    @Test
    public void shouldNotExist() throws Exception {
        assertThat(triggeredAlertDao.exists(AGENT_ID, "vvv1")).isFalse();
    }

    @Test
    public void shouldExistAfterInsert() throws Exception {
        triggeredAlertDao.insert(AGENT_ID, "vvv2");
        assertThat(triggeredAlertDao.exists(AGENT_ID, "vvv1")).isFalse();
        assertThat(triggeredAlertDao.exists(AGENT_ID, "vvv2")).isTrue();
    }

    @Test
    public void shouldNotExistAfterDelete() throws Exception {
        triggeredAlertDao.insert(AGENT_ID, "vvv3");
        triggeredAlertDao.delete(AGENT_ID, "vvv3");
        assertThat(triggeredAlertDao.exists(AGENT_ID, "vvv3")).isFalse();
    }
}
