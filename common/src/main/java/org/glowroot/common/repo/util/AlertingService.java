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

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Calendar;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import javax.annotation.Nullable;
import javax.mail.Address;
import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.xml.bind.DatatypeConverter;

import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.config.SmtpConfig;
import org.glowroot.common.config.SmtpConfig.ConnectionSecurity;
import org.glowroot.common.repo.AggregateRepository;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.GaugeValueRepository;
import org.glowroot.common.repo.GaugeValueRepository.Gauge;
import org.glowroot.common.repo.IncidentRepository;
import org.glowroot.common.repo.IncidentRepository.OpenIncident;
import org.glowroot.common.repo.Utils;
import org.glowroot.common.repo.util.HttpClient.TooManyRequestsHttpResponseException;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.Formatting;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.Versions;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition.MetricCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertNotification;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertNotification.PagerDutyNotification;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertSeverity;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

public class AlertingService {

    private static final Logger logger = LoggerFactory.getLogger(AlertingService.class);

    private final ConfigRepository configRepository;
    private final IncidentRepository incidentRepository;
    private final MailService mailService;
    private final HttpClient httpClient;
    private final Clock clock;

    private final MetricService metricService;

    // limit missing smtp host configuration warning to once per hour
    private final RateLimiter smtpHostWarningRateLimiter = RateLimiter.create(1.0 / 3600);

    private final ScheduledExecutorService pagerDutyRetryExecutor;

    private volatile boolean closed;

    public AlertingService(ConfigRepository configRepository, IncidentRepository incidentRepository,
            AggregateRepository aggregateRepository, GaugeValueRepository gaugeValueRepository,
            RollupLevelService rollupLevelService, MailService mailService, HttpClient httpClient,
            Clock clock) {
        this.configRepository = configRepository;
        this.incidentRepository = incidentRepository;
        this.mailService = mailService;
        this.httpClient = httpClient;
        this.clock = clock;
        this.metricService =
                new MetricService(aggregateRepository, gaugeValueRepository, rollupLevelService);
        pagerDutyRetryExecutor = Executors.newSingleThreadScheduledExecutor();
    }

    public void close() throws InterruptedException {
        closed = true;
        // shutdownNow() is needed here to send interrupt to pager duty retry thread
        pagerDutyRetryExecutor.shutdownNow();
        if (!pagerDutyRetryExecutor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException(
                    "Timed out waiting for pager duty retry thread to terminate");
        }
    }

    public void checkForDeletedAlerts(String agentRollupId) throws Exception {
        Set<AlertCondition> alertConditions = Sets.newHashSet();
        for (AlertConfig alertConfig : configRepository.getAlertConfigs(agentRollupId)) {
            alertConditions.add(alertConfig.getCondition());
        }
        for (OpenIncident openIncident : incidentRepository.readOpenIncidents(agentRollupId)) {
            if (!alertConditions.contains(openIncident.condition())) {
                incidentRepository.resolveIncident(openIncident, clock.currentTimeMillis());
            }
        }
    }

    public void checkMetricAlert(String centralDisplay, String agentRollupId,
            String agentRollupDisplay, AlertConfig alertConfig, MetricCondition metricCondition,
            long endTime) throws Exception {
        AlertCondition alertCondition = alertConfig.getCondition();
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
        OpenIncident openIncident = incidentRepository.readOpenIncident(agentRollupId,
                alertCondition, alertConfig.getSeverity());
        if (openIncident != null && !currentlyTriggered) {
            incidentRepository.resolveIncident(openIncident, endTime);
            sendMetricAlert(centralDisplay, agentRollupId, agentRollupDisplay, alertConfig,
                    metricCondition, endTime, true);
        } else if (openIncident == null && currentlyTriggered) {
            // the start time for the incident is the end time of the interval evaluated above
            incidentRepository.insertOpenIncident(agentRollupId, alertCondition,
                    alertConfig.getSeverity(), alertConfig.getNotification(), endTime);
            sendMetricAlert(centralDisplay, agentRollupId, agentRollupDisplay, alertConfig,
                    metricCondition, endTime, false);
        }
    }

    private void sendMetricAlert(String centralDisplay, String agentRollupId,
            String agentRollupDisplay, AlertConfig alertConfig, MetricCondition metricCondition,
            long endTime, boolean ok) throws Exception {
        // subject is the same between initial and ok messages so they will be threaded by gmail
        StringBuilder subject = new StringBuilder();
        String metric = metricCondition.getMetric();
        String transactionType = metricCondition.getTransactionType();
        boolean needsSubjectSeparator = false;
        if (!transactionType.isEmpty()) {
            subject.append(transactionType);
            needsSubjectSeparator = true;
        }
        String transactionName = metricCondition.getTransactionName();
        if (!transactionName.isEmpty()) {
            if (needsSubjectSeparator) {
                subject.append(" - ");
            }
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
        sendNotification(centralDisplay, agentRollupId, agentRollupDisplay, alertConfig, endTime,
                subject.toString(), message.toString(), ok);
    }

    public void sendNotification(String centralDisplay, String agentRollupId,
            String agentRollupDisplay, AlertConfig alertConfig, long endTime, String subject,
            String messageText, boolean ok) throws Exception {
        AlertNotification alertNotification = alertConfig.getNotification();
        if (alertNotification.hasEmailNotification()) {
            SmtpConfig smtpConfig = configRepository.getSmtpConfig();
            if (smtpConfig.host().isEmpty()) {
                if (smtpHostWarningRateLimiter.tryAcquire()) {
                    logger.warn("not sending alert due to missing SMTP host configuration"
                            + " (this warning will be logged at most once an hour)");
                }
            } else {
                sendEmail(centralDisplay, agentRollupDisplay, subject,
                        alertNotification.getEmailNotification().getEmailAddressList(),
                        messageText, smtpConfig, null, configRepository.getLazySecretKey(),
                        mailService);
            }
        }
        if (alertNotification.hasPagerDutyNotification()) {
            sendPagerDutyWithRetry(agentRollupId, agentRollupDisplay, alertConfig,
                    alertNotification.getPagerDutyNotification(), endTime, subject, messageText,
                    ok);
        }
    }

    private void sendPagerDutyWithRetry(String agentRollupId, String agentRollupDisplay,
            AlertConfig alertConfig, PagerDutyNotification pagerDutyNotification,
            long endTime, String subject, String messageText, boolean ok) {
        SendPagerDuty sendPagerDuty = new SendPagerDuty(agentRollupId, agentRollupDisplay,
                alertConfig, pagerDutyNotification, endTime, subject, messageText, ok);
        sendPagerDuty.run();
    }

    // optional passwordOverride can be passed in to test SMTP from
    // AdminJsonService.sentTestEmail() without possibility of throwing
    // org.glowroot.common.repo.util.LazySecretKey.SymmetricEncryptionKeyMissingException
    public static void sendEmail(String centralDisplay, String agentRollupDisplay, String subject,
            List<String> emailAddresses, String messageText, SmtpConfig smtpConfig,
            @Nullable String passwordOverride, LazySecretKey lazySecretKey, MailService mailService)
            throws Exception {
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
        String subj = subject;
        if (!agentRollupDisplay.isEmpty()) {
            subj = "[" + agentRollupDisplay + "] " + subj;
        }
        if (!centralDisplay.isEmpty()) {
            subj = "[" + centralDisplay + "] " + subj;
        }
        if (agentRollupDisplay.isEmpty() && centralDisplay.isEmpty()) {
            subj = "[Glowroot] " + subj;
        }
        message.setSubject(subj);
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
        ConnectionSecurity connectionSecurity = smtpConfig.connectionSecurity();
        Integer port = smtpConfig.port();
        if (port == null) {
            port = 25;
        }
        props.put("mail.smtp.port", port);
        if (connectionSecurity == ConnectionSecurity.SSL_TLS) {
            props.put("mail.smtp.socketFactory.port", port);
            props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
        } else if (connectionSecurity == ConnectionSecurity.STARTTLS) {
            props.put("mail.smtp.starttls.enable", true);
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

    private class SendPagerDuty implements Runnable {

        private final String agentRollupId;
        private final String agentRollupDisplay;
        private final AlertConfig alertConfig;
        private final PagerDutyNotification pagerDutyNotification;
        private final long endTime;
        private final String subject;
        private final String messageText;
        private final boolean ok;

        private volatile int currentTryCount = 1;

        private SendPagerDuty(String agentRollupId, String agentRollupDisplay,
                AlertConfig alertConfig, PagerDutyNotification pagerDutyNotification,
                long endTime, String subject, String messageText, boolean ok) {
            this.agentRollupId = agentRollupId;
            this.agentRollupDisplay = agentRollupDisplay;
            this.alertConfig = alertConfig;
            this.pagerDutyNotification = pagerDutyNotification;
            this.endTime = endTime;
            this.subject = subject;
            this.messageText = messageText;
            this.ok = ok;
        }

        @Override
        public void run() {
            try {
                sendPagerDuty(agentRollupId, agentRollupDisplay, alertConfig,
                        pagerDutyNotification, endTime, subject, messageText, ok);
                if (currentTryCount > 1) {
                    logger.info(
                            "{} - PagerDuty no longer responded with 429 Too many requests (rate"
                                    + " limit exceeded), successfully {} incident \"{} / {}\"",
                            agentRollupDisplay, ok ? "resolved" : "triggered", subject,
                            messageText);
                }
            } catch (TooManyRequestsHttpResponseException e) {
                logger.debug(e.getMessage(), e);
                if (currentTryCount == 1) {
                    logger.warn(
                            "{} - PagerDuty responded with 429 Too many requests (rate limit"
                                    + " exceeded), unable to {} incident \"{} / {}\" (will keep"
                                    + " trying...)",
                            agentRollupDisplay, ok ? "resolve" : "trigger", subject, messageText);
                }
                if (currentTryCount < 10) {
                    currentTryCount++;
                    if (!closed) {
                        pagerDutyRetryExecutor.schedule(this, 1, MINUTES);
                    }
                } else {
                    String action = ok ? "resolve" : "trigger";
                    logger.warn(
                            "{} - PagerDuty responded with 429 Too many requests (rate limit"
                                    + " exceeded), unable to {} incident \"{} / {}\" (will stop"
                                    + " trying to {} this incident)",
                            agentRollupDisplay, action, subject, messageText, action);
                }
            } catch (Throwable t) {
                logger.error(t.getMessage(), t);
            }
        }

        private void sendPagerDuty(String agentRollupId, String agentRollupDisplay,
                AlertConfig alertConfig, PagerDutyNotification pagerDutyNotification, long endTime,
                String subject, String messageText, boolean ok) throws Exception {
            AlertCondition alertCondition = alertConfig.getCondition();
            String dedupKey = getPagerDutyDedupKey(agentRollupId, alertCondition);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            JsonGenerator jg = ObjectMappers.create().getFactory().createGenerator(baos);
            try {
                jg.writeStartObject();
                jg.writeStringField("routing_key",
                        pagerDutyNotification.getPagerDutyIntegrationKey());
                jg.writeStringField("dedup_key", dedupKey);
                if (ok) {
                    jg.writeStringField("event_action", "resolve");
                } else {
                    jg.writeStringField("event_action", "trigger");
                    jg.writeStringField("client", "Glowroot");
                    jg.writeObjectFieldStart("payload");
                    jg.writeStringField("summary", subject + "\n\n" + messageText);
                    if (agentRollupId.isEmpty()) {
                        jg.writeStringField("source", InetAddress.getLocalHost().getHostName());
                    } else {
                        jg.writeStringField("source", agentRollupDisplay);
                    }
                    jg.writeStringField("severity",
                            getPagerDutySeverity(alertConfig.getSeverity()));
                    jg.writeStringField("timestamp", formatAsIso8601(endTime));
                    switch (alertCondition.getValCase()) {
                        case METRIC_CONDITION:
                            jg.writeStringField("class",
                                    "metric: " + alertCondition.getMetricCondition().getMetric());
                            break;
                        case SYNTHETIC_MONITOR_CONDITION:
                            jg.writeStringField("class", "synthetic monitor");
                            break;
                        case HEARTBEAT_CONDITION:
                            jg.writeStringField("class", "heartbeat");
                            break;
                        default:
                            logger.warn("unexpected alert condition: "
                                    + alertCondition.getValCase().name());
                            jg.writeStringField("class",
                                    "unknown: " + alertCondition.getValCase().name());
                            break;
                    }
                    jg.writeEndObject();
                }
                jg.writeEndObject();
            } finally {
                jg.close();
            }
            httpClient.post("https://events.pagerduty.com/v2/enqueue", baos.toByteArray(),
                    "application/json");
        }

        private String getPagerDutyDedupKey(String agentRollupId, AlertCondition alertCondition)
                throws UnknownHostException {
            String dedupKey;
            if (agentRollupId.isEmpty()) {
                dedupKey = InetAddress.getLocalHost().getHostName();
            } else {
                dedupKey = agentRollupId;
            }
            dedupKey = escapeDedupKeyPart(dedupKey) + ":" + Versions.getVersion(alertCondition);
            return dedupKey;
        }

        private String getPagerDutySeverity(AlertSeverity severity) {
            switch (severity) {
                case CRITICAL:
                    return "critical";
                case HIGH:
                    return "error";
                case MEDIUM:
                    return "warning";
                case LOW:
                    return "info";
                default:
                    throw new IllegalStateException("Unknown alert severity: " + severity);
            }
        }

        private String formatAsIso8601(long endTime) {
            Calendar end = Calendar.getInstance();
            end.setTimeInMillis(endTime);
            return DatatypeConverter.printDateTime(end);
        }

        private String escapeDedupKeyPart(String agentRollupId) {
            return agentRollupId.replace("\\", "\\\\").replace(":", "\\:");
        }
    }
}
