/*
 * Copyright 2014-2023 the original author or authors.
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
package org.glowroot.ui;

import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonSubTypes.Type;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.immutables.value.Value;

import org.glowroot.common.util.Clock;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.Styles;
import org.glowroot.common.util.Versions;
import org.glowroot.common2.config.MoreConfigDefaults;
import org.glowroot.common2.config.PagerDutyConfig.PagerDutyIntegrationKey;
import org.glowroot.common2.config.SlackConfig.SlackWebhook;
import org.glowroot.common2.repo.AlertingDisabledRepository;
import org.glowroot.common2.repo.ConfigRepository;
import org.glowroot.common2.repo.GaugeValueRepository;
import org.glowroot.common2.repo.GaugeValueRepository.Gauge;
import org.glowroot.common2.repo.SyntheticResultRepository;
import org.glowroot.common2.repo.Utils;
import org.glowroot.common2.repo.util.AlertingService;
import org.glowroot.common2.repo.util.Formatting;
import org.glowroot.common2.repo.util.Gauges;
import org.glowroot.ui.AlertConfigJsonService.AlertConfigDto.AlertConditionDto;
import org.glowroot.ui.AlertConfigJsonService.AlertConfigDto.MetricConditionDto;
import org.glowroot.ui.GaugeValueJsonService.GaugeOrdering;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition.HeartbeatCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition.MetricCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition.SyntheticMonitorCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertNotification;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertSeverity;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.SyntheticMonitorConfig;
import org.glowroot.wire.api.model.Proto.OptionalDouble;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.DAYS;

@JsonService
class AlertConfigJsonService {

    private static final ObjectMapper mapper = ObjectMappers.create();

    @VisibleForTesting
    static final Ordering<AlertListItem> orderingByName = new Ordering<AlertListItem>() {
        @Override
        public int compare(AlertListItem left, AlertListItem right) {
            return left.display().compareToIgnoreCase(right.display());
        }
    };

    private final ConfigRepository configRepository;
    private final AlertingDisabledRepository alertingDisableRepository;
    private final GaugeValueRepository gaugeValueRepository;
    private final @Nullable SyntheticResultRepository syntheticResultRepository;
    private final Clock clock;
    private final boolean central;

    AlertConfigJsonService(ConfigRepository configRepository,
            AlertingDisabledRepository alertingDisableRepository,
            GaugeValueRepository gaugeValueRepository,
            @Nullable SyntheticResultRepository syntheticResultRepository, Clock clock,
            boolean central) {
        this.configRepository = configRepository;
        this.alertingDisableRepository = alertingDisableRepository;
        this.gaugeValueRepository = gaugeValueRepository;
        this.syntheticResultRepository = syntheticResultRepository;
        this.clock = clock;
        this.central = central;
    }

    // central supports alert configs on rollups
    @GET(path = "/backend/config/alerts", permission = "agent:config:view:alert")
    String getAlert(@BindAgentRollupId String agentRollupId,
            @BindRequest AlertConfigRequest request) throws Exception {
        Optional<String> version = request.version();
        if (version.isPresent()) {
            AlertConfig alertConfig = configRepository.getAlertConfig(agentRollupId, version.get());
            if (alertConfig == null) {
                throw new JsonServiceException(HttpResponseStatus.NOT_FOUND);
            }
            return getAlertResponse(agentRollupId, alertConfig);
        } else {
            return getAlertList(agentRollupId);
        }
    }

    // central supports alert configs on rollups
    @GET(path = "/backend/config/alert-dropdowns", permission = "agent:config:view:alert")
    String getAlertDropdowns(@BindAgentRollupId String agentRollupId) throws Exception {
        return getAlertResponse(agentRollupId, null);
    }

    // central supports alert configs on rollups
    @POST(path = "/backend/config/alerts/add", permission = "agent:config:edit:alerts")
    String addAlert(@BindAgentRollupId String agentRollupId, @BindRequest AlertConfigDto configDto)
            throws Exception {
        validate(configDto);
        AlertConfig alertConfig = configDto.toProto();
        configRepository.insertAlertConfig(agentRollupId, alertConfig);
        return getAlertResponse(agentRollupId, alertConfig);
    }

    // central supports alert configs on rollups
    @POST(path = "/backend/config/alerts/update", permission = "agent:config:edit:alerts")
    String updateAlert(@BindAgentRollupId String agentRollupId,
            @BindRequest AlertConfigDto configDto) throws Exception {
        validate(configDto);
        AlertConfig alertConfig = configDto.toProto();
        configRepository.updateAlertConfig(agentRollupId, alertConfig, configDto.version().get());
        return getAlertResponse(agentRollupId, alertConfig);
    }

    // central supports alert configs on rollups
    @POST(path = "/backend/config/alerts/remove", permission = "agent:config:edit:alerts")
    void removeAlert(@BindAgentRollupId String agentRollupId,
            @BindRequest AlertConfigRequest request) throws Exception {
        configRepository.deleteAlertConfig(agentRollupId, request.version().get());
    }

    // central supports alert configs on rollups
    @POST(path = "/backend/config/disable-alerting", permission = "agent:config:edit:alerts")
    String disableAlerting(@BindAgentRollupId String agentRollupId,
            @BindRequest DisableAlertingRequest request) throws Exception {
        alertingDisableRepository.setAlertingDisabledUntilTime(agentRollupId,
                clock.currentTimeMillis() + request.disableForNextMillis());
        return getAlertList(agentRollupId);
    }

    // central supports alert configs on rollups
    @POST(path = "/backend/config/re-enable-alerting", permission = "agent:config:edit:alerts")
    String reEnableAlerting(@BindAgentRollupId String agentRollupId) throws Exception {
        alertingDisableRepository.setAlertingDisabledUntilTime(agentRollupId, null);
        return getAlertList(agentRollupId);
    }

    private String getAlertResponse(String agentRollupId, @Nullable AlertConfig alertConfig)
            throws Exception {
        ImmutableAlertConfigResponse.Builder builder = ImmutableAlertConfigResponse.builder();
        if (alertConfig != null) {
            builder.config(AlertConfigDto.toDto(alertConfig))
                    .heading(getConditionDisplay(agentRollupId, alertConfig.getCondition(),
                            clock.currentTimeMillis(), configRepository,
                            syntheticResultRepository));
        }
        builder.addAllGauges(getGaugeDropdownItems(agentRollupId))
                .addAllSyntheticMonitors(getSyntheticMonitorDropdownItems(agentRollupId))
                .addAllPagerDutyIntegrationKeys(
                        configRepository.getPagerDutyConfig().integrationKeys());
        for (SlackWebhook webhook : configRepository.getSlackConfig().webhooks()) {
            builder.addSlackWebhooks(ImmutableSlackWebhookItem.builder()
                    .id(webhook.id())
                    .display(webhook.display())
                    .build());
        }
        return mapper.writeValueAsString(builder.build());
    }

    private String getAlertList(String agentRollupId) throws Exception {
        List<AlertListItem> alertListItems = Lists.newArrayList();
        List<AlertConfig> alertConfigs = configRepository.getAlertConfigs(agentRollupId);
        for (AlertConfig alertConfig : alertConfigs) {
            alertListItems.add(ImmutableAlertListItem.builder()
                    .version(Versions.getVersion(alertConfig))
                    .display(getConditionDisplay(agentRollupId, alertConfig.getCondition(),
                            clock.currentTimeMillis(), configRepository,
                            syntheticResultRepository))
                    .build());
        }
        alertListItems = orderingByName.immutableSortedCopy(alertListItems);
        ImmutableAlertListResponse.Builder builder = ImmutableAlertListResponse.builder()
                .alerts(alertListItems);
        Long disabledUntilTime =
                alertingDisableRepository.getAlertingDisabledUntilTime(agentRollupId);
        if (disabledUntilTime != null) {
            long disabledForNextMillis = disabledUntilTime - clock.currentTimeMillis();
            if (disabledForNextMillis > 0) {
                builder.disabledForNextMillis(disabledForNextMillis);
            }
        }
        return mapper.writeValueAsString(builder.build());
    }

    private List<Gauge> getGaugeDropdownItems(String agentRollupId)
            throws Exception {
        List<Gauge> gauges = gaugeValueRepository.getRecentlyActiveGauges(agentRollupId);
        return new GaugeOrdering().immutableSortedCopy(gauges);
    }

    private List<SyntheticMonitorItem> getSyntheticMonitorDropdownItems(String agentRollupId)
            throws Exception {
        if (!central) {
            return ImmutableList.of();
        }
        List<SyntheticMonitorItem> items = Lists.newArrayList();
        for (SyntheticMonitorConfig config : configRepository
                .getSyntheticMonitorConfigs(agentRollupId)) {
            items.add(ImmutableSyntheticMonitorItem.of(config.getId(),
                    MoreConfigDefaults.getDisplayOrDefault(config)));
        }
        return items;
    }

    // syntheticResultRepository is null for embedded
    static String getConditionDisplay(String agentRollupId, AlertCondition alertCondition,
            long currentOrResolveTime, ConfigRepository configRepository,
            @Nullable SyntheticResultRepository syntheticResultRepository) throws Exception {
        switch (alertCondition.getValCase()) {
            case METRIC_CONDITION:
                return getConditionDisplay(alertCondition.getMetricCondition());
            case SYNTHETIC_MONITOR_CONDITION:
                checkNotNull(syntheticResultRepository);
                return getConditionDisplay(alertCondition.getSyntheticMonitorCondition(),
                        agentRollupId, currentOrResolveTime, configRepository,
                        syntheticResultRepository);
            case HEARTBEAT_CONDITION:
                return getConditionDisplay(alertCondition.getHeartbeatCondition());
            default:
                throw new IllegalStateException(
                        "Unexpected alert condition: " + alertCondition.getValCase().name());
        }
    }

    private static void validate(AlertConfigDto configDto) {
        AlertConditionDto condition = configDto.condition();
        if (condition instanceof MetricConditionDto) {
            MetricConditionDto metricCondition = (MetricConditionDto) condition;
            String errorMessageFilter = metricCondition.errorMessageFilter();
            if (errorMessageFilter != null && errorMessageFilter.startsWith("/")
                    && errorMessageFilter.endsWith("/")) {
                // this will throw PatternSyntaxException if invalid regex
                Pattern.compile(errorMessageFilter.substring(1, errorMessageFilter.length() - 1));
            }
        }
    }

    private static String getConditionDisplay(MetricCondition metricCondition) {
        StringBuilder sb = new StringBuilder();
        String metric = metricCondition.getMetric();
        String transactionType = metricCondition.getTransactionType();
        if (!transactionType.isEmpty()) {
            sb.append(transactionType);
            sb.append(" - ");
        }
        String transactionName = metricCondition.getTransactionName();
        if (!transactionName.isEmpty()) {
            sb.append(transactionName);
            sb.append(" - ");
        }
        Gauge gauge = null;
        if (metric.startsWith("gauge:")) {
            String gaugeName = metric.substring("gauge:".length());
            gauge = Gauges.getGauge(gaugeName);
            sb.append("Gauge - ");
            sb.append(gauge.display());
            sb.append(" - ");
        }
        if (metric.equals("transaction:x-percentile")) {
            checkState(metricCondition.hasPercentile());
            sb.append(
                    Utils.getPercentileWithSuffix(metricCondition.getPercentile().getValue()));
            sb.append(" percentile");
        } else if (metric.equals("transaction:average")) {
            sb.append("average");
        } else if (metric.equals("transaction:count")) {
            sb.append("transaction count");
        } else if (metric.equals("error:rate")) {
            sb.append("error rate");
        } else if (metric.equals("error:count")) {
            String errorMessageFilter = metricCondition.getErrorMessageFilter();
            if (errorMessageFilter.isEmpty()) {
                sb.append("error count");
            } else if (errorMessageFilter.startsWith("/") && errorMessageFilter.endsWith("/")) {
                sb.append("error count matching ");
                sb.append(errorMessageFilter);
            } else {
                sb.append("error count containing \"");
                sb.append(errorMessageFilter);
                sb.append("\"");
            }
        } else if (metric.startsWith("gauge:")) {
            sb.append("average");
        } else {
            throw new IllegalStateException("Unexpected metric: " + metric);
        }
        sb.append(
                AlertingService.getOverTheLastMinutesText(metricCondition.getTimePeriodSeconds()));
        if (metricCondition.getLowerBoundThreshold()) {
            if (metric.equals("transaction:count") && metricCondition.getThreshold() == 0) {
                // this is a common alert, deserves a more common naming
                sb.append(" is equal to ");
            } else {
                sb.append(" is less than or equal to ");
            }
        } else {
            sb.append(" is greater than or equal to ");
        }
        if (metric.equals("transaction:x-percentile") || metric.equals("transaction:average")) {
            sb.append(AlertingService.getWithUnit(metricCondition.getThreshold(), "millisecond"));
        } else if (metric.equals("transaction:count")) {
            sb.append(Formatting.displaySixDigitsOfPrecision(metricCondition.getThreshold()));
        } else if (metric.equals("error:rate")) {
            sb.append(Formatting.displaySixDigitsOfPrecision(metricCondition.getThreshold()));
            sb.append(" percent");
        } else if (metric.equals("error:count")) {
            sb.append(Formatting.displaySixDigitsOfPrecision(metricCondition.getThreshold()));
        } else if (metric.startsWith("gauge:")) {
            sb.append(AlertingService.getGaugeThresholdText(metricCondition.getThreshold(),
                    checkNotNull(gauge).unit()));
        } else {
            throw new IllegalStateException("Unexpected metric: " + metric);
        }
        return sb.toString();
    }

    private static String getConditionDisplay(HeartbeatCondition condition) {
        StringBuilder sb = new StringBuilder();
        sb.append("Heartbeat - not received over the last ");
        sb.append(AlertingService.getWithUnit(condition.getTimePeriodSeconds(), "second"));
        return sb.toString();
    }

    private static String getConditionDisplay(SyntheticMonitorCondition condition,
            String agentRollupId, long currentOrResolveTime, ConfigRepository configRepository,
            SyntheticResultRepository syntheticResultRepository) throws Exception {
        SyntheticMonitorConfig syntheticMonitorConfig =
                configRepository.getSyntheticMonitorConfig(agentRollupId,
                        condition.getSyntheticMonitorId());
        String syntheticMonitorDisplay;
        if (syntheticMonitorConfig == null) {
            syntheticMonitorDisplay = syntheticResultRepository
                    .getSyntheticMonitorIds(agentRollupId, currentOrResolveTime,
                            currentOrResolveTime)
                    .get(condition.getSyntheticMonitorId());
            if (syntheticMonitorDisplay == null) {
                // expand the search
                syntheticMonitorDisplay = syntheticResultRepository
                        .getSyntheticMonitorIds(agentRollupId,
                                currentOrResolveTime - DAYS.toMillis(30),
                                currentOrResolveTime + DAYS.toMillis(30))
                        .get(condition.getSyntheticMonitorId());
                if (syntheticMonitorDisplay == null) {
                    syntheticMonitorDisplay = "<NOT FOUND>";
                }
            }
        } else {
            syntheticMonitorDisplay =
                    MoreConfigDefaults.getDisplayOrDefault(syntheticMonitorConfig);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Synthetic monitor - ");
        sb.append(syntheticMonitorDisplay);
        sb.append(" exceeds ");
        sb.append(AlertingService.getWithUnit(condition.getThresholdMillis(), "millisecond"));
        sb.append(" or results in error");
        return sb.toString();
    }

    @Value.Immutable
    interface AlertConfigRequest {
        Optional<String> version();
    }

    @Value.Immutable
    interface AlertListResponse {
        List<AlertListItem> alerts();
        @Nullable
        Long disabledForNextMillis();
    }

    @Value.Immutable
    interface AlertListItem {
        String version();
        String display();
    }

    @Value.Immutable
    interface AlertConfigResponse {
        @Nullable
        AlertConfigDto config();
        @Nullable
        String heading();
        List<Gauge> gauges();
        List<SyntheticMonitorItem> syntheticMonitors();
        List<PagerDutyIntegrationKey> pagerDutyIntegrationKeys();
        List<SlackWebhookItem> slackWebhooks(); // not exposing webhook url itself
    }

    @Value.Immutable
    @Styles.AllParameters
    interface SyntheticMonitorItem {
        String id();
        String display();
    }

    @Value.Immutable
    @Styles.AllParameters
    interface SlackWebhookItem {
        String id();
        String display();
    }

    @Value.Immutable
    interface DisableAlertingRequest {
        long disableForNextMillis();
    }

    @Value.Immutable
    abstract static class AlertConfigDto {

        abstract AlertConditionDto condition();
        abstract AlertSeverity severity();
        abstract @Nullable ImmutableEmailNotificationDto emailNotification();
        abstract @Nullable ImmutablePagerDutyNotificationDto pagerDutyNotification();
        abstract @Nullable ImmutableSlackNotificationDto slackNotification();

        abstract Optional<String> version(); // absent for insert operations

        private static AlertConfigDto toDto(AlertConfig config) {
            ImmutableAlertConfigDto.Builder builder = ImmutableAlertConfigDto.builder()
                    .condition(toDto(config.getCondition()))
                    .severity(config.getSeverity());
            AlertNotification notification = config.getNotification();
            if (notification.hasEmailNotification()) {
                builder.emailNotification(toDto(notification.getEmailNotification()));
            }
            if (notification.hasPagerDutyNotification()) {
                builder.pagerDutyNotification(toDto(notification.getPagerDutyNotification()));
            }
            if (notification.hasSlackNotification()) {
                builder.slackNotification(toDto(notification.getSlackNotification()));
            }
            return builder.version(Optional.of(Versions.getVersion(config)))
                    .build();
        }

        private AlertConfig toProto() {
            AlertConfig.Builder builder = AlertConfig.newBuilder()
                    .setCondition(toProto(condition()))
                    .setSeverity(severity());
            EmailNotificationDto emailNotification = emailNotification();
            if (emailNotification != null) {
                builder.getNotificationBuilder().setEmailNotification(toProto(emailNotification));
            }
            PagerDutyNotificationDto pagerDutyNotification = pagerDutyNotification();
            if (pagerDutyNotification != null) {
                builder.getNotificationBuilder()
                        .setPagerDutyNotification(toProto(pagerDutyNotification));
            }
            SlackNotificationDto slackNotification = slackNotification();
            if (slackNotification != null) {
                builder.getNotificationBuilder()
                        .setSlackNotification(toProto(slackNotification));
            }
            return builder.build();
        }

        private static AlertConditionDto toDto(AlertConfig.AlertCondition alertCondition) {
            switch (alertCondition.getValCase()) {
                case METRIC_CONDITION:
                    return toDto(alertCondition.getMetricCondition());
                case SYNTHETIC_MONITOR_CONDITION:
                    return toDto(alertCondition.getSyntheticMonitorCondition());
                case HEARTBEAT_CONDITION:
                    return toDto(alertCondition.getHeartbeatCondition());
                default:
                    throw new IllegalStateException(
                            "Unexpected condition kind: " + alertCondition.getValCase().name());
            }
        }

        private static ImmutableEmailNotificationDto toDto(
                AlertConfig.AlertNotification.EmailNotification emailNotification) {
            return ImmutableEmailNotificationDto.builder()
                    .addAllEmailAddresses(emailNotification.getEmailAddressList())
                    .build();
        }

        private static ImmutablePagerDutyNotificationDto toDto(
                AlertConfig.AlertNotification.PagerDutyNotification pagerDutyNotification) {
            return ImmutablePagerDutyNotificationDto.builder()
                    .pagerDutyIntegrationKey(pagerDutyNotification.getPagerDutyIntegrationKey())
                    .build();
        }

        private static ImmutableSlackNotificationDto toDto(
                AlertConfig.AlertNotification.SlackNotification slackNotification) {
            return ImmutableSlackNotificationDto.builder()
                    .slackWebhookId(slackNotification.getSlackWebhookId())
                    .addAllSlackChannels(slackNotification.getSlackChannelList())
                    .build();
        }

        private static AlertConfig.AlertCondition toProto(AlertConditionDto condition) {
            if (condition instanceof MetricConditionDto) {
                return AlertConfig.AlertCondition.newBuilder()
                        .setMetricCondition(toProto((MetricConditionDto) condition))
                        .build();
            }
            if (condition instanceof SyntheticMonitorConditionDto) {
                return AlertConfig.AlertCondition.newBuilder()
                        .setSyntheticMonitorCondition(
                                toProto((SyntheticMonitorConditionDto) condition))
                        .build();
            }
            if (condition instanceof HeartbeatConditionDto) {
                return AlertConfig.AlertCondition.newBuilder()
                        .setHeartbeatCondition(toProto((HeartbeatConditionDto) condition))
                        .build();
            }
            throw new IllegalStateException(
                    "Unexpected alert condition type: " + condition.getClass().getName());
        }

        private static AlertConfig.AlertNotification.EmailNotification toProto(
                EmailNotificationDto emailNotification) {
            return AlertConfig.AlertNotification.EmailNotification.newBuilder()
                    .addAllEmailAddress(emailNotification.emailAddresses())
                    .build();
        }

        private static AlertConfig.AlertNotification.PagerDutyNotification toProto(
                PagerDutyNotificationDto pagerDutyNotification) {
            return AlertConfig.AlertNotification.PagerDutyNotification.newBuilder()
                    .setPagerDutyIntegrationKey(pagerDutyNotification.pagerDutyIntegrationKey())
                    .build();
        }

        private static AlertConfig.AlertNotification.SlackNotification toProto(
                SlackNotificationDto slackNotification) {
            return AlertConfig.AlertNotification.SlackNotification.newBuilder()
                    .setSlackWebhookId(slackNotification.slackWebhookId())
                    .addAllSlackChannel(slackNotification.slackChannels())
                    .build();
        }

        private static MetricConditionDto toDto(
                AlertConfig.AlertCondition.MetricCondition condition) {
            ImmutableMetricConditionDto.Builder builder = ImmutableMetricConditionDto.builder()
                    .metric(condition.getMetric());
            if (AlertingService.hasTransactionTypeAndName(condition.getMetric())) {
                builder.transactionType(condition.getTransactionType());
                builder.transactionName(condition.getTransactionName());
            }
            if (condition.hasPercentile()) {
                builder.percentile(condition.getPercentile().getValue());
            }
            if (AlertingService.hasErrorMessageFilter(condition.getMetric())) {
                builder.errorMessageFilter(condition.getErrorMessageFilter());
            }
            if (AlertingService.hasMinTransactionCount(condition.getMetric())) {
                builder.minTransactionCount(condition.getMinTransactionCount());
            }
            return builder.threshold(condition.getThreshold())
                    .lowerBoundThreshold(condition.getLowerBoundThreshold())
                    .timePeriodSeconds(condition.getTimePeriodSeconds())
                    .build();
        }

        private static SyntheticMonitorConditionDto toDto(
                AlertConfig.AlertCondition.SyntheticMonitorCondition condition) {
            ImmutableSyntheticMonitorConditionDto.Builder builder =
                    ImmutableSyntheticMonitorConditionDto.builder()
                            .syntheticMonitorId(condition.getSyntheticMonitorId())
                            .thresholdMillis(condition.getThresholdMillis());
            int consecutiveCount = condition.getConsecutiveCount();
            if (consecutiveCount == 0) {
                // this is needed to support agent configs that were saved prior to 0.13.0
                builder.consecutiveCount(1);
            } else {
                builder.consecutiveCount(consecutiveCount);
            }
            return builder.build();
        }

        private static HeartbeatConditionDto toDto(
                AlertConfig.AlertCondition.HeartbeatCondition condition) {
            return ImmutableHeartbeatConditionDto.builder()
                    .timePeriodSeconds(condition.getTimePeriodSeconds())
                    .build();
        }

        private static AlertConfig.AlertCondition.MetricCondition toProto(
                MetricConditionDto condition) {
            AlertConfig.AlertCondition.MetricCondition.Builder builder =
                    AlertConfig.AlertCondition.MetricCondition.newBuilder()
                            .setMetric(condition.metric());
            if (AlertingService.hasTransactionTypeAndName(condition.metric())) {
                builder.setTransactionType(checkNotNull(condition.transactionType()));
                builder.setTransactionName(checkNotNull(condition.transactionName()));
            }
            Double percentile = condition.percentile();
            if (percentile != null) {
                builder.setPercentile(OptionalDouble.newBuilder().setValue(percentile));
            }
            if (AlertingService.hasErrorMessageFilter(condition.metric())) {
                builder.setErrorMessageFilter(checkNotNull(condition.errorMessageFilter()));
            }
            if (AlertingService.hasMinTransactionCount(condition.metric())) {
                builder.setMinTransactionCount(checkNotNull(condition.minTransactionCount()));
            }
            return builder.setThreshold(condition.threshold())
                    .setLowerBoundThreshold(condition.lowerBoundThreshold())
                    .setTimePeriodSeconds(condition.timePeriodSeconds())
                    .build();
        }

        private static AlertConfig.AlertCondition.SyntheticMonitorCondition toProto(
                SyntheticMonitorConditionDto condition) {
            return AlertConfig.AlertCondition.SyntheticMonitorCondition.newBuilder()
                    .setSyntheticMonitorId(condition.syntheticMonitorId())
                    .setThresholdMillis(condition.thresholdMillis())
                    .setConsecutiveCount(condition.consecutiveCount())
                    .build();
        }

        private static AlertConfig.AlertCondition.HeartbeatCondition toProto(
                HeartbeatConditionDto condition) {
            return AlertConfig.AlertCondition.HeartbeatCondition.newBuilder()
                    .setTimePeriodSeconds(condition.timePeriodSeconds())
                    .build();
        }

        @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY,
                property = "conditionType")
        @JsonSubTypes({@Type(value = ImmutableMetricConditionDto.class, name = "metric"),
                @Type(value = ImmutableSyntheticMonitorConditionDto.class,
                        name = "synthetic-monitor"),
                @Type(value = ImmutableHeartbeatConditionDto.class, name = "heartbeat")})
        public interface AlertConditionDto {}

        @Value.Immutable
        public abstract static class MetricConditionDto implements AlertConditionDto {
            abstract String metric();
            abstract @Nullable String transactionType();
            abstract @Nullable String transactionName();
            abstract @Nullable Double percentile();
            abstract @Nullable String errorMessageFilter();
            abstract double threshold();
            @Value.Default
            @JsonInclude(value = Include.NON_EMPTY)
            boolean lowerBoundThreshold() {
                return false;
            }
            abstract int timePeriodSeconds();
            abstract @Nullable Long minTransactionCount();
        }

        @Value.Immutable
        public interface SyntheticMonitorConditionDto extends AlertConditionDto {
            String syntheticMonitorId();
            int thresholdMillis();
            int consecutiveCount();
        }

        @Value.Immutable
        public interface HeartbeatConditionDto extends AlertConditionDto {
            int timePeriodSeconds();
        }

        @Value.Immutable
        public interface EmailNotificationDto {
            List<String> emailAddresses();
        }

        @Value.Immutable
        public interface PagerDutyNotificationDto {
            String pagerDutyIntegrationKey();
        }

        @Value.Immutable
        public interface SlackNotificationDto {
            String slackWebhookId();
            List<String> slackChannels();
        }
    }
}
