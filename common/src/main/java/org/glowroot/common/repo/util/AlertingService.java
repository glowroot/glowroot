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

import java.net.InetAddress;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;

import javax.annotation.Nullable;
import javax.mail.Address;
import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import com.google.common.collect.Sets;
import com.google.common.util.concurrent.RateLimiter;
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
import org.glowroot.common.util.Formatting;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValue;

import static com.google.common.base.Preconditions.checkState;
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

    // limit missing smtp host configuration warning to once per hour
    private final RateLimiter smtpHostWarningRateLimiter = RateLimiter.create(1.0 / 3600);

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

    public void checkForDeletedAlerts(String agentRollupId) throws Exception {
        Set<AlertConfig> alertConditions = Sets.newHashSet();
        for (AlertConfig alertConfig : configRepository.getAlertConfigs(agentRollupId)) {
            alertConditions.add(toAlertCondition(alertConfig));
        }
        for (AlertConfig alertCondition : triggeredAlertRepository
                .readAlertConditions(agentRollupId)) {
            if (!alertConditions.contains(alertCondition)) {
                triggeredAlertRepository.delete(agentRollupId, alertCondition);
            }
        }
    }

    public void checkTransactionAlert(String agentRollupId, String agentRollupDisplay,
            AlertConfig alertConfig, long endTime) throws Exception {
        // validate config
        if (!alertConfig.hasTransactionPercentile()) {
            // AlertConfig has nice toString() from immutables
            logger.warn("alert config missing transactionPercentile: {}", alertConfig);
            return;
        }
        if (!alertConfig.hasThresholdMillis()) {
            // AlertConfig has nice toString() from immutables
            logger.warn("alert config missing thresholdMillis: {}", alertConfig);
            return;
        }
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
        boolean previouslyTriggered = triggeredAlertRepository.exists(agentRollupId, alertConfig);
        long valueAtPercentile = durationNanosHistogram
                .getValueAtPercentile(alertConfig.getTransactionPercentile().getValue());
        boolean currentlyTriggered = valueAtPercentile >= MILLISECONDS
                .toNanos(alertConfig.getThresholdMillis().getValue());
        if (previouslyTriggered && !currentlyTriggered) {
            triggeredAlertRepository.delete(agentRollupId, alertConfig);
            sendTransactionAlert(agentRollupDisplay, alertConfig, true);
        } else if (!previouslyTriggered && currentlyTriggered) {
            triggeredAlertRepository.insert(agentRollupId, alertConfig);
            sendTransactionAlert(agentRollupDisplay, alertConfig, false);
        }
    }

    public void checkGaugeAlert(String agentRollupId, String agentRollupDisplay,
            AlertConfig alertConfig, long endTime) throws Exception {
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
        // individual gauge value weights cannot be zero, and gaugeValues is non-empty
        // (see above conditional), so totalWeight is guaranteed non-zero
        checkState(totalWeight != 0);
        double average = totalWeightedValue / totalWeight;
        boolean previouslyTriggered = triggeredAlertRepository.exists(agentRollupId, alertConfig);
        boolean currentlyTriggered = average >= threshold;
        if (previouslyTriggered && !currentlyTriggered) {
            triggeredAlertRepository.delete(agentRollupId, alertConfig);
            sendGaugeAlert(agentRollupDisplay, alertConfig, threshold, true);
        } else if (!previouslyTriggered && currentlyTriggered) {
            triggeredAlertRepository.insert(agentRollupId, alertConfig);
            sendGaugeAlert(agentRollupDisplay, alertConfig, threshold, false);
        }
    }

    // only used by central
    public void sendHeartbeatAlertIfNeeded(String agentRollupId, String agentRollupDisplay,
            AlertConfig alertConfig, boolean currentlyTriggered) throws Exception {
        boolean previouslyTriggered = triggeredAlertRepository.exists(agentRollupId, alertConfig);
        if (previouslyTriggered && !currentlyTriggered) {
            triggeredAlertRepository.delete(agentRollupId, alertConfig);
            sendHeartbeatAlert(agentRollupDisplay, alertConfig, true);
        } else if (!previouslyTriggered && currentlyTriggered) {
            triggeredAlertRepository.insert(agentRollupId, alertConfig);
            sendHeartbeatAlert(agentRollupDisplay, alertConfig, false);
        }
    }

    private void sendTransactionAlert(String agentRollupDisplay, AlertConfig alertConfig,
            boolean ok) throws Exception {
        // subject is the same between initial and ok messages so they will be threaded by gmail
        String subject = "Glowroot alert";
        if (!agentRollupDisplay.equals("")) {
            subject += " - " + agentRollupDisplay;
        }
        subject += " - " + alertConfig.getTransactionType();
        StringBuilder sb = new StringBuilder();
        sb.append(Utils.getPercentileWithSuffix(alertConfig.getTransactionPercentile().getValue()));
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
        int thresholdMillis = alertConfig.getThresholdMillis().getValue();
        sb.append(thresholdMillis);
        sb.append(" millisecond");
        if (thresholdMillis != 1) {
            sb.append("s");
        }
        sb.append(".");
        sendNotification(alertConfig, subject, sb.toString());
    }

    private void sendGaugeAlert(String agentRollupDisplay, AlertConfig alertConfig,
            double threshold, boolean ok) throws Exception {
        // subject is the same between initial and ok messages so they will be threaded by gmail
        String subject = "Glowroot alert";
        if (!agentRollupDisplay.equals("")) {
            subject += " - " + agentRollupDisplay;
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
        String unit = gauge.unit();
        if (unit.equals("bytes")) {
            sb.append(Formatting.formatBytes((long) threshold));
        } else if (!unit.isEmpty()) {
            sb.append(Formatting.displaySixDigitsOfPrecision(threshold));
            sb.append(" ");
            sb.append(unit);
        } else {
            sb.append(Formatting.displaySixDigitsOfPrecision(threshold));
        }
        sb.append(".\n\n");
        sendNotification(alertConfig, subject, sb.toString());
    }

    private void sendHeartbeatAlert(String agentRollupDisplay, AlertConfig alertConfig, boolean ok)
            throws Exception {
        // subject is the same between initial and ok messages so they will be threaded by gmail
        String subject = "Glowroot alert";
        if (!agentRollupDisplay.equals("")) {
            subject += " - " + agentRollupDisplay;
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
        sendNotification(alertConfig, subject, sb.toString());
    }

    public void sendNotification(AlertConfig alertConfig, String subject, String messageText)
            throws Exception {
        SmtpConfig smtpConfig = configRepository.getSmtpConfig();
        if (smtpConfig.host().isEmpty()) {
            if (smtpHostWarningRateLimiter.tryAcquire()) {
                logger.warn("not sending alert due to missing SMTP host configuration"
                        + " (this warning will be logged at most once an hour)");
            }
            return;
        }
        sendEmail(alertConfig.getEmailAddressList(), subject, messageText, smtpConfig, null,
                configRepository.getLazySecretKey(), mailService);
    }

    public static AlertConfig toAlertCondition(AlertConfig alertConfig) {
        return alertConfig.toBuilder()
                .clearEmailAddress()
                .build();
    }

    // optional newPlainPassword can be passed in to test SMTP from
    // AdminJsonService.sentTestEmail() without possibility of throwing
    // org.glowroot.common.repo.util.LazySecretKey.SymmetricEncryptionKeyMissingException
    public static void sendEmail(List<String> emailAddresses, String subject, String messageText,
            SmtpConfig smtpConfig, @Nullable String passwordOverride, LazySecretKey lazySecretKey,
            MailService mailService) throws Exception {
        Session session = createMailSession(smtpConfig, passwordOverride, lazySecretKey);
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

    // optional newPlainPassword can be passed in to test SMTP from
    // AdminJsonService.sentTestEmail() without possibility of throwing
    // org.glowroot.common.repo.util.LazySecretKey.SymmetricEncryptionKeyMissingException
    private static Session createMailSession(SmtpConfig smtpConfig,
            @Nullable String passwordOverride, LazySecretKey lazySecretKey) throws Exception {
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
        final String password = getPassword(smtpConfig, passwordOverride, lazySecretKey);
        if (!password.isEmpty()) {
            props.put("mail.smtp.auth", "true");
            final String username = smtpConfig.username();
            authenticator = new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(username, password);
                }
            };
        }
        return Session.getInstance(props, authenticator);
    }

    private static String getPassword(SmtpConfig smtpConfig, @Nullable String passwordOverride,
            LazySecretKey lazySecretKey) throws Exception {
        if (passwordOverride != null) {
            return passwordOverride;
        }
        String password = smtpConfig.password();
        if (password.isEmpty()) {
            return "";
        }
        return Encryption.decrypt(password, lazySecretKey);
    }
}
