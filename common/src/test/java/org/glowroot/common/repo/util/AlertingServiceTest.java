/*
 * Copyright 2015-2016 the original author or authors.
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

import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormat;

import javax.crypto.SecretKey;
import javax.mail.Message;

import com.google.common.collect.ImmutableList;
import org.junit.Before;
import org.junit.Test;

import org.glowroot.common.config.ImmutableSmtpConfig;
import org.glowroot.common.config.SmtpConfig;
import org.glowroot.common.live.ImmutablePercentileAggregate;
import org.glowroot.common.live.ImmutableTransactionQuery;
import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.model.LazyHistogram;
import org.glowroot.common.model.LazyHistogram.ScratchBuffer;
import org.glowroot.common.repo.AgentRepository;
import org.glowroot.common.repo.AgentRepository.AgentRollup;
import org.glowroot.common.repo.AggregateRepository;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.GaugeValueRepository;
import org.glowroot.common.repo.ImmutableAgentRollup;
import org.glowroot.common.repo.TriggeredAlertRepository;
import org.glowroot.common.repo.Utils;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertKind;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValue;
import org.glowroot.wire.api.model.Proto.OptionalDouble;
import org.glowroot.wire.api.model.Proto.OptionalInt32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AlertingServiceTest {

    private static final String AGENT_ID = "";

    private static final AlertConfig TRANSACTION_ALERT_CONFIG = AlertConfig.newBuilder()
            .setKind(AlertKind.TRANSACTION)
            .setTransactionType("tt")
            .setTransactionPercentile(OptionalDouble.newBuilder()
                    .setValue(95.0))
            .setTransactionThresholdMillis(OptionalInt32.newBuilder()
                    .setValue(1))
            .setTimePeriodSeconds(60)
            .setMinTransactionCount(OptionalInt32.newBuilder()
                    .setValue(0))
            .setGaugeName("")
            .addEmailAddress("to@example.org")
            .build();

    private static final AlertConfig GAUGE_ALERT_CONFIG = AlertConfig.newBuilder()
            .setKind(AlertKind.GAUGE)
            .setGaugeName("java.lang:type=GarbageCollector,name=ConcurrentMarkSweep"
                    + ":CollectionTime[counter]")
            .setGaugeThreshold(OptionalDouble.newBuilder()
                    .setValue(500.0))
            .setTimePeriodSeconds(60)
            .setMinTransactionCount(OptionalInt32.newBuilder()
                    .setValue(0))
            .setTransactionType("")
            .addEmailAddress("to@example.org")
            .build();

    private static final SecretKey SECRET_KEY;

    private static final SmtpConfig SMTP_CONFIG;

    static {
        try {
            SECRET_KEY = Encryption.generateNewKey();
            SMTP_CONFIG = ImmutableSmtpConfig.builder()
                    .host("localhost")
                    .ssl(true)
                    .username("u")
                    .password(Encryption.encrypt("test", SECRET_KEY))
                    .putAdditionalProperties("a", "x")
                    .putAdditionalProperties("b", "y")
                    .build();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private ConfigRepository configRepository;
    private AgentRepository agentRepository;
    private TriggeredAlertRepository triggeredAlertRepository;
    private AggregateRepository aggregateRepository;
    private GaugeValueRepository gaugeValueRepository;
    private RollupLevelService rollupLevelService;
    private MockMailService mailService;

    @Before
    public void beforeEachTest() throws Exception {
        configRepository = mock(ConfigRepository.class);
        agentRepository = mock(AgentRepository.class);
        when(agentRepository.readAgentRollups()).thenReturn(ImmutableList.<AgentRollup>of(
                ImmutableAgentRollup.builder()
                        .id("")
                        .display("")
                        .agent(true)
                        .build()));
        triggeredAlertRepository = mock(TriggeredAlertRepository.class);
        aggregateRepository = mock(AggregateRepository.class);
        gaugeValueRepository = mock(GaugeValueRepository.class);
        rollupLevelService = mock(RollupLevelService.class);
        mailService = new MockMailService();
        when(configRepository.getSecretKey()).thenReturn(SECRET_KEY);
    }

    @Test
    public void shouldSendMailForTransactionAlert() throws Exception {
        // given
        setupForTransaction(1000000);
        AlertingService alertingService = new AlertingService(configRepository,
                triggeredAlertRepository, aggregateRepository, gaugeValueRepository,
                rollupLevelService, mailService);
        // when
        alertingService.checkTransactionAlert("", TRANSACTION_ALERT_CONFIG, 120000, SMTP_CONFIG);
        // then
        assertThat(mailService.getMessage()).isNotNull();
        assertThat(((String) mailService.getMessage().getContent()).trim())
                .isEqualTo("95th percentile over the last 1 minute exceeded alert threshold of"
                        + " 1 millisecond.");
    }

    @Test
    public void shouldNotSendMailForTransactionAlert() throws Exception {
        // given
        setupForTransaction(999000);
        AlertingService alertingService = new AlertingService(configRepository,
                triggeredAlertRepository, aggregateRepository, gaugeValueRepository,
                rollupLevelService, mailService);
        // when
        alertingService.checkTransactionAlert("", TRANSACTION_ALERT_CONFIG, 120000, SMTP_CONFIG);
        // then
        assertThat(mailService.getMessage()).isNull();
    }

    @Test
    public void shouldSendMailForGaugeAlert() throws Exception {
        // given
        setupForGauge(500.1);
        AlertingService alertingService = new AlertingService(configRepository,
                triggeredAlertRepository, aggregateRepository, gaugeValueRepository,
                rollupLevelService, mailService);
        // when
        alertingService.checkGaugeAlert("", GAUGE_ALERT_CONFIG, 120000, SMTP_CONFIG);
        // then
        assertThat(mailService.getMessage()).isNotNull();
        assertThat(((String) mailService.getMessage().getContent()).trim())
                .isEqualTo("Average over the last 1 minute exceeded alert threshold of"
                        + " 500 milliseconds per second.");
    }

    @Test
    public void shouldNotSendMailForGaugeAlert() throws Exception {
        // given
        setupForGauge(499);
        AlertingService alertingService = new AlertingService(configRepository,
                triggeredAlertRepository, aggregateRepository, gaugeValueRepository,
                rollupLevelService, mailService);
        // when
        alertingService.checkGaugeAlert("", GAUGE_ALERT_CONFIG, 120000, SMTP_CONFIG);
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

    @Test
    public void testGaugeValueFormatting() {
        assertThat(AlertingService.displaySixDigitsOfPrecision(3)).isEqualTo("3");
        assertThat(AlertingService.displaySixDigitsOfPrecision(333333)).isEqualTo("333,333");
        assertThat(AlertingService.displaySixDigitsOfPrecision(3333333)).isEqualTo("3,333,333");

        assertThat(AlertingService.displaySixDigitsOfPrecision(3.3)).isEqualTo("3.3");
        assertThat(AlertingService.displaySixDigitsOfPrecision(3333.3)).isEqualTo("3,333.3");
        assertThat(AlertingService.displaySixDigitsOfPrecision(3333333.3)).isEqualTo("3,333,333");

        assertThat(AlertingService.displaySixDigitsOfPrecision(3.33333)).isEqualTo("3.33333");
        assertThat(AlertingService.displaySixDigitsOfPrecision(3.333333)).isEqualTo("3.33333");
        assertThat(AlertingService.displaySixDigitsOfPrecision(3.3333333)).isEqualTo("3.33333");

        assertThat(AlertingService.displaySixDigitsOfPrecision(0.333333)).isEqualTo("0.333333");
        assertThat(AlertingService.displaySixDigitsOfPrecision(0.3333333)).isEqualTo("0.333333");
        assertThat(AlertingService.displaySixDigitsOfPrecision(0.33333333)).isEqualTo("0.333333");

        assertThat(AlertingService.displaySixDigitsOfPrecision(0.0333333)).isEqualTo("0.0333333");
        assertThat(AlertingService.displaySixDigitsOfPrecision(0.03333333)).isEqualTo("0.0333333");
        assertThat(AlertingService.displaySixDigitsOfPrecision(0.033333333)).isEqualTo("0.0333333");

        assertThat(AlertingService.displaySixDigitsOfPrecision(0.000000333333))
                .isEqualTo("0.000000333333");
        assertThat(AlertingService.displaySixDigitsOfPrecision(0.0000003333333))
                .isEqualTo("0.000000333333");
        assertThat(AlertingService.displaySixDigitsOfPrecision(0.00000033333333))
                .isEqualTo("0.000000333333");
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
