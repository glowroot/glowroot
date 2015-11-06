/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.storage.repo.helper;

import javax.crypto.SecretKey;
import javax.mail.Message;

import com.google.common.collect.ImmutableList;
import org.junit.Before;
import org.junit.Test;

import org.glowroot.common.live.ImmutablePercentileAggregate;
import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.model.LazyHistogram;
import org.glowroot.common.model.LazyHistogram.ScratchBuffer;
import org.glowroot.storage.repo.AggregateRepository;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.TriggeredAlertRepository;
import org.glowroot.storage.repo.Utils;
import org.glowroot.storage.repo.config.AlertConfig;
import org.glowroot.storage.repo.config.ImmutableAlertConfig;
import org.glowroot.storage.repo.config.ImmutableSmtpConfig;
import org.glowroot.storage.repo.config.SmtpConfig;
import org.glowroot.storage.util.Encryption;
import org.glowroot.storage.util.MailService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AlertingServiceTest {

    private static final String SERVER_NAME = "";

    private ConfigRepository configRepository;
    private TriggeredAlertRepository triggeredAlertRepository;
    private AggregateRepository aggregateRepository;
    private MockMailService mailService;

    @Before
    public void beforeEachTest() throws Exception {
        configRepository = mock(ConfigRepository.class);
        triggeredAlertRepository = mock(TriggeredAlertRepository.class);
        aggregateRepository = mock(AggregateRepository.class);
        mailService = new MockMailService();
        SecretKey secretKey = Encryption.generateNewKey();
        when(configRepository.getSecretKey()).thenReturn(secretKey);
        SmtpConfig smtpConfig = ImmutableSmtpConfig.builder()
                .host("localhost")
                .ssl(true)
                .username("u")
                .encryptedPassword(Encryption.encrypt("test", secretKey))
                .putAdditionalProperties("a", "x")
                .putAdditionalProperties("b", "y")
                .build();
        when(configRepository.getSmtpConfig()).thenReturn(smtpConfig);
    }

    @Test
    public void shouldSendMail() throws Exception {
        // given
        setup(1000000);
        AlertingService alertingService = new AlertingService(configRepository,
                triggeredAlertRepository, aggregateRepository, mailService);
        // when
        alertingService.checkAlerts(120000);
        // then
        assertThat(mailService.getMessage()).isNotNull();
    }

    @Test
    public void shouldNotSendMail() throws Exception {
        // given
        setup(999000);
        AlertingService alertingService = new AlertingService(configRepository,
                triggeredAlertRepository, aggregateRepository, mailService);
        // when
        alertingService.checkAlerts(120000);
        // then
        assertThat(mailService.getMessage()).isNull();
    }

    @Test
    public void shouldReturnCorrectPercentileName() {
        assertThat(Utils.getPercentileWithSuffix(0)).isEqualTo("0th");
        assertThat(Utils.getPercentileWithSuffix(1)).isEqualTo("1st");
        assertThat(Utils.getPercentileWithSuffix(2)).isEqualTo("2nd");
        assertThat(Utils.getPercentileWithSuffix(3)).isEqualTo("3rd");
        assertThat(Utils.getPercentileWithSuffix(4)).isEqualTo("4th");
        assertThat(Utils.getPercentileWithSuffix(9)).isEqualTo("9th");
        assertThat(Utils.getPercentileWithSuffix(10)).isEqualTo("10th");
        assertThat(Utils.getPercentileWithSuffix(11)).isEqualTo("11th");
        assertThat(Utils.getPercentileWithSuffix(12)).isEqualTo("12th");
        assertThat(Utils.getPercentileWithSuffix(13)).isEqualTo("13th");
        assertThat(Utils.getPercentileWithSuffix(14)).isEqualTo("14th");
        assertThat(Utils.getPercentileWithSuffix(20)).isEqualTo("20th");
        assertThat(Utils.getPercentileWithSuffix(21)).isEqualTo("21st");
        assertThat(Utils.getPercentileWithSuffix(22)).isEqualTo("22nd");
        assertThat(Utils.getPercentileWithSuffix(23)).isEqualTo("23rd");
        assertThat(Utils.getPercentileWithSuffix(24)).isEqualTo("24th");

        assertThat(Utils.getPercentileWithSuffix(50.0)).isEqualTo("50th");
        assertThat(Utils.getPercentileWithSuffix(50.1)).isEqualTo("50.1st");
        assertThat(Utils.getPercentileWithSuffix(50.2)).isEqualTo("50.2nd");
        assertThat(Utils.getPercentileWithSuffix(50.3)).isEqualTo("50.3rd");
        assertThat(Utils.getPercentileWithSuffix(50.4)).isEqualTo("50.4th");
        assertThat(Utils.getPercentileWithSuffix(50.10)).isEqualTo("50.1st");
        assertThat(Utils.getPercentileWithSuffix(50.11)).isEqualTo("50.11th");
        assertThat(Utils.getPercentileWithSuffix(50.12)).isEqualTo("50.12th");
        assertThat(Utils.getPercentileWithSuffix(50.13)).isEqualTo("50.13th");
        assertThat(Utils.getPercentileWithSuffix(50.14)).isEqualTo("50.14th");
        assertThat(Utils.getPercentileWithSuffix(50.20)).isEqualTo("50.2nd");
        assertThat(Utils.getPercentileWithSuffix(50.21)).isEqualTo("50.21st");
        assertThat(Utils.getPercentileWithSuffix(50.22)).isEqualTo("50.22nd");
        assertThat(Utils.getPercentileWithSuffix(50.23)).isEqualTo("50.23rd");
        assertThat(Utils.getPercentileWithSuffix(50.24)).isEqualTo("50.24th");
    }

    private void setup(long... histogramValues) throws Exception {
        AlertConfig alertConfig = ImmutableAlertConfig.builder()
                .transactionType("tt")
                .percentile(95)
                .timePeriodMinutes(1)
                .thresholdMillis(1)
                .minTransactionCount(0)
                .addEmailAddresses("to@example.org")
                .build();
        LazyHistogram lazyHistogram = new LazyHistogram();
        for (long histogramValue : histogramValues) {
            lazyHistogram.add(histogramValue);
        }
        PercentileAggregate aggregate = ImmutablePercentileAggregate.builder()
                .captureTime(120000)
                .totalNanos(1000000)
                .transactionCount(1)
                .histogram(lazyHistogram.toProtobuf(new ScratchBuffer()))
                .build();
        when(configRepository.getAlertConfigs(SERVER_NAME))
                .thenReturn(ImmutableList.of(alertConfig));
        when(aggregateRepository.readOverallPercentileAggregates(SERVER_NAME, "tt", 60001, 120000,
                0)).thenReturn(ImmutableList.of(aggregate));
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
