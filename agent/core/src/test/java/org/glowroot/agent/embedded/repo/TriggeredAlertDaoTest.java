/*
 * Copyright 2013-2017 the original author or authors.
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
package org.glowroot.agent.embedded.repo;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.glowroot.agent.embedded.util.DataSource;
import org.glowroot.common.repo.TriggeredAlertRepository.TriggeredAlert;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertKind;

import static org.assertj.core.api.Assertions.assertThat;

// NOTE this is mostly a copy of TriggeredAlertDaoIT in glowroot-central
//
// this is not an integration test (*IT.java) since then it would run against shaded agent and fail
// due to shading issues
public class TriggeredAlertDaoTest {

    private static final String AGENT_ID = "";

    private DataSource dataSource;
    private TriggeredAlertDao triggeredAlertDao;

    @Before
    public void beforeEachTest() throws Exception {
        dataSource = new DataSource();
        if (dataSource.tableExists("triggered_alert")) {
            dataSource.execute("drop table triggered_alert");
        }
        triggeredAlertDao = new TriggeredAlertDao(dataSource);
    }

    @After
    public void afterEachTest() throws Exception {
        dataSource.close();
    }

    @Test
    public void shouldNotExist() throws Exception {
        AlertConfig alertCondition = AlertConfig.newBuilder()
                .setKind(AlertKind.HEARTBEAT)
                .build();
        assertThat(triggeredAlertDao.exists(AGENT_ID, alertCondition)).isFalse();
    }

    @Test
    public void shouldExistAfterInsert() throws Exception {
        // given
        AlertConfig alertCondition = AlertConfig.newBuilder()
                .setKind(AlertKind.HEARTBEAT)
                .build();
        AlertConfig otherAlertConfig = AlertConfig.newBuilder()
                .setKind(AlertKind.GAUGE)
                .build();
        // when
        triggeredAlertDao.insert(AGENT_ID, alertCondition);
        // then
        assertThat(triggeredAlertDao.exists(AGENT_ID, otherAlertConfig)).isFalse();
        assertThat(triggeredAlertDao.exists(AGENT_ID, alertCondition)).isTrue();
    }

    @Test
    public void shouldNotExistAfterDelete() throws Exception {
        // given
        AlertConfig alertCondition = AlertConfig.newBuilder()
                .setKind(AlertKind.HEARTBEAT)
                .build();
        // when
        triggeredAlertDao.insert(AGENT_ID, alertCondition);
        triggeredAlertDao.delete(AGENT_ID, alertCondition);
        // then
        assertThat(triggeredAlertDao.exists(AGENT_ID, alertCondition)).isFalse();
    }

    @Test
    public void shouldReadAll() throws Exception {
        // given
        AlertConfig alertCondition = AlertConfig.newBuilder()
                .setKind(AlertKind.HEARTBEAT)
                .build();
        AlertConfig alertCondition2 = AlertConfig.newBuilder()
                .setKind(AlertKind.GAUGE)
                .build();
        // when
        triggeredAlertDao.insert("xyz", alertCondition);
        triggeredAlertDao.insert("abc", alertCondition2);
        // then
        List<TriggeredAlert> triggeredAlerts = triggeredAlertDao.readAll();
        assertThat(triggeredAlerts).hasSize(2);
    }
}
