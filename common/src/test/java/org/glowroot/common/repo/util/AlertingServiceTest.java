/*
 * Copyright 2015-2017 the original author or authors.
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
package org.glowroot.common.repo.util;

import java.text.DecimalFormat;

import javax.crypto.SecretKey;
import javax.mail.Message;

import com.google.common.collect.ImmutableList;
import org.junit.Before;
import org.junit.Test;

import org.glowroot.common.config.HttpProxyConfig;
import org.glowroot.common.config.ImmutableHttpProxyConfig;
import org.glowroot.common.config.ImmutableSmtpConfig;
import org.glowroot.common.config.SmtpConfig;
import org.glowroot.common.config.SmtpConfig.ConnectionSecurity;
import org.glowroot.common.live.ImmutablePercentileAggregate;
import org.glowroot.common.live.ImmutableTransactionQuery;
import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.model.LazyHistogram;
import org.glowroot.common.model.LazyHistogram.ScratchBuffer;
import org.glowroot.common.repo.AggregateRepository;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.GaugeValueRepository;
import org.glowroot.common.repo.IncidentRepository;
import org.glowroot.common.repo.Utils;
import org.glowroot.common.util.Clock;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition.MetricCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertNotification;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertNotification.EmailNotification;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValue;
import org.glowroot.wire.api.model.Proto.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
                    .password(Encryption.encrypt("test", LAZY_SECRET_KEY))
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
    private RollupLevelService rollupLevelService;
    private MockMailService mailService;
    private HttpClient httpClient;

    @Before
    public void beforeEachTest() throws Exception {
        configRepository = mock(ConfigRepository.class);
        incidentRepository = mock(IncidentRepository.class);
        aggregateRepository = mock(AggregateRepository.class);
        gaugeValueRepository = mock(GaugeValueRepository.class);
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
        setupForTransaction(1000001);
        AlertingService alertingService = new AlertingService(configRepository,
                incidentRepository, aggregateRepository, gaugeValueRepository,
                rollupLevelService, mailService, httpClient, Clock.systemClock());
        // when
        alertingService.checkMetricAlert("", "", "", TRANSACTION_X_PERCENTILE_ALERT_CONFIG,
                TRANSACTION_X_PERCENTILE_ALERT_CONFIG.getCondition().getMetricCondition(), 120000);
        // then
        assertThat(mailService.getMessage()).isNotNull();
        assertThat(((String) mailService.getMessage().getContent()).trim())
                .isEqualTo("95th percentile over the last 1 minute has exceeded alert threshold of"
                        + " 1 millisecond.");
    }

    @Test
    public void shouldNotSendMailForTransactionAlert() throws Exception {
        // given
        setupForTransaction(1000000);
        AlertingService alertingService = new AlertingService(configRepository,
                incidentRepository, aggregateRepository, gaugeValueRepository,
                rollupLevelService, mailService, httpClient, Clock.systemClock());
        // when
        alertingService.checkMetricAlert("", "", "", TRANSACTION_X_PERCENTILE_ALERT_CONFIG,
                TRANSACTION_X_PERCENTILE_ALERT_CONFIG.getCondition().getMetricCondition(), 120000);
        // then
        assertThat(mailService.getMessage()).isNull();
    }

    @Test
    public void shouldSendMailForGaugeAlert() throws Exception {
        // given
        setupForGauge(500.1);
        AlertingService alertingService = new AlertingService(configRepository,
                incidentRepository, aggregateRepository, gaugeValueRepository,
                rollupLevelService, mailService, httpClient, Clock.systemClock());
        // when
        alertingService.checkMetricAlert("", "", "", UPPER_BOUND_GAUGE_ALERT_CONFIG,
                UPPER_BOUND_GAUGE_ALERT_CONFIG.getCondition().getMetricCondition(), 120000);
        // then
        assertThat(mailService.getMessage()).isNotNull();
        assertThat(((String) mailService.getMessage().getContent()).trim())
                .isEqualTo("Average over the last 1 minute has exceeded alert threshold of"
                        + " 500 milliseconds per second.");
    }

    @Test
    public void shouldNotSendMailForGaugeAlert() throws Exception {
        // given
        setupForGauge(500);
        AlertingService alertingService = new AlertingService(configRepository,
                incidentRepository, aggregateRepository, gaugeValueRepository,
                rollupLevelService, mailService, httpClient, Clock.systemClock());
        // when
        alertingService.checkMetricAlert("", "", "", UPPER_BOUND_GAUGE_ALERT_CONFIG,
                UPPER_BOUND_GAUGE_ALERT_CONFIG.getCondition().getMetricCondition(), 120000);
        // then
        assertThat(mailService.getMessage()).isNull();
    }

    @Test
    public void shouldSendMailForLowerBoundGaugeAlert() throws Exception {
        // given
        setupForGauge(499.9);
        AlertingService alertingService = new AlertingService(configRepository,
                incidentRepository, aggregateRepository, gaugeValueRepository,
                rollupLevelService, mailService, httpClient, Clock.systemClock());
        // when
        alertingService.checkMetricAlert("", "", "", LOWER_BOUND_GAUGE_ALERT_CONFIG,
                LOWER_BOUND_GAUGE_ALERT_CONFIG.getCondition().getMetricCondition(), 120000);
        // then
        assertThat(mailService.getMessage()).isNotNull();
        assertThat(((String) mailService.getMessage().getContent()).trim())
                .isEqualTo("Average over the last 1 minute has dropped below alert threshold of"
                        + " 500 milliseconds per second.");
    }

    @Test
    public void shouldNotSendMailForLowerBoundGaugeAlert() throws Exception {
        // given
        setupForGauge(500);
        AlertingService alertingService = new AlertingService(configRepository,
                incidentRepository, aggregateRepository, gaugeValueRepository,
                rollupLevelService, mailService, httpClient, Clock.systemClock());
        // when
        alertingService.checkMetricAlert("", "", "", LOWER_BOUND_GAUGE_ALERT_CONFIG,
                LOWER_BOUND_GAUGE_ALERT_CONFIG.getCondition().getMetricCondition(), 120000);
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
        ImmutableTransactionQuery query = ImmutableTransactionQuery.builder()
                .transactionType("tt")
                .from(60001)
                .to(120000)
                .rollupLevel(0)
                .build();
        when(aggregateRepository.readPercentileAggregates(AGENT_ID, query))
                .thenReturn(ImmutableList.of(aggregate));
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
                60001, 120000, 0)).thenReturn(ImmutableList.of(gaugeValue));
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
