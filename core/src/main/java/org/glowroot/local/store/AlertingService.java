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

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.text.DecimalFormat;
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

import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.collector.Aggregate;
import org.glowroot.collector.LazyHistogram;
import org.glowroot.common.Encryption;
import org.glowroot.config.AlertConfig;
import org.glowroot.config.ConfigService;
import org.glowroot.config.SmtpConfig;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;

public class AlertingService {

    private static final Logger logger = LoggerFactory.getLogger(AlertingService.class);

    private final ConfigService configService;
    private final TriggeredAlertDao triggeredAlertDao;
    private final AggregateDao aggregateDao;
    private final MailService mailService;

    AlertingService(ConfigService configService, TriggeredAlertDao triggeredAlertDao,
            AggregateDao aggregateDao, MailService mailService) {
        this.configService = configService;
        this.triggeredAlertDao = triggeredAlertDao;
        this.aggregateDao = aggregateDao;
        this.mailService = mailService;
    }

    void checkAlerts(long endTime) {
        for (AlertConfig alertConfig : configService.getAlertConfigs()) {
            try {
                checkAlert(alertConfig, endTime);
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    private void checkAlert(AlertConfig alertConfig, long endTime) throws Exception {
        long startTime = endTime - MINUTES.toMillis(alertConfig.timePeriodMinutes());
        // don't want to include the aggregate at startTime, so add 1
        startTime++;
        int rollupLevel = aggregateDao.getRollupLevelForView(startTime, endTime);
        ImmutableList<Aggregate> aggregates = aggregateDao.readOverallAggregates(
                alertConfig.transactionType(), startTime, endTime, rollupLevel);
        long transactionCount = 0;
        LazyHistogram histogram = new LazyHistogram();
        for (Aggregate aggregate : aggregates) {
            transactionCount += aggregate.transactionCount();
            histogram.decodeFromByteBuffer(ByteBuffer.wrap(aggregate.histogram()));
        }
        if (transactionCount < alertConfig.minTransactionCount()) {
            // don't clear existing triggered alert
            return;
        }
        boolean previouslyTriggered = triggeredAlertDao.exists(alertConfig.version());
        long valueAtPercentile = histogram.getValueAtPercentile(alertConfig.percentile());
        boolean currentlyTriggered =
                valueAtPercentile >= MILLISECONDS.toMicros(alertConfig.thresholdMillis());
        if (previouslyTriggered && !currentlyTriggered) {
            triggeredAlertDao.delete(alertConfig.version());
            sendAlert(alertConfig, valueAtPercentile, transactionCount, true);
        } else if (!previouslyTriggered && currentlyTriggered) {
            triggeredAlertDao.insert(alertConfig.version(), endTime);
            sendAlert(alertConfig, valueAtPercentile, transactionCount, false);
        }
    }

    private void sendAlert(AlertConfig alertConfig, long valueAtPercentile, long transactionCount,
            boolean ok) throws Exception {
        SmtpConfig smtpConfig = configService.getSmtpConfig();
        Session session = createMailSession(smtpConfig, configService.getSecretKey());
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
        List<String> allTransactionTypes = configService.getAllTransactionTypes();
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
        sb.append(getPercentileWithSuffix(alertConfig.percentile()));
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

    public static Session createMailSession(SmtpConfig smtpConfig, SecretKey secretKey)
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

    public static String getPercentileWithSuffix(double percentile) {
        String percentileText = new DecimalFormat("0.#########").format(percentile);
        return percentileText + getPercentileSuffix(percentileText);
    }

    private static String getPercentileSuffix(String percentileText) {
        if (percentileText.equals("11") || percentileText.endsWith(".11")) {
            return "th";
        } else if (percentileText.equals("12") || percentileText.endsWith(".12")) {
            return "th";
        } else if (percentileText.equals("13") || percentileText.endsWith(".13")) {
            return "th";
        }
        switch (percentileText.charAt(percentileText.length() - 1)) {
            case '1':
                return "st";
            case '2':
                return "nd";
            case '3':
                return "rd";
            default:
                return "th";
        }
    }
}
