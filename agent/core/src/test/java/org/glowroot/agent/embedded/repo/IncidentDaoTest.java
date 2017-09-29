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
import org.glowroot.common.repo.IncidentRepository.OpenIncident;
import org.glowroot.common.repo.IncidentRepository.ResolvedIncident;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition.HeartbeatCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition.MetricCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertNotification;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertSeverity;
import org.glowroot.wire.api.model.Proto.OptionalDouble;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

// NOTE this is mostly a copy of IncidentDaoTest in glowroot-central
//
// this is not an integration test (*IT.java) since then it would run against shaded agent and fail
// due to shading issues
public class IncidentDaoTest {

    private static final String AGENT_ID = "";

    private DataSource dataSource;
    private IncidentDao incidentDao;

    @Before
    public void beforeEachTest() throws Exception {
        dataSource = new DataSource();
        if (dataSource.tableExists("incident")) {
            dataSource.execute("drop table incident");
        }
        incidentDao = new IncidentDao(dataSource);
    }

    @After
    public void afterEachTest() throws Exception {
        dataSource.close();
    }

    @Test
    public void shouldNotExist() throws Exception {
        AlertCondition alertCondition = AlertCondition.newBuilder()
                .setMetricCondition(MetricCondition.newBuilder()
                        .setMetric("transaction:x-percentile")
                        .setTransactionType("Web")
                        .setPercentile(OptionalDouble.newBuilder().setValue(95))
                        .setThreshold(SECONDS.toNanos(2))
                        .setTimePeriodSeconds(60)
                        .setMinTransactionCount(100)
                        .build())
                .build();
        assertThat(incidentDao.readOpenIncident(AGENT_ID, alertCondition, AlertSeverity.HIGH))
                .isNull();
    }

    @Test
    public void shouldExistAfterInsert() throws Exception {
        // given
        AlertCondition alertCondition = AlertCondition.newBuilder()
                .setMetricCondition(MetricCondition.newBuilder()
                        .setMetric("transaction:x-percentile")
                        .setTransactionType("Web")
                        .setPercentile(OptionalDouble.newBuilder().setValue(95))
                        .setThreshold(SECONDS.toNanos(2))
                        .setTimePeriodSeconds(60)
                        .setMinTransactionCount(100)
                        .build())
                .build();
        AlertCondition otherAlertCondition = AlertCondition.newBuilder()
                .setMetricCondition(MetricCondition.newBuilder()
                        .setMetric("transaction:x-percentile")
                        .setTransactionType("Web")
                        .setPercentile(OptionalDouble.newBuilder().setValue(96))
                        .setThreshold(SECONDS.toNanos(2))
                        .setTimePeriodSeconds(60)
                        .setMinTransactionCount(100)
                        .build())
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
                .setMetricCondition(MetricCondition.newBuilder()
                        .setMetric("transaction:x-percentile")
                        .setTransactionType("Web")
                        .setPercentile(OptionalDouble.newBuilder().setValue(95))
                        .setThreshold(SECONDS.toNanos(2))
                        .setTimePeriodSeconds(60)
                        .setMinTransactionCount(100)
                        .build())
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
        List<ResolvedIncident> resolvedIncidents = incidentDao.readResolvedIncidents(10);
        // then
        assertThat(resolvedIncidents).hasSize(1);
        assertThat(resolvedIncidents.get(0).condition()).isEqualTo(alertCondition);
    }

    @Test
    public void shouldReadAll() throws Exception {
        // given
        AlertCondition alertCondition = AlertCondition.newBuilder()
                .setMetricCondition(MetricCondition.newBuilder()
                        .setMetric("transaction:x-percentile")
                        .setTransactionType("Web")
                        .setPercentile(OptionalDouble.newBuilder().setValue(95))
                        .setThreshold(SECONDS.toNanos(2))
                        .setTimePeriodSeconds(60)
                        .setMinTransactionCount(100)
                        .build())
                .build();
        AlertCondition alertCondition2 = AlertCondition.newBuilder()
                .setMetricCondition(MetricCondition.newBuilder()
                        .setMetric("transaction:x-percentile")
                        .setTransactionType("Web")
                        .setPercentile(OptionalDouble.newBuilder().setValue(95))
                        .setThreshold(SECONDS.toNanos(2))
                        .setTimePeriodSeconds(60)
                        .setMinTransactionCount(100)
                        .build())
                .build();
        // when
        incidentDao.insertOpenIncident(AGENT_ID, alertCondition, AlertSeverity.HIGH,
                AlertNotification.getDefaultInstance(), 456);
        incidentDao.insertOpenIncident(AGENT_ID, alertCondition2, AlertSeverity.HIGH,
                AlertNotification.getDefaultInstance(), 567);
        // then
        List<OpenIncident> openIncidents = incidentDao.readAllOpenIncidents();
        assertThat(openIncidents).hasSize(2);
    }
}
