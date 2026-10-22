/*
 * Copyright 2026 the original author or authors.
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
package org.glowroot.central.storage.postgres;

import java.util.List;
import java.util.concurrent.ExecutorService;

import com.zaxxer.hikari.HikariDataSource;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.common2.repo.IncidentRepository.OpenIncident;
import org.glowroot.common2.repo.IncidentRepository.ResolvedIncident;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition.HeartbeatCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition.MetricCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertNotification;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertSeverity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
@Tag("postgres-store")
public class PostgresIncidentIT {

    private static final String AGENT_ID = "xyz";

    @Container
    @SuppressWarnings("resource")
    public final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private HikariDataSource dataSource;
    private ExecutorService asyncExecutor;
    private PostgresIncidentDao incidentDao;

    @BeforeEach
    public void before() throws Exception {
        dataSource = PostgresDataSourceFactory.create(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword(), 5);
        asyncExecutor = PostgresAsync.newExecutor(5);
        Clock clock = mock(Clock.class);
        when(clock.currentTimeMillis()).thenReturn(345L);
        PostgresSchemaUpgrade.upgrade(dataSource);
        incidentDao = new PostgresIncidentDao(dataSource, clock, asyncExecutor);
    }

    @AfterEach
    public void after() {
        PostgresAsync.shutdownQuietly(asyncExecutor);
        PostgresDataSourceFactory.closeQuietly(dataSource);
    }

    @Test
    public void shouldNotExist() {
        AlertCondition alertCondition = AlertCondition.newBuilder()
                .setHeartbeatCondition(HeartbeatCondition.newBuilder()
                        .setTimePeriodSeconds(60))
                .build();
        assertThat(incidentDao.readOpenIncident(AGENT_ID, alertCondition, AlertSeverity.HIGH,
                CassandraProfile.web).toCompletableFuture().join()).isNull();
    }

    @Test
    public void shouldExistAfterInsert() {
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
        incidentDao.insertOpenIncident(AGENT_ID, alertCondition, AlertSeverity.HIGH,
                AlertNotification.getDefaultInstance(), 123, CassandraProfile.web)
                .toCompletableFuture().join();
        assertThat(incidentDao.readOpenIncident(AGENT_ID, otherAlertCondition, AlertSeverity.HIGH,
                CassandraProfile.web).toCompletableFuture().join()).isNull();
        OpenIncident openIncident = incidentDao
                .readOpenIncident(AGENT_ID, alertCondition, AlertSeverity.HIGH, CassandraProfile.web)
                .toCompletableFuture().join();
        assertThat(openIncident).isNotNull();
        assertThat(openIncident.openTime()).isEqualTo(123);
    }

    @Test
    public void shouldResolve() {
        AlertCondition alertCondition = AlertCondition.newBuilder()
                .setHeartbeatCondition(HeartbeatCondition.newBuilder()
                        .setTimePeriodSeconds(60))
                .build();
        incidentDao.insertOpenIncident(AGENT_ID, alertCondition, AlertSeverity.HIGH,
                AlertNotification.getDefaultInstance(), 234, CassandraProfile.web)
                .toCompletableFuture().join();
        OpenIncident openIncident = incidentDao
                .readOpenIncident(AGENT_ID, alertCondition, AlertSeverity.HIGH, CassandraProfile.web)
                .toCompletableFuture().join();
        incidentDao.resolveIncident(openIncident, 345, CassandraProfile.web).toCompletableFuture()
                .join();
        assertThat(incidentDao.readOpenIncident(AGENT_ID, alertCondition, AlertSeverity.HIGH,
                CassandraProfile.web).toCompletableFuture().join()).isNull();
        List<ResolvedIncident> resolved =
                incidentDao.readResolvedIncidents(345).toCompletableFuture().join();
        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0).condition()).isEqualTo(alertCondition);
    }

    @Test
    public void shouldReadAll() {
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
        incidentDao.insertOpenIncident("xyz", alertCondition, AlertSeverity.HIGH,
                AlertNotification.getDefaultInstance(), 456, CassandraProfile.web)
                .toCompletableFuture().join();
        incidentDao.insertOpenIncident("abc", alertCondition2, AlertSeverity.HIGH,
                AlertNotification.getDefaultInstance(), 567, CassandraProfile.web)
                .toCompletableFuture().join();
        assertThat(incidentDao.readAllOpenIncidents(CassandraProfile.collector).toCompletableFuture()
                .join()).hasSize(2);
    }
}
