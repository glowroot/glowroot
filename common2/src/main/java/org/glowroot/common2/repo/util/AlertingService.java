/*
 * Copyright 2015-2023 the original author or authors.
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
package org.glowroot.common2.repo.util;

import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.RateLimiter;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.Versions;
import org.glowroot.common2.config.SlackConfig.SlackWebhook;
import org.glowroot.common2.config.SmtpConfig;
import org.glowroot.common2.config.SmtpConfig.ConnectionSecurity;
import org.glowroot.common2.repo.*;
import org.glowroot.common2.repo.ConfigRepository.AgentConfigNotFoundException;
import org.glowroot.common2.repo.GaugeValueRepository.Gauge;
import org.glowroot.common2.repo.IncidentRepository.OpenIncident;
import org.glowroot.common2.repo.util.HttpClient.TooManyRequestsHttpResponseException;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition.MetricCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertNotification;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertNotification.EmailNotification;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertNotification.PagerDutyNotification;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertNotification.SlackNotification;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertSeverity;
import org.immutables.serial.Serial;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

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
    private final LockSet<IncidentKey> openingIncidentLockSet;
    private final LockSet<IncidentKey> resolvingIncidentLockSet;
    private final Clock clock;

    private final MetricService metricService;

    // limit missing smtp host configuration warning to once per hour
    private final RateLimiter smtpHostWarningRateLimiter = RateLimiter.create(1.0 / 3600);

    private final ScheduledExecutorService pagerDutyRetryExecutor;

    private volatile boolean closed;

    public AlertingService(ConfigRepository configRepository, IncidentRepository incidentRepository,
                           AggregateRepository aggregateRepository, GaugeValueRepository gaugeValueRepository,
                           TraceRepository traceRepository, RollupLevelService rollupLevelService,
                           MailService mailService, HttpClient httpClient,
                           LockSet<IncidentKey> openingIncidentLockSet,
                           LockSet<IncidentKey> resolvingIncidentLockSet, Clock clock) {
        this.configRepository = configRepository;
        this.incidentRepository = incidentRepository;
        this.mailService = mailService;
        this.httpClient = httpClient;
        this.openingIncidentLockSet = openingIncidentLockSet;
        this.resolvingIncidentLockSet = resolvingIncidentLockSet;
        this.clock = clock;
        this.metricService = new MetricService(aggregateRepository, gaugeValueRepository,
                traceRepository, rollupLevelService);
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

    public CompletionStage<?> checkForDeletedAlerts(String agentRollupId, CassandraProfile profile) {
        return incidentRepository.readOpenIncidents(agentRollupId, profile).thenCompose(openIncidents -> {
            List<CompletionStage<?>> futures = new ArrayList<>();
            for (OpenIncident openIncident : openIncidents) {
                futures.add(isDeletedAlert(openIncident).thenCompose(isDeletedAlert -> {
                    if (isDeletedAlert) {
                        return incidentRepository.resolveIncident(openIncident, clock.currentTimeMillis(),
                                profile);
                    } else {
                        return CompletableFuture.completedFuture(null);
                    }
                }));
            }
            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        });
    }

    public CompletionStage<?> checkForAllDeletedAlerts(CassandraProfile profile) {
        return incidentRepository.readAllOpenIncidents(profile).thenCompose(openIncidents -> {
            List<CompletionStage<?>> futures = new ArrayList<>(openIncidents.size());
            for (OpenIncident openIncident : openIncidents) {
                futures.add(isDeletedAlert(openIncident).thenCompose(isDeletedAlert -> {
                    if (isDeletedAlert) {
                        return incidentRepository.resolveIncident(openIncident, clock.currentTimeMillis(),
                                profile);
                    } else {
                        return CompletableFuture.completedFuture(null);
                    }
                }));
            }
            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        });
    }

    public CompletionStage<?> checkMetricAlert(String centralDisplay, String agentRollupId,
                                               String agentRollupDisplay, AlertConfig alertConfig, MetricCondition metricCondition,
                                               long endTime, CassandraProfile profile) {
        long startTime = endTime - SECONDS.toMillis(metricCondition.getTimePeriodSeconds());
        return metricService.getMetricValue(agentRollupId, metricCondition, startTime, endTime, profile).thenCompose(value -> {

            if (value == null) {
                // cannot calculate due to no data, e.g. error rate (but not error count, which can be
                // calculated - zero - when no data)
                return CompletableFuture.completedFuture(null);
            }
            boolean currentlyTriggered;
            if (metricCondition.getLowerBoundThreshold()) {
                currentlyTriggered = value.doubleValue() <= metricCondition.getThreshold();
            } else {
                currentlyTriggered = value.doubleValue() >= metricCondition.getThreshold();
            }
            AlertCondition alertCondition = alertConfig.getCondition();
            return incidentRepository.readOpenIncident(agentRollupId,
                    alertCondition, alertConfig.getSeverity(), profile).thenCompose(openIncident -> {
                if (openIncident != null && !currentlyTriggered) {
                    // TODO don't close if no data and no heartbeat?
                    return (CompletionStage<Object>) resolveIncident(centralDisplay, agentRollupId, agentRollupDisplay, alertConfig,
                            metricCondition, endTime, alertCondition, openIncident, profile);
                } else if (openIncident == null && currentlyTriggered) {
                    // don't open if min transaction count is not met
                    if (hasMinTransactionCount(metricCondition.getMetric())) {
                        long minTransactionCount = metricCondition.getMinTransactionCount();
                        if (minTransactionCount != 0) {
                            return (CompletionStage<Object>) metricService.getTransactionCount(agentRollupId,
                                    metricCondition.getTransactionType(),
                                    Strings.emptyToNull(metricCondition.getTransactionName()), startTime,
                                    endTime, profile).thenCompose(transactionCount -> {
                                if (transactionCount < minTransactionCount) {
                                    return (CompletionStage<Object>) CompletableFuture.completedFuture(null);
                                }
                                return (CompletionStage<Object>) openIncident(centralDisplay, agentRollupId, agentRollupDisplay, alertConfig,
                                        metricCondition, endTime, alertCondition, profile);
                            });
                        }
                    }
                    return (CompletionStage<Object>) openIncident(centralDisplay, agentRollupId, agentRollupDisplay, alertConfig,
                            metricCondition, endTime, alertCondition, profile);
                }
                return (CompletionStage<Object>) CompletableFuture.completedFuture(null);
            });
        });
    }

    private CompletionStage<?> openIncident(String centralDisplay, String agentRollupId,
                                            String agentRollupDisplay, AlertConfig alertConfig, MetricCondition metricCondition,
                                            long endTime, AlertCondition alertCondition, CassandraProfile profile) {
        IncidentKey incidentKey = ImmutableIncidentKey.builder()
                .agentRollupId(agentRollupId)
                .condition(alertCondition)
                .severity(alertConfig.getSeverity())
                .build();
        UUID lockToken = openingIncidentLockSet.lock(incidentKey);
        if (lockToken == null) {
            return CompletableFuture.completedFuture(null);
        }
        // the start time for the incident is the end time of the interval evaluated above
        return incidentRepository.insertOpenIncident(agentRollupId, alertCondition,
                alertConfig.getSeverity(), alertConfig.getNotification(), endTime, profile).thenAccept(v -> {
            sendMetricAlert(centralDisplay, agentRollupId, agentRollupDisplay, alertConfig,
                    metricCondition, endTime, false);
        }).whenComplete((v, t) -> {
            openingIncidentLockSet.unlock(incidentKey, lockToken);
        });
    }

    private CompletionStage<?> resolveIncident(String centralDisplay, String agentRollupId,
                                               String agentRollupDisplay, AlertConfig alertConfig, MetricCondition metricCondition,
                                               long endTime, AlertCondition alertCondition, OpenIncident openIncident, CassandraProfile profile) {
        IncidentKey incidentKey = ImmutableIncidentKey.builder()
                .agentRollupId(agentRollupId)
                .condition(alertCondition)
                .severity(alertConfig.getSeverity())
                .build();
        UUID lockToken = resolvingIncidentLockSet.lock(incidentKey);
        if (lockToken == null) {
            return CompletableFuture.completedFuture(null);
        }
        return incidentRepository.resolveIncident(openIncident, endTime, profile).thenAccept(v -> {
            sendMetricAlert(centralDisplay, agentRollupId, agentRollupDisplay, alertConfig,
                    metricCondition, endTime, true);
        }).whenComplete((v, t) -> {
            resolvingIncidentLockSet.unlock(incidentKey, lockToken);
        });
    }

    private CompletionStage<Boolean> isDeletedAlert(OpenIncident openIncident) {
        return getAlertConfigsLeniently(openIncident.agentRollupId()).thenApply(alertConfigs -> {
            for (AlertConfig alertConfig : alertConfigs) {
                if (alertConfig.getCondition().equals(openIncident.condition())
                        && alertConfig.getSeverity() == openIncident.severity()) {
                    return false;
                }
            }
            return true;
        });
    }

    private CompletionStage<List<AlertConfig>> getAlertConfigsLeniently(String agentRollupId) {
        return configRepository.getAlertConfigs(agentRollupId).handle((configs, t) -> {
            if (t != null) {
                if (t instanceof AgentConfigNotFoundException) {
                    // be lenient if agent_config table is messed up
                    logger.debug(t.getMessage(), t);
                    return ImmutableList.of();
                }
                throw new RuntimeException(t);
            }
            return configs;
        });
    }

    private void sendMetricAlert(String centralDisplay, String agentRollupId,
                                 String agentRollupDisplay, AlertConfig alertConfig, MetricCondition metricCondition,
                                 long endTime, boolean ok) {
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
            String errorMessageFilter = metricCondition.getErrorMessageFilter();
            if (errorMessageFilter.isEmpty()) {
                message.append("Error count");
            } else if (errorMessageFilter.startsWith("/") && errorMessageFilter.endsWith("/")) {
                message.append("error count matching ");
                message.append(errorMessageFilter);
            } else {
                message.append("error count containing \"");
                message.append(errorMessageFilter);
                message.append("\"");
            }
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
                                 String messageText, boolean ok) {
        AlertNotification alertNotification = alertConfig.getNotification();
        EmailNotification emailNotification = alertNotification.getEmailNotification();
        if (emailNotification.getEmailAddressCount() > 0) {
            SmtpConfig smtpConfig = configRepository.getSmtpConfig().toCompletableFuture().join();
            if (smtpConfig.host().isEmpty()) {
                if (smtpHostWarningRateLimiter.tryAcquire()) {
                    logger.warn("not sending alert due to missing SMTP host configuration"
                            + " (this warning will be logged at most once an hour)");
                }
            } else {
                sendEmail(centralDisplay, agentRollupDisplay, subject,
                        emailNotification.getEmailAddressList(),
                        messageText, smtpConfig, null, configRepository.getLazySecretKey(),
                        mailService, ok);
            }
        }
        PagerDutyNotification pagerDutyNotification = alertNotification.getPagerDutyNotification();
        if (!pagerDutyNotification.getPagerDutyIntegrationKey().isEmpty()) {
            sendPagerDutyWithRetry(agentRollupId, agentRollupDisplay, alertConfig,
                    pagerDutyNotification, endTime, subject, messageText, ok);
        }
        SlackNotification slackNotification = alertNotification.getSlackNotification();
        if (!slackNotification.getSlackWebhookId().isEmpty()) {
            String slackWebhookUrl = null;
            for (SlackWebhook webhook : configRepository.getSlackConfig().toCompletableFuture().join().webhooks()) {
                if (webhook.id().equals(slackNotification.getSlackWebhookId())) {
                    slackWebhookUrl = webhook.url();
                    break;
                }
            }
            if (slackWebhookUrl == null) {
                logger.warn("{} - alert config refers to non-existent webhook id: {}",
                        agentRollupId, slackNotification.getSlackWebhookId());
            } else {
                for (String slackChannel : slackNotification.getSlackChannelList()) {
                    sendSlackWithRetry(centralDisplay, agentRollupDisplay, slackWebhookUrl,
                            slackChannel, endTime, subject, messageText, ok);
                }
            }
        }
    }

    private void sendPagerDutyWithRetry(String agentRollupId, String agentRollupDisplay,
                                        AlertConfig alertConfig, PagerDutyNotification pagerDutyNotification,
                                        long endTime, String subject, String messageText, boolean ok) {
        SendPagerDuty sendPagerDuty = new SendPagerDuty(agentRollupId, agentRollupDisplay,
                alertConfig, pagerDutyNotification, endTime, subject, messageText, ok);
        sendPagerDuty.run();
    }

    private void sendSlackWithRetry(String centralDisplay, String agentRollupDisplay,
                                    String slackWebhookUrl, String slackChannel, long endTime, String subject,
                                    String messageText, boolean ok) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            JsonGenerator jg = ObjectMappers.create().getFactory().createGenerator(baos);
            try {
                jg.writeStartObject();
                jg.writeArrayFieldStart("attachments");
                jg.writeStartObject();
                if (!agentRollupDisplay.isEmpty()) {
                    subject = "[" + agentRollupDisplay + "] " + subject;
                }
                if (!centralDisplay.isEmpty()) {
                    subject = "[" + centralDisplay + "] " + subject;
                }
                jg.writeStringField("fallback", subject + " - " + messageText);
                jg.writeStringField("pretext", subject);
                jg.writeStringField("color", ok ? "good" : "danger");
                jg.writeStringField("text", messageText);
                jg.writeNumberField("ts", endTime / 1000.0);
                jg.writeEndObject();
                jg.writeEndArray();
                jg.writeStringField("channel", slackChannel);
                jg.writeEndObject();
            } finally {
                jg.close();
            }
            httpClient.post(slackWebhookUrl, baos.toByteArray(), "application/json");
        } catch (Throwable t) {
            logger.error("{} - {}", agentRollupDisplay, t.getMessage(), t);
        }
    }

    // optional passwordOverride can be passed in to test SMTP from
    // AdminJsonService.sentTestEmail() without possibility of throwing
    // org.glowroot.common.repo.util.LazySecretKey.SymmetricEncryptionKeyMissingException
    public static void sendEmail(String centralDisplay, String agentRollupDisplay, String subject,
                                 List<String> emailAddresses, String messageText, SmtpConfig smtpConfig,
                                 @Nullable String passwordOverride, LazySecretKey lazySecretKey, MailService mailService,
                                 boolean ok) {
        try {
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
            if (ok) {
                subj = subj + " - resolved";
            } else {
                subj = subj + " - triggered";
            }
            message.setSubject(subj);
            message.setText(messageText);
            mailService.send(message);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String getPreUpperBoundText(boolean ok) {
        if (ok) {
            return " is no longer greater than or equal to the alert threshold of ";
        } else {
            return " is greater than or equal to the alert threshold of ";
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

    public static boolean hasTransactionTypeAndName(String metric) {
        return metric.startsWith("transaction:") || metric.startsWith("error:");
    }

    public static boolean hasMinTransactionCount(String metric) {
        return hasTransactionTypeAndName(metric) && !metric.equals("transaction:count")
                && !metric.equals("error:count");
    }

    public static boolean hasErrorMessageFilter(String metric) {
        return metric.equals("error:count");
    }

    private static String getPreLowerBoundText(boolean ok) {
        if (ok) {
            return " is no longer less than or equal to the alert threshold of ";
        } else {
            return " is less than or equal to the alert threshold of ";
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
        for (Map.Entry<String, String> entry : smtpConfig.additionalProperties().entrySet()) {
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
        String password = smtpConfig.encryptedPassword();
        if (password.isEmpty()) {
            return "";
        }
        return Encryption.decrypt(password, lazySecretKey);
    }

    @Value.Immutable
    @Serial.Structural
    public interface IncidentKey extends Serializable {
        String agentRollupId();

        AlertCondition condition();

        AlertSeverity severity();
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
                logger.error("{} - {}", agentRollupDisplay, t.getMessage(), t);
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
            // Trailing 'Z' to indicate UTC (and avoid having to format timezone in ISO 8601 format)
            DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'");
            df.setTimeZone(TimeZone.getTimeZone("UTC"));
            return df.format(endTime);
        }

        private String escapeDedupKeyPart(String agentRollupId) {
            return agentRollupId.replace("\\", "\\\\").replace(":", "\\:");
        }
    }
}
