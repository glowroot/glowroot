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

import java.net.InetAddress;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.model.LazyHistogram;
import org.glowroot.storage.repo.AggregateRepository;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.TriggeredAlertRepository;
import org.glowroot.storage.repo.Utils;
import org.glowroot.storage.repo.config.AlertConfig;
import org.glowroot.storage.repo.config.SmtpConfig;
import org.glowroot.storage.util.Encryption;
import org.glowroot.storage.util.MailService;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;

public class AlertingService {

    private static final Logger logger = LoggerFactory.getLogger(AlertingService.class);

    private final ConfigRepository configRepository;
    private final TriggeredAlertRepository triggeredAlertRepository;
    private final AggregateRepository aggregateRepository;
    private final MailService mailService;

    public AlertingService(ConfigRepository configRepository,
            TriggeredAlertRepository triggeredAlertRepository,
            AggregateRepository aggregateRepository, MailService mailService) {
        this.configRepository = configRepository;
        this.triggeredAlertRepository = triggeredAlertRepository;
        this.aggregateRepository = aggregateRepository;
        this.mailService = mailService;
    }

    public void checkAlerts(long endTime) {

        // FIXME for each server
        final String server = "";

        for (AlertConfig alertConfig : configRepository.getAlertConfigs(server)) {
            try {
                checkAlert(server, alertConfig, endTime);
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    private void checkAlert(String server, AlertConfig alertConfig, long endTime) throws Exception {
        long startTime = endTime - MINUTES.toMillis(alertConfig.timePeriodMinutes());
        // don't want to include the aggregate at startTime, so add 1
        startTime++;
        int rollupLevel = aggregateRepository.getRollupLevelForView(server, startTime, endTime);
        List<PercentileAggregate> percentileAggregates =
                aggregateRepository.readOverallPercentileAggregates(server,
                        alertConfig.transactionType(), startTime, endTime, rollupLevel);
        long transactionCount = 0;
        LazyHistogram histogram = new LazyHistogram();
        for (PercentileAggregate aggregate : percentileAggregates) {
            transactionCount += aggregate.transactionCount();
            histogram.merge(aggregate.histogram());
        }
        if (transactionCount < alertConfig.minTransactionCount()) {
            // don't clear existing triggered alert
            return;
        }
        boolean previouslyTriggered = triggeredAlertRepository.exists(alertConfig.version());
        long valueAtPercentile = histogram.getValueAtPercentile(alertConfig.percentile());
        boolean currentlyTriggered =
                valueAtPercentile >= MILLISECONDS.toNanos(alertConfig.thresholdMillis());
        if (previouslyTriggered && !currentlyTriggered) {
            triggeredAlertRepository.delete(alertConfig.version());
            sendAlert(server, alertConfig, valueAtPercentile, transactionCount, true);
        } else if (!previouslyTriggered && currentlyTriggered) {
            triggeredAlertRepository.insert(alertConfig.version(), endTime);
            sendAlert(server, alertConfig, valueAtPercentile, transactionCount, false);
        }
    }

    private void sendAlert(String server, AlertConfig alertConfig, long valueAtPercentile,
            long transactionCount, boolean ok) throws Exception {
        SmtpConfig smtpConfig = configRepository.getSmtpConfig();
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
        Address[] emailAddresses = new Address[alertConfig.emailAddresses().size()];
        for (int i = 0; i < alertConfig.emailAddresses().size(); i++) {
            emailAddresses[i] = new InternetAddress(alertConfig.emailAddresses().get(i));
        }
        message.setRecipients(Message.RecipientType.TO, emailAddresses);
        String subject = "Glowroot alert";
        List<String> allTransactionTypes = configRepository.getAllTransactionTypes(server);
        if (allTransactionTypes.size() != 1
                || !allTransactionTypes.get(0).equals(alertConfig.transactionType())) {
            // only add transaction type if it is not the only one in the system
            subject += " - " + alertConfig.transactionType();
        }
        if (ok) {
            subject += " - OK";
        }
        message.setSubject(subject);
        StringBuilder sb = new StringBuilder();
        sb.append(Utils.getPercentileWithSuffix(alertConfig.percentile()));
        sb.append(" percentile over the last ");
        sb.append(alertConfig.timePeriodMinutes());
        sb.append(" minutes was ");
        sb.append(Math.round(valueAtPercentile / 1000.0));
        sb.append(" milliseconds.\n\nTotal transaction count over the last ");
        sb.append(alertConfig.timePeriodMinutes());
        sb.append(" minutes was ");
        sb.append(transactionCount);
        sb.append(".");
        message.setText(sb.toString());
        mailService.send(message);
    }

    public static void sendTestEmails(String testEmailRecipient, SmtpConfig smtpConfig,
            ConfigRepository configRepository, MailService mailService) throws Exception {
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
        InternetAddress to = new InternetAddress(testEmailRecipient);
        message.setRecipient(Message.RecipientType.TO, to);
        message.setSubject("Test email from Glowroot (EOM)");
        message.setText("");
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
        if (!smtpConfig.encryptedPassword().isEmpty()) {
            props.put("mail.smtp.auth", "true");
            final String username = smtpConfig.username();
            final String password = Encryption.decrypt(smtpConfig.encryptedPassword(), secretKey);
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
