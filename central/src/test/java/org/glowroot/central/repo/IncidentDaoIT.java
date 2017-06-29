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
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.central.util.Session;
import org.glowroot.common.repo.IncidentRepository.OpenIncident;
import org.glowroot.common.repo.IncidentRepository.ResolvedIncident;
import org.glowroot.common.util.Clock;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition.HeartbeatCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition.MetricCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertNotification;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertSeverity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// NOTE this is mostly a copy of IncidentDaoTest in glowroot-agent-core
public class IncidentDaoIT {

    private static final String AGENT_ID = "xyz";

    private static Cluster cluster;
    private static Session session;
    private static IncidentDao incidentDao;

    @BeforeClass
    public static void setUp() throws Exception {
        SharedSetupRunListener.startCassandra();
        cluster = Clusters.newCluster();
        session = new Session(cluster.newSession());
        session.createKeyspaceIfNotExists("glowroot_unit_tests");
        session.execute("use glowroot_unit_tests");

        Clock clock = mock(Clock.class);
        when(clock.currentTimeMillis()).thenReturn(345L);
        incidentDao = new IncidentDao(session, clock);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        session.close();
        cluster.close();
        SharedSetupRunListener.stopCassandra();
    }

    @Before
    public void beforeEach() throws Exception {
        session.execute("truncate open_incident");
        session.execute("truncate resolved_incident");
    }

    @Test
    public void shouldNotExist() throws Exception {
        AlertCondition alertCondition = AlertCondition.newBuilder()
                .setHeartbeatCondition(HeartbeatCondition.newBuilder()
                        .setTimePeriodSeconds(60))
                .build();
        assertThat(incidentDao.readOpenIncident(AGENT_ID, alertCondition, AlertSeverity.HIGH))
                .isNull();
    }

    @Test
    public void shouldExistAfterInsert() throws Exception {
        // given
        AlertCondition alertCondition = AlertCondition.newBuilder()
                .setHeartbeatCondition(HeartbeatCondition.newBuilder()
                        .setTimePeriodSeconds(60))
                .build();
        AlertCondition otherAlertCondition = AlertCondition.newBuilder()
                .setMetricCondition(MetricCondition.newBuilder()
                        .setMetric("gauge:abc")
                        .setThreshold(5)
                        .setTimePeriodSeconds(60))
                .build();
        // when
        incidentDao.insertOpenIncident(AGENT_ID, alertCondition, AlertSeverity.HIGH,
                AlertNotification.getDefaultInstance(), 123);
        // then
        assertThat(incidentDao.readOpenIncident(AGENT_ID, otherAlertCondition, AlertSeverity.HIGH))
                .isNull();
        OpenIncident openIncident =
                incidentDao.readOpenIncident(AGENT_ID, alertCondition, AlertSeverity.HIGH);
        assertThat(openIncident).isNotNull();
        assertThat(openIncident.openTime()).isEqualTo(123);
    }

    @Test
    public void shouldNotBeOpenAfterClose() throws Exception {
        // given
        AlertCondition alertCondition = AlertCondition.newBuilder()
                .setHeartbeatCondition(HeartbeatCondition.newBuilder()
                        .setTimePeriodSeconds(60))
                .build();
        // when
        incidentDao.insertOpenIncident(AGENT_ID, alertCondition, AlertSeverity.HIGH,
                AlertNotification.getDefaultInstance(), 234);
        OpenIncident openIncident =
                incidentDao.readOpenIncident(AGENT_ID, alertCondition, AlertSeverity.HIGH);
        incidentDao.resolveIncident(openIncident, 345);
        // then
        assertThat(incidentDao.readOpenIncident(AGENT_ID, alertCondition, AlertSeverity.HIGH))
                .isNull();
    }

    @Test
    public void shouldBeClosedAfterClose() throws Exception {
        // given
        AlertCondition alertCondition = AlertCondition.newBuilder()
                .setHeartbeatCondition(HeartbeatCondition.newBuilder()
                        .setTimePeriodSeconds(60))
                .build();
        // when
        incidentDao.insertOpenIncident(AGENT_ID, alertCondition, AlertSeverity.HIGH,
                AlertNotification.getDefaultInstance(), 234);
        OpenIncident openIncident =
                incidentDao.readOpenIncident(AGENT_ID, alertCondition, AlertSeverity.HIGH);
        incidentDao.resolveIncident(openIncident, 345);
        List<ResolvedIncident> resolvedIncidents = incidentDao.readResolvedIncidents(345);
        // then
        assertThat(resolvedIncidents).hasSize(1);
        assertThat(resolvedIncidents.get(0).condition()).isEqualTo(alertCondition);
    }

    @Test
    public void shouldReadAll() throws Exception {
        // given
        AlertCondition alertCondition = AlertCondition.newBuilder()
                .setHeartbeatCondition(HeartbeatCondition.newBuilder()
                        .setTimePeriodSeconds(60))
                .build();
        AlertCondition alertCondition2 = AlertCondition.newBuilder()
                .setMetricCondition(MetricCondition.newBuilder()
                        .setMetric("gauge:abc")
                        .setThreshold(5)
                        .setTimePeriodSeconds(60))
                .build();
        // when
        incidentDao.insertOpenIncident("xyz", alertCondition, AlertSeverity.HIGH,
                AlertNotification.getDefaultInstance(), 456);
        incidentDao.insertOpenIncident("abc", alertCondition2, AlertSeverity.HIGH,
                AlertNotification.getDefaultInstance(), 567);
        // then
        List<OpenIncident> openIncidents = incidentDao.readAllOpenIncidents();
        assertThat(openIncidents).hasSize(2);
    }
}
