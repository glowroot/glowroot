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

import java.net.InetAddress;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;

import javax.annotation.Nullable;
import javax.crypto.SecretKey;
import javax.mail.Address;
import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

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
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertKind;
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

    public void checkTransactionAlerts(final String agentRollup, final long endTime,
            @Nullable Class<? extends Exception> retryOnceOnException) throws Exception {
        if (configRepository.getSmtpConfig().host().isEmpty()) {
            return;
        }
        for (final AlertConfig alertConfig : configRepository.getAlertConfigs(agentRollup)) {
            if (alertConfig.getKind() != AlertKind.TRANSACTION) {
                continue;
            }
            retryOnceOnException(new Retryable() {
                @Override
                public void execute() throws Exception {
                    checkTransactionAlert(agentRollup, alertConfig, endTime);
                }
            }, retryOnceOnException);
        }
    }

    public void checkGaugeAlerts(final String agentRollup, final long endTime,
            @Nullable Class<? extends Exception> retryOnceOnException) throws Exception {
        if (configRepository.getSmtpConfig().host().isEmpty()) {
            return;
        }
        for (final AlertConfig alertConfig : configRepository.getAlertConfigs(agentRollup)) {
            if (alertConfig.getKind() != AlertKind.GAUGE) {
                continue;
            }
            retryOnceOnException(new Retryable() {
                @Override
                public void execute() throws Exception {
                    checkGaugeAlert(agentRollup, alertConfig, endTime);
                }
            }, retryOnceOnException);
        }
    }

    private void checkTransactionAlert(String agentRollup, AlertConfig alertConfig, long endTime)
            throws Exception {
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
        // don't want to include the aggregate at startTime, so add 1
        startTime++;
        int rollupLevel = rollupLevelService.getRollupLevelForView(startTime, endTime);
        List<PercentileAggregate> percentileAggregates =
                aggregateRepository.readPercentileAggregates(agentRollup,
                        ImmutableTransactionQuery.builder()
                                .transactionType(alertConfig.getTransactionType())
                                .from(startTime)
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
        boolean previouslyTriggered = triggeredAlertRepository.exists(agentRollup, version);
        long valueAtPercentile = durationNanosHistogram.getValueAtPercentile(percentile);
        boolean currentlyTriggered = valueAtPercentile >= MILLISECONDS.toNanos(thresholdMillis);
        if (previouslyTriggered && !currentlyTriggered) {
            triggeredAlertRepository.delete(agentRollup, version);
            sendTransactionAlert(agentRollup, alertConfig, percentile, valueAtPercentile,
                    transactionCount, true);
        } else if (!previouslyTriggered && currentlyTriggered) {
            triggeredAlertRepository.insert(agentRollup, version);
            sendTransactionAlert(agentRollup, alertConfig, percentile, valueAtPercentile,
                    transactionCount, false);
        }
    }

    private void checkGaugeAlert(String agentRollup, AlertConfig alertConfig, long endTime)
            throws Exception {
        if (!alertConfig.hasGaugeThreshold()) {
            // AlertConfig has nice toString() from immutables
            logger.warn("alert config missing gaugeThreshold: {}", alertConfig);
            return;
        }
        double threshold = alertConfig.getGaugeThreshold().getValue();
        long startTime = endTime - SECONDS.toMillis(alertConfig.getTimePeriodSeconds());
        // don't want to include the aggregate at startTime, so add 1
        startTime++;
        int rollupLevel = rollupLevelService.getRollupLevelForView(startTime, endTime);
        List<GaugeValue> gaugeValues = gaugeValueRepository.readGaugeValues(agentRollup,
                alertConfig.getGaugeName(), startTime, endTime, rollupLevel);
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
        boolean previouslyTriggered = triggeredAlertRepository.exists(agentRollup, version);
        boolean currentlyTriggered = average >= threshold;
        if (previouslyTriggered && !currentlyTriggered) {
            triggeredAlertRepository.delete(agentRollup, version);
            sendGaugeAlert(agentRollup, alertConfig, average, true);
        } else if (!previouslyTriggered && currentlyTriggered) {
            triggeredAlertRepository.insert(agentRollup, version);
            sendGaugeAlert(agentRollup, alertConfig, average, false);
        }
    }

    private void sendTransactionAlert(String agentRollup, AlertConfig alertConfig,
            double percentile, long valueAtPercentile, long transactionCount, boolean ok)
            throws Exception {
        String subject = "Glowroot alert";
        if (!agentRollup.equals("")) {
            subject += " - " + agentRollup;
        }
        subject += " - " + alertConfig.getTransactionType();
        if (ok) {
            subject += " - OK";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(Utils.getPercentileWithSuffix(percentile));
        sb.append(" percentile over the last ");
        sb.append(alertConfig.getTimePeriodSeconds() / 60);
        sb.append(" minutes was ");
        sb.append(Math.round(valueAtPercentile / 1000000.0));
        sb.append(" milliseconds.\n\nTotal transaction count over the last ");
        sb.append(alertConfig.getTimePeriodSeconds() / 60);
        sb.append(" minutes was ");
        sb.append(transactionCount);
        sb.append(".");
        sendEmail(alertConfig.getEmailAddressList(), subject, sb.toString(),
                configRepository.getSmtpConfig(), configRepository, mailService);
    }

    private void sendGaugeAlert(String agentRollup, AlertConfig alertConfig, double average,
            boolean ok) throws Exception {
        String subject = "Glowroot alert";
        if (!agentRollup.equals("")) {
            subject += " - " + agentRollup;
        }
        Gauge gauge = Gauges.getGauge(alertConfig.getGaugeName());
        subject += " - " + gauge.display();
        if (ok) {
            subject += " - OK";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Average over the last ");
        sb.append(alertConfig.getTimePeriodSeconds() / 60);
        sb.append(" minutes was ");
        sb.append(average);
        String unit = gauge.unit();
        if (!unit.isEmpty()) {
            sb.append(" ");
            sb.append(unit);
        }
        sb.append(".\n\n");
        sendEmail(alertConfig.getEmailAddressList(), subject, sb.toString(),
                configRepository.getSmtpConfig(), configRepository, mailService);
    }

    public static void sendEmail(List<String> emailAddresses, String subject, String messageText,
            SmtpConfig smtpConfig, ConfigRepository configRepository, MailService mailService)
            throws Exception {
        Session session = createMailSession(smtpConfig, configRepository.getSecretKey());
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

    private static void retryOnceOnException(Retryable retryable,
            @Nullable Class<? extends Exception> retryOnceOnException) {
        try {
            retryable.execute();
        } catch (Exception e) {
            if (retryOnceOnException != null
                    && retryOnceOnException.isAssignableFrom(e.getClass())) {
                try {
                    retryable.execute();
                    // log initial exception at debug level only
                    logger.debug(e.getMessage(), e);
                } catch (Exception f) {
                    // log initial exception at error level
                    logger.error(e.getMessage(), e);
                }
            } else {
                logger.error(e.getMessage(), e);
            }
        }
    }

    private interface Retryable {
        void execute() throws Exception;
    }
}
