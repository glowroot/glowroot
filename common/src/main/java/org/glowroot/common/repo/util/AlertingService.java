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
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.config.SmtpConfig;
import org.glowroot.common.repo.AggregateRepository;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.GaugeValueRepository;
import org.glowroot.common.repo.GaugeValueRepository.Gauge;
import org.glowroot.common.repo.TriggeredAlertRepository;
import org.glowroot.common.repo.Utils;
import org.glowroot.common.util.Formatting;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition.HeartbeatCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition.MetricCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertNotification;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.SECONDS;

public class AlertingService {

    private static final Logger logger = LoggerFactory.getLogger(AlertingService.class);

    private final ConfigRepository configRepository;
    private final TriggeredAlertRepository triggeredAlertRepository;
    private final MailService mailService;

    private final MetricService metricService;

    // limit missing smtp host configuration warning to once per hour
    private final RateLimiter smtpHostWarningRateLimiter = RateLimiter.create(1.0 / 3600);

    public AlertingService(ConfigRepository configRepository,
            TriggeredAlertRepository triggeredAlertRepository,
            AggregateRepository aggregateRepository, GaugeValueRepository gaugeValueRepository,
            RollupLevelService rollupLevelService, MailService mailService) {
        this.configRepository = configRepository;
        this.triggeredAlertRepository = triggeredAlertRepository;
        this.mailService = mailService;
        this.metricService =
                new MetricService(aggregateRepository, gaugeValueRepository, rollupLevelService);
    }

    public void checkForDeletedAlerts(String agentRollupId) throws Exception {
        Set<AlertCondition> alertConditions = Sets.newHashSet();
        for (AlertConfig alertConfig : configRepository.getAlertConfigs(agentRollupId)) {
            alertConditions.add(alertConfig.getCondition());
        }
        for (AlertCondition alertCondition : triggeredAlertRepository
                .readAlertConditions(agentRollupId)) {
            if (!alertConditions.contains(alertCondition)) {
                triggeredAlertRepository.delete(agentRollupId, alertCondition);
            }
        }
    }

    public void checkMetricAlert(String agentRollupId, String agentRollupDisplay,
            AlertCondition alertCondition, MetricCondition metricCondition,
            AlertNotification alertNotification, long endTime) throws Exception {

        long startTime = endTime - SECONDS.toMillis(metricCondition.getTimePeriodSeconds());
        Number value =
                metricService.getMetricValue(agentRollupId, metricCondition, startTime, endTime);
        if (value == null) {
            // cannot calculate due to no data, e.g. error rate (but not error count, which can be
            // calculated - zero - when no data)
            return;
        }
        boolean currentlyTriggered;
        if (metricCondition.getLowerBoundThreshold()) {
            currentlyTriggered = value.doubleValue() < metricCondition.getThreshold();
        } else {
            currentlyTriggered = value.doubleValue() > metricCondition.getThreshold();
        }
        boolean previouslyTriggered =
                triggeredAlertRepository.exists(agentRollupId, alertCondition);
        if (previouslyTriggered && !currentlyTriggered) {
            triggeredAlertRepository.delete(agentRollupId, alertCondition);
            sendMetricAlert(agentRollupId, agentRollupDisplay, metricCondition, alertNotification,
                    true);
        } else if (!previouslyTriggered && currentlyTriggered) {
            triggeredAlertRepository.insert(agentRollupId, alertCondition);
            sendMetricAlert(agentRollupId, agentRollupDisplay, metricCondition, alertNotification,
                    false);
        }
    }

    // only used by central
    public void sendHeartbeatAlertIfNeeded(String agentRollupId, String agentRollupDisplay,
            AlertCondition alertCondition, HeartbeatCondition heartbeatCondition,
            AlertNotification alertNotification, boolean currentlyTriggered) throws Exception {
        boolean previouslyTriggered =
                triggeredAlertRepository.exists(agentRollupId, alertCondition);
        if (previouslyTriggered && !currentlyTriggered) {
            triggeredAlertRepository.delete(agentRollupId, alertCondition);
            sendHeartbeatAlert(agentRollupDisplay, heartbeatCondition, alertNotification,
                    true);
        } else if (!previouslyTriggered && currentlyTriggered) {
            triggeredAlertRepository.insert(agentRollupId, alertCondition);
            sendHeartbeatAlert(agentRollupDisplay, heartbeatCondition, alertNotification,
                    false);
        }
    }

    private void sendMetricAlert(String agentRollupId, String agentRollupDisplay,
            MetricCondition metricCondition, AlertNotification alertNotification, boolean ok)
            throws Exception {
        // subject is the same between initial and ok messages so they will be threaded by gmail
        StringBuilder subject = new StringBuilder();
        subject.append("Glowroot alert");
        if (!agentRollupId.isEmpty()) {
            subject.append(" - ");
            subject.append(agentRollupDisplay);
        }
        String metric = metricCondition.getMetric();
        String transactionType = metricCondition.getTransactionType();
        if (!transactionType.isEmpty()) {
            subject.append(" - ");
            subject.append(transactionType);
        }
        String transactionName = metricCondition.getTransactionName();
        if (!transactionName.isEmpty()) {
            subject.append(" - ");
            subject.append(transactionName);
        }
        Gauge gauge = null;
        StringBuilder message = new StringBuilder();
        if (metric.equals("transaction:x-percentile")) {
            checkState(metricCondition.hasPercentile());
            message.append(
                    Utils.getPercentileWithSuffix(metricCondition.getPercentile().getValue()));
            message.append(" percentile");
        } else if (metric.equals("transaction:average")) {
            message.append("Average");
        } else if (metric.equals("transaction:count")) {
            message.append("Transaction count");
        } else if (metric.equals("error:rate")) {
            message.append("Error rate");
        } else if (metric.equals("error:count")) {
            message.append("Error count");
        } else if (metric.startsWith("gauge:")) {
            String gaugeName = metric.substring("gauge:".length());
            gauge = Gauges.getGauge(gaugeName);
            subject.append(" - ");
            subject.append(gauge.display());
            message.append("Average");
        } else {
            throw new IllegalStateException("Unexpected metric: " + metric);
        }
        message.append(getOverTheLastMinutesText(metricCondition.getTimePeriodSeconds()));
        if (metricCondition.getLowerBoundThreshold()) {
            message.append(getPreLowerBoundText(ok));
        } else {
            message.append(getPreUpperBoundText(ok));
        }
        if (metric.equals("transaction:x-percentile") || metric.equals("transaction:average")) {
            message.append(
                    AlertingService.getWithUnit(metricCondition.getThreshold(), "millisecond"));
        } else if (metric.equals("transaction:count")) {
            message.append(metricCondition.getThreshold());
        } else if (metric.equals("error:rate")) {
            message.append(metricCondition.getThreshold());
            message.append("percent");
        } else if (metric.equals("error:count")) {
            message.append(metricCondition.getThreshold());
        } else if (metric.startsWith("gauge:")) {
            message.append(getGaugeThresholdText(metricCondition.getThreshold(),
                    checkNotNull(gauge).unit()));
        } else {
            throw new IllegalStateException("Unexpected metric: " + metric);
        }
        message.append(".\n\n");
        sendNotification(alertNotification, subject.toString(), message.toString());
    }

    private void sendHeartbeatAlert(String agentRollupDisplay,
            HeartbeatCondition heartbeatCondition, AlertNotification alertNotification, boolean ok)
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
            sb.append(heartbeatCondition.getTimePeriodSeconds());
            sb.append(" seconds.\n\n");
        }
        sendNotification(alertNotification, subject, sb.toString());
    }

    public void sendNotification(AlertNotification alertNotification, String subject,
            String messageText) throws Exception {
        if (alertNotification.hasEmailNotification()) {
            SmtpConfig smtpConfig = configRepository.getSmtpConfig();
            if (smtpConfig.host().isEmpty()) {
                if (smtpHostWarningRateLimiter.tryAcquire()) {
                    logger.warn("not sending alert due to missing SMTP host configuration"
                            + " (this warning will be logged at most once an hour)");
                }
            } else {
                sendEmail(alertNotification.getEmailNotification().getEmailAddressList(), subject,
                        messageText, smtpConfig, null, configRepository.getLazySecretKey(),
                        mailService);
            }
        }
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

    public static String getPreUpperBoundText(boolean ok) {
        if (ok) {
            return " no longer exceeds alert threshold of ";
        } else {
            return " has exceeded alert threshold of ";
        }
    }

    public static String getGaugeThresholdText(double threshold, String gaugeUnit) {
        if (gaugeUnit.equals("bytes")) {
            return Formatting.formatBytes((long) threshold);
        } else if (!gaugeUnit.isEmpty()) {
            return Formatting.displaySixDigitsOfPrecision(threshold) + " " + gaugeUnit;
        } else {
            return Formatting.displaySixDigitsOfPrecision(threshold);
        }
    }

    public static String getOverTheLastMinutesText(int timePeriodSeconds) {
        return " over the last " + getWithUnit(timePeriodSeconds / 60.0, "minute");
    }

    public static String getWithUnit(double val, String unit) {
        String text = Formatting.displaySixDigitsOfPrecision(val) + " " + unit;
        if (val != 1) {
            text += "s";
        }
        return text;
    }

    private static String getPreLowerBoundText(boolean ok) {
        if (ok) {
            return " has risen back above alert threshold of ";
        } else {
            return " has dropped below alert threshold of ";
        }
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

    public static class HttpClientHandler extends SimpleChannelInboundHandler<HttpObject> {

        @Override
        public void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
            if (msg instanceof HttpResponse) {
                HttpResponse response = (HttpResponse) msg;
                if (!response.status().equals(HttpResponseStatus.OK)) {
                    if (msg instanceof HttpContent) {
                        HttpContent httpContent = (HttpContent) msg;
                        String content = httpContent.content().toString(CharsetUtil.UTF_8);
                        logger.warn("unexpected response status: {}, content: {}",
                                response.status(), content);
                    } else {
                        logger.warn("unexpected response status: {}", response.status());
                    }
                }
            } else {
                logger.error("unexpected response: {}", msg);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.error(cause.getMessage(), cause);
            ctx.close();
        }
    }
}
