/*
 * Copyright 2016-2018 the original author or authors.
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
import org.glowroot.central.util.Session;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.common2.repo.IncidentRepository.OpenIncident;
import org.glowroot.common2.repo.IncidentRepository.ResolvedIncident;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition.HeartbeatCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition.MetricCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertNotification;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertSeverity;
import org.junit.jupiter.api.*;
import org.testcontainers.cassandra.CassandraContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// NOTE this is mostly a copy of IncidentDaoTest in glowroot-agent-embedded
public class IncidentDaoIT {
    public static final CassandraContainer cassandra
            = new CassandraContainer("cassandra:3.11.16").withExposedPorts(9042);

    private static final String AGENT_ID = "xyz";

    private CqlSessionBuilder cqlSessionBuilder;
    private Session session;
    private IncidentDao incidentDao;

    @BeforeAll
    public static void beforeClass() {
        cassandra.start();
    }

    @AfterAll
    public static void afterClass() {
        cassandra.stop();
    }

    @BeforeEach
    public void setUp() throws Exception {
        cqlSessionBuilder = CqlSession
                .builder()
                .addContactPoint(cassandra.getContactPoint())
                .withLocalDatacenter(cassandra.getLocalDatacenter())
                .withConfigLoader(DriverConfigLoader.fromClasspath("datastax-driver.conf"));
        session = new Session(cqlSessionBuilder.build(), "glowroot_unit_tests", null, 0);

        Clock clock = mock(Clock.class);
        when(clock.currentTimeMillis()).thenReturn(345L);
        incidentDao = new IncidentDao(session, clock);
        session.updateSchemaWithRetry("truncate open_incident");
        session.updateSchemaWithRetry("truncate resolved_incident");
    }

    @AfterEach
    public void tearDown() throws Exception {
        try (var se = session) {
        }
    }


    @Test
    public void shouldNotExist() throws Exception {
        AlertCondition alertCondition = AlertCondition.newBuilder()
                .setHeartbeatCondition(HeartbeatCondition.newBuilder()
                        .setTimePeriodSeconds(60))
                .build();
        assertThat(incidentDao.readOpenIncident(AGENT_ID, alertCondition, AlertSeverity.HIGH, CassandraProfile.web).toCompletableFuture().join())
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
                AlertNotification.getDefaultInstance(), 123, CassandraProfile.web).toCompletableFuture().join();
        // then
        assertThat(incidentDao.readOpenIncident(AGENT_ID, otherAlertCondition, AlertSeverity.HIGH, CassandraProfile.web).toCompletableFuture().join())
                .isNull();
        OpenIncident openIncident =
                incidentDao.readOpenIncident(AGENT_ID, alertCondition, AlertSeverity.HIGH, CassandraProfile.web).toCompletableFuture().join();
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
                AlertNotification.getDefaultInstance(), 234, CassandraProfile.web).toCompletableFuture().get();
        OpenIncident openIncident =
                incidentDao.readOpenIncident(AGENT_ID, alertCondition, AlertSeverity.HIGH, CassandraProfile.web).toCompletableFuture().join();
        incidentDao.resolveIncident(openIncident, 345, CassandraProfile.web).toCompletableFuture().get();
        // then
        assertThat(incidentDao.readOpenIncident(AGENT_ID, alertCondition, AlertSeverity.HIGH, CassandraProfile.web).toCompletableFuture().join())
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
                AlertNotification.getDefaultInstance(), 234, CassandraProfile.web).toCompletableFuture().join();
        OpenIncident openIncident =
                incidentDao.readOpenIncident(AGENT_ID, alertCondition, AlertSeverity.HIGH, CassandraProfile.web).toCompletableFuture().join();
        incidentDao.resolveIncident(openIncident, 345, CassandraProfile.web).toCompletableFuture().join();
        List<ResolvedIncident> resolvedIncidents = incidentDao.readResolvedIncidents(345).toCompletableFuture().join();
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
                AlertNotification.getDefaultInstance(), 456, CassandraProfile.web).toCompletableFuture().join();
        incidentDao.insertOpenIncident("abc", alertCondition2, AlertSeverity.HIGH,
                AlertNotification.getDefaultInstance(), 567, CassandraProfile.web).toCompletableFuture().join();
        // then
        List<OpenIncident> openIncidents = incidentDao.readAllOpenIncidents(CassandraProfile.collector).toCompletableFuture().join();
        assertThat(openIncidents).hasSize(2);
    }
}
