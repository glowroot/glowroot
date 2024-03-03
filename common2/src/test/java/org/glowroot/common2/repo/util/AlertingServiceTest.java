/*
 * Copyright 2015-2023 the original author or authors.
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
package org.glowroot.common2.repo.util;

import java.text.DecimalFormat;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import javax.crypto.SecretKey;
import javax.mail.Message;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import org.glowroot.common2.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.glowroot.common.live.ImmutableAggregateQuery;
import org.glowroot.common.live.ImmutablePercentileAggregate;
import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.model.LazyHistogram;
import org.glowroot.common.model.LazyHistogram.ScratchBuffer;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.config.HttpProxyConfig;
import org.glowroot.common2.config.ImmutableHttpProxyConfig;
import org.glowroot.common2.config.ImmutableSmtpConfig;
import org.glowroot.common2.config.SmtpConfig;
import org.glowroot.common2.config.SmtpConfig.ConnectionSecurity;
import org.glowroot.common2.repo.util.AlertingService.IncidentKey;
import org.glowroot.common2.repo.util.LockSet.LockSetImpl;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition.MetricCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertNotification;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertNotification.EmailNotification;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueMessage.GaugeValue;
import org.glowroot.wire.api.model.Proto.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class AlertingServiceTest {

    private static final String AGENT_ID = "";

    private static final AlertConfig TRANSACTION_X_PERCENTILE_ALERT_CONFIG;
    private static final AlertConfig UPPER_BOUND_GAUGE_ALERT_CONFIG;
    private static final AlertConfig LOWER_BOUND_GAUGE_ALERT_CONFIG;

    static {
        AlertNotification alertNotification = AlertNotification.newBuilder()
                .setEmailNotification(EmailNotification.newBuilder()
                        .addEmailAddress("to@example.org"))
                .build();

        TRANSACTION_X_PERCENTILE_ALERT_CONFIG = AlertConfig.newBuilder()
                .setCondition(AlertCondition.newBuilder()
                        .setMetricCondition(MetricCondition.newBuilder()
                                .setMetric("transaction:x-percentile")
                                .setTransactionType("tt")
                                .setPercentile(OptionalDouble.newBuilder().setValue(95))
                                .setThreshold(1)
                                .setTimePeriodSeconds(60)))
                .setNotification(alertNotification)
                .build();
        UPPER_BOUND_GAUGE_ALERT_CONFIG = AlertConfig.newBuilder()
                .setCondition(AlertCondition.newBuilder()
                        .setMetricCondition(MetricCondition.newBuilder()
                                .setMetric("gauge:java.lang:type=GarbageCollector,"
                                        + "name=ConcurrentMarkSweep:CollectionTime[counter]")
                                .setThreshold(500.0)
                                .setTimePeriodSeconds(60)))
                .setNotification(alertNotification)
                .build();
        LOWER_BOUND_GAUGE_ALERT_CONFIG = AlertConfig.newBuilder()
                .setCondition(AlertCondition.newBuilder()
                        .setMetricCondition(MetricCondition.newBuilder()
                                .setMetric("gauge:java.lang:type=GarbageCollector,"
                                        + "name=ConcurrentMarkSweep:CollectionTime[counter]")
                                .setThreshold(500.0)
                                .setTimePeriodSeconds(60)
                                .setLowerBoundThreshold(true)))
                .setNotification(alertNotification)
                .build();
    }

    private static final LazySecretKey LAZY_SECRET_KEY;

    private static final SmtpConfig SMTP_CONFIG;
    private static final HttpProxyConfig HTTP_PROXY_CONFIG;

    static {
        try {
            SecretKey secretKey = Encryption.generateNewKey();
            LAZY_SECRET_KEY = mock(LazySecretKey.class);
            when(LAZY_SECRET_KEY.getOrCreate()).thenReturn(secretKey);
            when(LAZY_SECRET_KEY.getExisting()).thenReturn(secretKey);
            SMTP_CONFIG = ImmutableSmtpConfig.builder()
                    .host("localhost")
                    .connectionSecurity(ConnectionSecurity.SSL_TLS)
                    .username("u")
                    .encryptedPassword(Encryption.encrypt("test", LAZY_SECRET_KEY))
                    .putAdditionalProperties("a", "x")
                    .putAdditionalProperties("b", "y")
                    .build();
            HTTP_PROXY_CONFIG = ImmutableHttpProxyConfig.builder().build();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ConfigRepository configRepository;
    private IncidentRepository incidentRepository;
    private AggregateRepository aggregateRepository;
    private GaugeValueRepository gaugeValueRepository;
    private TraceRepository traceRepository;
    private RollupLevelService rollupLevelService;
    private MockMailService mailService;
    private HttpClient httpClient;

    @BeforeEach
    public void beforeEachTest() throws Exception {
        configRepository = mock(ConfigRepository.class);
        incidentRepository = mock(IncidentRepository.class);
        doReturn(CompletableFuture.completedFuture(null))
                .when(incidentRepository)
                .insertOpenIncident(anyString(), any(), any(), any(), anyLong(), any());
        doReturn(CompletableFuture.completedFuture(null))
                .when(incidentRepository)
                .readOpenIncident(anyString(), any(), any(), any());
        aggregateRepository = mock(AggregateRepository.class);
        gaugeValueRepository = mock(GaugeValueRepository.class);
        traceRepository = mock(TraceRepository.class);
        rollupLevelService = mock(RollupLevelService.class);
        mailService = new MockMailService();
        httpClient = new HttpClient(configRepository);
        when(configRepository.getLazySecretKey()).thenReturn(LAZY_SECRET_KEY);
        when(configRepository.getSmtpConfig()).thenReturn(SMTP_CONFIG);
        when(configRepository.getHttpProxyConfig()).thenReturn(HTTP_PROXY_CONFIG);
    }

    @Test
    public void shouldSendMailForTransactionAlert() throws Exception {
        // given
        setupForTransaction(1000000);
        AlertingService alertingService = new AlertingService(configRepository,
                incidentRepository, aggregateRepository, gaugeValueRepository, traceRepository,
                rollupLevelService, mailService, httpClient, newLockSet(),
                newLockSet(),
                Clock.systemClock());
        // when
        alertingService.checkMetricAlert("", "", "", TRANSACTION_X_PERCENTILE_ALERT_CONFIG,
                TRANSACTION_X_PERCENTILE_ALERT_CONFIG.getCondition().getMetricCondition(), 120000, CassandraProfile.collector).toCompletableFuture().join();
        // then
        assertThat(mailService.getMessage()).isNotNull();
        assertThat(((String) mailService.getMessage().getContent()).trim())
                .isEqualTo("95th percentile over the last 1 minute is greater than or equal to the"
                        + " alert threshold of 1 millisecond.");
    }

    @Test
    public void shouldNotSendMailForTransactionAlert() throws Exception {
        // given
        setupForTransaction(999999);
        AlertingService alertingService = new AlertingService(configRepository,
                incidentRepository, aggregateRepository, gaugeValueRepository, traceRepository,
                rollupLevelService, mailService, httpClient, newLockSet(),
                newLockSet(),
                Clock.systemClock());
        // when
        alertingService.checkMetricAlert("", "", "", TRANSACTION_X_PERCENTILE_ALERT_CONFIG,
                TRANSACTION_X_PERCENTILE_ALERT_CONFIG.getCondition().getMetricCondition(), 120000, CassandraProfile.collector).toCompletableFuture().join();
        // then
        assertThat(mailService.getMessage()).isNull();
    }

    @Test
    public void shouldSendMailForGaugeAlert() throws Exception {
        // given
        setupForGauge(500);
        AlertingService alertingService = new AlertingService(configRepository,
                incidentRepository, aggregateRepository, gaugeValueRepository, traceRepository,
                rollupLevelService, mailService, httpClient, newLockSet(),
                newLockSet(),
                Clock.systemClock());
        // when
        alertingService.checkMetricAlert("", "", "", UPPER_BOUND_GAUGE_ALERT_CONFIG,
                UPPER_BOUND_GAUGE_ALERT_CONFIG.getCondition().getMetricCondition(), 120000, CassandraProfile.collector).toCompletableFuture().join();
        // then
        assertThat(mailService.getMessage()).isNotNull();
        assertThat(((String) mailService.getMessage().getContent()).trim())
                .isEqualTo("Average over the last 1 minute is greater than or equal to the alert"
                        + " threshold of 500 milliseconds per second.");
    }

    @Test
    public void shouldNotSendMailForGaugeAlert() throws Exception {
        // given
        setupForGauge(499);
        AlertingService alertingService = new AlertingService(configRepository,
                incidentRepository, aggregateRepository, gaugeValueRepository, traceRepository,
                rollupLevelService, mailService, httpClient, newLockSet(),
                newLockSet(),
                Clock.systemClock());
        // when
        alertingService.checkMetricAlert("", "", "", UPPER_BOUND_GAUGE_ALERT_CONFIG,
                UPPER_BOUND_GAUGE_ALERT_CONFIG.getCondition().getMetricCondition(), 120000, CassandraProfile.collector).toCompletableFuture().join();
        // then
        assertThat(mailService.getMessage()).isNull();
    }

    @Test
    public void shouldSendMailForLowerBoundGaugeAlert() throws Exception {
        // given
        setupForGauge(500);
        AlertingService alertingService = new AlertingService(configRepository,
                incidentRepository, aggregateRepository, gaugeValueRepository, traceRepository,
                rollupLevelService, mailService, httpClient, newLockSet(),
                newLockSet(),
                Clock.systemClock());
        // when
        alertingService.checkMetricAlert("", "", "", LOWER_BOUND_GAUGE_ALERT_CONFIG,
                LOWER_BOUND_GAUGE_ALERT_CONFIG.getCondition().getMetricCondition(), 120000, CassandraProfile.collector).toCompletableFuture().join();
        // then
        assertThat(mailService.getMessage()).isNotNull();
        assertThat(((String) mailService.getMessage().getContent()).trim())
                .isEqualTo("Average over the last 1 minute is less than or equal to the alert"
                        + " threshold of 500 milliseconds per second.");
    }

    @Test
    public void shouldNotSendMailForLowerBoundGaugeAlert() throws Exception {
        // given
        setupForGauge(501);
        AlertingService alertingService = new AlertingService(configRepository,
                incidentRepository, aggregateRepository, gaugeValueRepository, traceRepository,
                rollupLevelService, mailService, httpClient, newLockSet(),
                newLockSet(),
                Clock.systemClock());
        // when
        alertingService.checkMetricAlert("", "", "", LOWER_BOUND_GAUGE_ALERT_CONFIG,
                LOWER_BOUND_GAUGE_ALERT_CONFIG.getCondition().getMetricCondition(), 120000, CassandraProfile.collector).toCompletableFuture().join();
        // then
        assertThat(mailService.getMessage()).isNull();
    }

    @Test
    public void shouldReturnCorrectPercentileName() {
        shouldReturnCorrectPercentileName(0, "th");
        shouldReturnCorrectPercentileName(1, "st");
        shouldReturnCorrectPercentileName(2, "nd");
        shouldReturnCorrectPercentileName(3, "rd");
        shouldReturnCorrectPercentileName(4, "th");
        shouldReturnCorrectPercentileName(9, "th");
        shouldReturnCorrectPercentileName(10, "th");
        shouldReturnCorrectPercentileName(11, "th");
        shouldReturnCorrectPercentileName(12, "th");
        shouldReturnCorrectPercentileName(13, "th");
        shouldReturnCorrectPercentileName(14, "th");
        shouldReturnCorrectPercentileName(20, "th");
        shouldReturnCorrectPercentileName(21, "st");
        shouldReturnCorrectPercentileName(22, "nd");
        shouldReturnCorrectPercentileName(23, "rd");
        shouldReturnCorrectPercentileName(24, "th");

        shouldReturnCorrectPercentileName(50, "th");
        shouldReturnCorrectPercentileName(50.1, "st");
        shouldReturnCorrectPercentileName(50.2, "nd");
        shouldReturnCorrectPercentileName(50.3, "rd");
        shouldReturnCorrectPercentileName(50.4, "th");
        shouldReturnCorrectPercentileName(50.11, "th");
        shouldReturnCorrectPercentileName(50.12, "th");
        shouldReturnCorrectPercentileName(50.13, "th");
        shouldReturnCorrectPercentileName(50.14, "th");
        shouldReturnCorrectPercentileName(50.2, "nd");
        shouldReturnCorrectPercentileName(50.21, "st");
        shouldReturnCorrectPercentileName(50.22, "nd");
        shouldReturnCorrectPercentileName(50.23, "rd");
        shouldReturnCorrectPercentileName(50.24, "th");
    }

    private void setupForTransaction(long... histogramValues) throws Exception {
        LazyHistogram lazyHistogram = new LazyHistogram();
        for (long histogramValue : histogramValues) {
            lazyHistogram.add(histogramValue);
        }
        PercentileAggregate aggregate = ImmutablePercentileAggregate.builder()
                .captureTime(120000)
                .totalDurationNanos(1000000)
                .transactionCount(1)
                .durationNanosHistogram(lazyHistogram.toProto(new ScratchBuffer()))
                .build();
        ImmutableAggregateQuery query = ImmutableAggregateQuery.builder()
                .transactionType("tt")
                .from(60001)
                .to(120000)
                .rollupLevel(0)
                .build();
        when(aggregateRepository.readPercentileAggregates(AGENT_ID, query, CassandraProfile.collector))
                .thenReturn(CompletableFuture.completedFuture(ImmutableList.of(aggregate)));
    }

    private void setupForGauge(double value) throws Exception {
        GaugeValue gaugeValue = GaugeValue.newBuilder()
                .setGaugeName("abc")
                .setCaptureTime(120000)
                .setValue(value)
                .setWeight(1)
                .build();
        when(gaugeValueRepository.readGaugeValues(AGENT_ID,
                "java.lang:type=GarbageCollector,name=ConcurrentMarkSweep:CollectionTime[counter]",
                60001, 120000, 0, CassandraProfile.collector)).thenReturn(CompletableFuture.completedFuture(ImmutableList.of(gaugeValue)));
    }

    private static LockSet<IncidentKey> newLockSet() {
        return new LockSetImpl<IncidentKey>(Maps.<IncidentKey, UUID>newConcurrentMap());
    }

    private static void shouldReturnCorrectPercentileName(double percentile, String suffix) {
        assertThat(Utils.getPercentileWithSuffix(percentile))
                .isEqualTo(new DecimalFormat().format(percentile) + suffix);
    }

    static class MockMailService extends MailService {

        private Message msg;

        @Override
        public void send(Message msg) {
            this.msg = msg;
        }

        public Message getMessage() {
            return msg;
        }
    }
}
