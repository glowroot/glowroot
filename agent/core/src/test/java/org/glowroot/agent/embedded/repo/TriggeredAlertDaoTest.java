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
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition.MetricCondition;
import org.glowroot.wire.api.model.Proto.OptionalDouble;

import static java.util.concurrent.TimeUnit.SECONDS;
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
        triggeredAlertDao = new TriggeredAlertDao(dataSource, null);
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
        assertThat(triggeredAlertDao.exists(AGENT_ID, alertCondition)).isFalse();
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
        triggeredAlertDao.insert(AGENT_ID, alertCondition);
        // then
        assertThat(triggeredAlertDao.exists(AGENT_ID, otherAlertCondition)).isFalse();
        assertThat(triggeredAlertDao.exists(AGENT_ID, alertCondition)).isTrue();
    }

    @Test
    public void shouldNotExistAfterDelete() throws Exception {
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
        triggeredAlertDao.insert(AGENT_ID, alertCondition);
        triggeredAlertDao.delete(AGENT_ID, alertCondition);
        // then
        assertThat(triggeredAlertDao.exists(AGENT_ID, alertCondition)).isFalse();
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
        triggeredAlertDao.insert("xyz", alertCondition);
        triggeredAlertDao.insert("abc", alertCondition2);
        // then
        List<TriggeredAlert> triggeredAlerts = triggeredAlertDao.readAll();
        assertThat(triggeredAlerts).hasSize(2);
    }
}
