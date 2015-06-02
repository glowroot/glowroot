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
package org.glowroot.local.store;

import java.nio.ByteBuffer;
import java.sql.SQLException;

import javax.crypto.SecretKey;
import javax.mail.Message;

import com.google.common.collect.ImmutableList;
import org.junit.Before;
import org.junit.Test;

import org.glowroot.collector.Aggregate;
import org.glowroot.collector.LazyHistogram;
import org.glowroot.common.Encryption;
import org.glowroot.config.AlertConfig;
import org.glowroot.config.ConfigService;
import org.glowroot.config.SmtpConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AlertingServiceTest {

    private ConfigService configService;
    private TriggeredAlertDao triggeredAlertDao;
    private AggregateDao aggregateDao;
    private MockMailService mailService;

    @Before
    public void beforeEachTest() throws Exception {
        configService = mock(ConfigService.class);
        triggeredAlertDao = mock(TriggeredAlertDao.class);
        aggregateDao = mock(AggregateDao.class);
        mailService = new MockMailService();
        SecretKey secretKey = Encryption.generateNewKey();
        when(configService.getSecretKey()).thenReturn(secretKey);
        SmtpConfig smtpConfig = SmtpConfig.builder()
                .host("localhost")
                .ssl(true)
                .username("u")
                .encryptedPassword(Encryption.encrypt("test", secretKey))
                .putAdditionalProperties("a", "x")
                .putAdditionalProperties("b", "y")
                .build();
        when(configService.getSmtpConfig()).thenReturn(smtpConfig);
        when(configService.getAllTransactionTypes()).thenReturn(ImmutableList.of("tt", "uu"));
    }

    @Test
    public void shouldSendMail() throws Exception {
        // given
        setup(1000);
        AlertingService alertingService =
                new AlertingService(configService, triggeredAlertDao, aggregateDao, mailService);
        // when
        alertingService.checkAlerts(120000);
        // then
        assertThat(mailService.getMessage()).isNotNull();
    }

    @Test
    public void shouldNotSendMail() throws Exception {
        // given
        setup(999);
        AlertingService alertingService =
                new AlertingService(configService, triggeredAlertDao, aggregateDao, mailService);
        // when
        alertingService.checkAlerts(120000);
        // then
        assertThat(mailService.getMessage()).isNull();
    }

    @Test
    public void shouldReturnCorrectPercentileName() {
        assertThat(AlertingService.getPercentileWithSuffix(0)).isEqualTo("0th");
        assertThat(AlertingService.getPercentileWithSuffix(1)).isEqualTo("1st");
        assertThat(AlertingService.getPercentileWithSuffix(2)).isEqualTo("2nd");
        assertThat(AlertingService.getPercentileWithSuffix(3)).isEqualTo("3rd");
        assertThat(AlertingService.getPercentileWithSuffix(4)).isEqualTo("4th");
        assertThat(AlertingService.getPercentileWithSuffix(9)).isEqualTo("9th");
        assertThat(AlertingService.getPercentileWithSuffix(10)).isEqualTo("10th");
        assertThat(AlertingService.getPercentileWithSuffix(11)).isEqualTo("11th");
        assertThat(AlertingService.getPercentileWithSuffix(12)).isEqualTo("12th");
        assertThat(AlertingService.getPercentileWithSuffix(13)).isEqualTo("13th");
        assertThat(AlertingService.getPercentileWithSuffix(14)).isEqualTo("14th");
        assertThat(AlertingService.getPercentileWithSuffix(20)).isEqualTo("20th");
        assertThat(AlertingService.getPercentileWithSuffix(21)).isEqualTo("21st");
        assertThat(AlertingService.getPercentileWithSuffix(22)).isEqualTo("22nd");
        assertThat(AlertingService.getPercentileWithSuffix(23)).isEqualTo("23rd");
        assertThat(AlertingService.getPercentileWithSuffix(24)).isEqualTo("24th");

        assertThat(AlertingService.getPercentileWithSuffix(50.0)).isEqualTo("50th");
        assertThat(AlertingService.getPercentileWithSuffix(50.1)).isEqualTo("50.1st");
        assertThat(AlertingService.getPercentileWithSuffix(50.2)).isEqualTo("50.2nd");
        assertThat(AlertingService.getPercentileWithSuffix(50.3)).isEqualTo("50.3rd");
        assertThat(AlertingService.getPercentileWithSuffix(50.4)).isEqualTo("50.4th");
        assertThat(AlertingService.getPercentileWithSuffix(50.10)).isEqualTo("50.1st");
        assertThat(AlertingService.getPercentileWithSuffix(50.11)).isEqualTo("50.11th");
        assertThat(AlertingService.getPercentileWithSuffix(50.12)).isEqualTo("50.12th");
        assertThat(AlertingService.getPercentileWithSuffix(50.13)).isEqualTo("50.13th");
        assertThat(AlertingService.getPercentileWithSuffix(50.14)).isEqualTo("50.14th");
        assertThat(AlertingService.getPercentileWithSuffix(50.20)).isEqualTo("50.2nd");
        assertThat(AlertingService.getPercentileWithSuffix(50.21)).isEqualTo("50.21st");
        assertThat(AlertingService.getPercentileWithSuffix(50.22)).isEqualTo("50.22nd");
        assertThat(AlertingService.getPercentileWithSuffix(50.23)).isEqualTo("50.23rd");
        assertThat(AlertingService.getPercentileWithSuffix(50.24)).isEqualTo("50.24th");
    }

    private void setup(long... histogramValues) throws SQLException {
        AlertConfig alertConfig = AlertConfig.builder()
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
        ByteBuffer buffer = ByteBuffer.allocate(lazyHistogram.getNeededByteBufferCapacity());
        byte[] histogram = lazyHistogram.encodeUsingTempByteBuffer(buffer);
        Aggregate aggregate = Aggregate.builder()
                .transactionType("tt")
                .captureTime(120000)
                .totalMicros(1000)
                .errorCount(0)
                .transactionCount(1)
                .timers("")
                .histogram(histogram)
                .build();
        when(configService.getAlertConfigs()).thenReturn(ImmutableList.of(alertConfig));
        when(aggregateDao.readOverallAggregates("tt", 60001, 120000, 0))
                .thenReturn(ImmutableList.of(aggregate));
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
