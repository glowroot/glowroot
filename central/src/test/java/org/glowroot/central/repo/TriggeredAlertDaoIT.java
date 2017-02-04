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
import org.glowroot.common.repo.TriggeredAlertRepository.TriggeredAlert;

import static org.assertj.core.api.Assertions.assertThat;

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

        triggeredAlertDao = new TriggeredAlertDao(session);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        session.close();
        cluster.close();
        SharedSetupRunListener.stopCassandra();
    }

    @Before
    public void beforeEach() {
        session.execute("truncate triggered_alert");
    }

    @Test
    public void shouldNotExist() throws Exception {
        assertThat(triggeredAlertDao.exists(AGENT_ID, "1111")).isFalse();
    }

    @Test
    public void shouldExistAfterInsert() throws Exception {
        triggeredAlertDao.insert(AGENT_ID, "2222");
        assertThat(triggeredAlertDao.exists(AGENT_ID, "1111")).isFalse();
        assertThat(triggeredAlertDao.exists(AGENT_ID, "2222")).isTrue();
    }

    @Test
    public void shouldNotExistAfterDelete() throws Exception {
        triggeredAlertDao.insert(AGENT_ID, "3333");
        triggeredAlertDao.delete(AGENT_ID, "3333");
        assertThat(triggeredAlertDao.exists(AGENT_ID, "3333")).isFalse();
    }

    @Test
    public void shouldReadAll() throws Exception {
        triggeredAlertDao.insert("xyz", "444");
        triggeredAlertDao.insert("abc", "555");
        List<TriggeredAlert> triggeredAlerts = triggeredAlertDao.readAll();
        assertThat(triggeredAlerts).hasSize(2);
    }
}
