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

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.text.NumberFormat;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;

import javax.crypto.SecretKey;
import javax.mail.Address;
import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.config.SmtpConfig;
import org.glowroot.common.live.ImmutableTransactionQuery;
import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.model.LazyHistogram;
import org.glowroot.common.repo.AggregateRepository;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.GaugeValueRepository;
import org.glowroot.common.repo.GaugeValueRepository.Gauge;
import org.glowroot.common.repo.TriggeredAlertRepository;
import org.glowroot.common.repo.Utils;
import org.glowroot.common.util.Versions;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValue;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

public class AlertingService {

    private static final Logger logger = LoggerFactory.getLogger(AlertingService.class);

    private final ConfigRepository configRepository;
    private final TriggeredAlertRepository triggeredAlertRepository;
    private final AggregateRepository aggregateRepository;
    private final GaugeValueRepository gaugeValueRepository;
    private final RollupLevelService rollupLevelService;
    private final MailService mailService;

    public AlertingService(ConfigRepository configRepository,
            TriggeredAlertRepository triggeredAlertRepository,
            AggregateRepository aggregateRepository, GaugeValueRepository gaugeValueRepository,
            RollupLevelService rollupLevelService, MailService mailService) {
        this.configRepository = configRepository;
        this.triggeredAlertRepository = triggeredAlertRepository;
        this.aggregateRepository = aggregateRepository;
        this.gaugeValueRepository = gaugeValueRepository;
        this.rollupLevelService = rollupLevelService;
        this.mailService = mailService;
    }

    public void checkTransactionAlert(String agentRollupId, AlertConfig alertConfig, long endTime,
            SmtpConfig smtpConfig) throws Exception {
        // validate config
        if (!alertConfig.hasTransactionPercentile()) {
            // AlertConfig has nice toString() from immutables
            logger.warn("alert config missing transactionPercentile: {}", alertConfig);
            return;
        }
        double percentile = alertConfig.getTransactionPercentile().getValue();
        if (!alertConfig.hasTransactionThresholdMillis()) {
            // AlertConfig has nice toString() from immutables
            logger.warn("alert config missing transactionThresholdMillis: {}", alertConfig);
            return;
        }
        int thresholdMillis = alertConfig.getTransactionThresholdMillis().getValue();
        if (!alertConfig.hasMinTransactionCount()) {
            // AlertConfig has nice toString() from immutables
            logger.warn("alert config missing minTransactionCount: {}", alertConfig);
            return;
        }
        int minTransactionCount = alertConfig.getMinTransactionCount().getValue();

        long startTime = endTime - SECONDS.toMillis(alertConfig.getTimePeriodSeconds());
        int rollupLevel = rollupLevelService.getRollupLevelForView(startTime, endTime);
        // startTime + 1 in order to not include the gauge value at startTime
        List<PercentileAggregate> percentileAggregates =
                aggregateRepository.readPercentileAggregates(agentRollupId,
                        ImmutableTransactionQuery.builder()
                                .transactionType(alertConfig.getTransactionType())
                                .from(startTime + 1)
                                .to(endTime)
                                .rollupLevel(rollupLevel)
                                .build());
        long transactionCount = 0;
        LazyHistogram durationNanosHistogram = new LazyHistogram();
        for (PercentileAggregate aggregate : percentileAggregates) {
            transactionCount += aggregate.transactionCount();
            durationNanosHistogram.merge(aggregate.durationNanosHistogram());
        }
        if (transactionCount < minTransactionCount) {
            // don't clear existing triggered alert
            return;
        }
        String version = Versions.getVersion(alertConfig);
        boolean previouslyTriggered = triggeredAlertRepository.exists(agentRollupId, version);
        long valueAtPercentile = durationNanosHistogram.getValueAtPercentile(percentile);
        boolean currentlyTriggered = valueAtPercentile >= MILLISECONDS.toNanos(thresholdMillis);
        if (previouslyTriggered && !currentlyTriggered) {
            triggeredAlertRepository.delete(agentRollupId, version);
            sendTransactionAlert(agentRollupId, alertConfig, percentile, thresholdMillis, true,
                    smtpConfig);
        } else if (!previouslyTriggered && currentlyTriggered) {
            triggeredAlertRepository.insert(agentRollupId, version);
            sendTransactionAlert(agentRollupId, alertConfig, percentile, thresholdMillis, false,
                    smtpConfig);
        }
    }

    public void checkGaugeAlert(String agentRollupId, AlertConfig alertConfig, long endTime,
            SmtpConfig smtpConfig) throws Exception {
        if (!alertConfig.hasGaugeThreshold()) {
            // AlertConfig has nice toString() from immutables
            logger.warn("alert config missing gaugeThreshold: {}", alertConfig);
            return;
        }
        double threshold = alertConfig.getGaugeThreshold().getValue();
        long startTime = endTime - SECONDS.toMillis(alertConfig.getTimePeriodSeconds());
        int rollupLevel = rollupLevelService.getRollupLevelForView(startTime, endTime);
        // startTime + 1 in order to not include the gauge value at startTime
        List<GaugeValue> gaugeValues = gaugeValueRepository.readGaugeValues(agentRollupId,
                alertConfig.getGaugeName(), startTime + 1, endTime, rollupLevel);
        if (gaugeValues.isEmpty()) {
            return;
        }
        double totalWeightedValue = 0;
        long totalWeight = 0;
        for (GaugeValue gaugeValue : gaugeValues) {
            totalWeightedValue += gaugeValue.getValue() * gaugeValue.getWeight();
            totalWeight += gaugeValue.getWeight();
        }
        double average = totalWeightedValue / totalWeight;
        String version = Versions.getVersion(alertConfig);
        boolean previouslyTriggered = triggeredAlertRepository.exists(agentRollupId, version);
        boolean currentlyTriggered = average >= threshold;
        if (previouslyTriggered && !currentlyTriggered) {
            triggeredAlertRepository.delete(agentRollupId, version);
            sendGaugeAlert(agentRollupId, alertConfig, threshold, true, smtpConfig);
        } else if (!previouslyTriggered && currentlyTriggered) {
            triggeredAlertRepository.insert(agentRollupId, version);
            sendGaugeAlert(agentRollupId, alertConfig, threshold, false, smtpConfig);
        }
    }

    // this is only used by central
    public void checkHeartbeatAlert(String agentRollupId, AlertConfig alertConfig,
            boolean currentlyTriggered, SmtpConfig smtpConfig) throws Exception {
        String version = Versions.getVersion(alertConfig);
        boolean previouslyTriggered = triggeredAlertRepository.exists(agentRollupId, version);
        if (previouslyTriggered && !currentlyTriggered) {
            triggeredAlertRepository.delete(agentRollupId, version);
            sendHeartbeatAlert(agentRollupId, alertConfig, true, smtpConfig);
        } else if (!previouslyTriggered && currentlyTriggered) {
            triggeredAlertRepository.insert(agentRollupId, version);
            sendHeartbeatAlert(agentRollupId, alertConfig, false, smtpConfig);
        }
    }

    private void sendTransactionAlert(String agentRollupId, AlertConfig alertConfig,
            double percentile, long thresholdMillis, boolean ok, SmtpConfig smtpConfig)
            throws Exception {
        // subject is the same between initial and ok messages so they will be threaded by gmail
        String subject = "Glowroot alert";
        if (!agentRollupId.equals("")) {
            subject += " - " + agentRollupId;
        }
        subject += " - " + alertConfig.getTransactionType();
        StringBuilder sb = new StringBuilder();
        sb.append(Utils.getPercentileWithSuffix(percentile));
        sb.append(" percentile over the last ");
        sb.append(alertConfig.getTimePeriodSeconds() / 60);
        sb.append(" minute");
        if (alertConfig.getTimePeriodSeconds() != 60) {
            sb.append("s");
        }
        if (ok) {
            sb.append(" has dropped back below alert threshold of ");
        } else {
            sb.append(" exceeded alert threshold of ");
        }
        sb.append(thresholdMillis);
        sb.append(" millisecond");
        if (thresholdMillis != 1) {
            sb.append("s");
        }
        sb.append(".");
        sendEmail(alertConfig.getEmailAddressList(), subject, sb.toString(), smtpConfig,
                configRepository.getSecretKey(), mailService);
    }

    private void sendGaugeAlert(String agentRollupId, AlertConfig alertConfig, double threshold,
            boolean ok, SmtpConfig smtpConfig) throws Exception {
        // subject is the same between initial and ok messages so they will be threaded by gmail
        String subject = "Glowroot alert";
        if (!agentRollupId.equals("")) {
            subject += " - " + agentRollupId;
        }
        Gauge gauge = Gauges.getGauge(alertConfig.getGaugeName());
        subject += " - " + gauge.display();
        StringBuilder sb = new StringBuilder();
        sb.append("Average over the last ");
        sb.append(alertConfig.getTimePeriodSeconds() / 60);
        sb.append(" minute");
        if (alertConfig.getTimePeriodSeconds() != 60) {
            sb.append("s");
        }
        if (ok) {
            sb.append(" has dropped back below alert threshold of ");
        } else {
            sb.append(" exceeded alert threshold of ");
        }
        sb.append(displaySixDigitsOfPrecision(threshold));
        String unit = gauge.unit();
        if (!unit.isEmpty()) {
            sb.append(" ");
            sb.append(unit);
        }
        sb.append(".\n\n");
        sendEmail(alertConfig.getEmailAddressList(), subject, sb.toString(), smtpConfig,
                configRepository.getSecretKey(), mailService);
    }

    private void sendHeartbeatAlert(String agentRollupId, AlertConfig alertConfig, boolean ok,
            SmtpConfig smtpConfig) throws Exception {
        // subject is the same between initial and ok messages so they will be threaded by gmail
        String subject = "Glowroot alert";
        if (!agentRollupId.equals("")) {
            subject += " - " + agentRollupId;
        }
        subject += " - Heartbeat";
        StringBuilder sb = new StringBuilder();
        if (ok) {
            sb.append("Receving heartbeat again.\n\n");
        } else {
            sb.append("Heartbeat not received in the last ");
            sb.append(alertConfig.getTimePeriodSeconds());
            sb.append(" seconds.\n\n");
        }
        sendEmail(alertConfig.getEmailAddressList(), subject, sb.toString(), smtpConfig,
                configRepository.getSecretKey(), mailService);
    }

    public static void sendEmail(List<String> emailAddresses, String subject, String messageText,
            SmtpConfig smtpConfig, SecretKey secretKey, MailService mailService) throws Exception {
        Session session = createMailSession(smtpConfig, secretKey);
        Message message = new MimeMessage(session);
        String fromEmailAddress = smtpConfig.fromEmailAddress();
        if (fromEmailAddress.isEmpty()) {
            String localServerName = InetAddress.getLocalHost().getHostName();
            fromEmailAddress = "glowroot@" + localServerName;
        }
        String fromDisplayName = smtpConfig.fromDisplayName();
        if (fromDisplayName.isEmpty()) {
            fromDisplayName = "Glowroot";
        }
        message.setFrom(new InternetAddress(fromEmailAddress, fromDisplayName));
        Address[] emailAddrs = new Address[emailAddresses.size()];
        for (int i = 0; i < emailAddresses.size(); i++) {
            emailAddrs[i] = new InternetAddress(emailAddresses.get(i));
        }
        message.setRecipients(Message.RecipientType.TO, emailAddrs);
        message.setSubject(subject);
        message.setText(messageText);
        mailService.send(message);
    }

    private static String displaySixDigitsOfPrecision(double value) {
        return displaySixDigitsOfPrecision(value, NumberFormat.getNumberInstance());
    }

    // this mimics the javascript function of same name in gauge-values.js
    @VisibleForTesting
    static String displaySixDigitsOfPrecision(double value, NumberFormat numberFormat) {
        numberFormat.setMaximumFractionDigits(20);
        if (value < 1000000) {
            return numberFormat
                    .format(new BigDecimal(value).round(new MathContext(6, RoundingMode.HALF_UP)));
        } else {
            return numberFormat.format(Math.round(value));
        }
    }

    private static Session createMailSession(SmtpConfig smtpConfig, SecretKey secretKey)
            throws Exception {
        Properties props = new Properties();
        props.put("mail.smtp.host", smtpConfig.host());
        Integer port = smtpConfig.port();
        if (port == null) {
            port = 25;
        }
        props.put("mail.smtp.port", port);
        if (smtpConfig.ssl()) {
            props.put("mail.smtp.socketFactory.port", port);
            props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
        }
        for (Entry<String, String> entry : smtpConfig.additionalProperties().entrySet()) {
            props.put(entry.getKey(), entry.getValue());
        }
        Authenticator authenticator = null;
        if (!smtpConfig.password().isEmpty()) {
            props.put("mail.smtp.auth", "true");
            final String username = smtpConfig.username();
            final String password = Encryption.decrypt(smtpConfig.password(), secretKey);
            authenticator = new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(username, password);
                }
            };
        }
        return Session.getInstance(props, authenticator);
    }
}
