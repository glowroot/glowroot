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
package org.glowroot.agent.config;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonSubTypes.Type;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;

import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertNotification;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertSeverity;
import org.glowroot.wire.api.model.Proto.OptionalDouble;

@Value.Immutable
public abstract class AlertConfig {

    public abstract AlertCondition condition();
    public abstract AlertSeverity severity();
    public abstract @Nullable ImmutableEmailNotification emailNotification();
    public abstract @Nullable ImmutablePagerDutyNotification pagerDutyNotification();

    public static AlertConfig create(AgentConfig.AlertConfig config) {
        ImmutableAlertConfig.Builder builder = ImmutableAlertConfig.builder()
                .condition(create(config.getCondition()))
                .severity(config.getSeverity());
        AlertNotification notification = config.getNotification();
        if (notification.hasEmailNotification()) {
            builder.emailNotification(create(notification.getEmailNotification()));
        }
        if (notification.hasPagerDutyNotification()) {
            builder.pagerDutyNotification(create(notification.getPagerDutyNotification()));
        }
        return builder.build();
    }

    public AgentConfig.AlertConfig toProto() {
        AgentConfig.AlertConfig.Builder builder = AgentConfig.AlertConfig.newBuilder()
                .setCondition(toProto(condition()))
                .setSeverity(severity());
        EmailNotification emailNotification = emailNotification();
        if (emailNotification != null) {
            builder.getNotificationBuilder().setEmailNotification(toProto(emailNotification));
        }
        PagerDutyNotification pagerDutyNotification = pagerDutyNotification();
        if (pagerDutyNotification != null) {
            builder.getNotificationBuilder()
                    .setPagerDutyNotification(toProto(pagerDutyNotification));
        }
        return builder.build();
    }

    private static AlertCondition create(AgentConfig.AlertConfig.AlertCondition alertCondition) {
        switch (alertCondition.getValCase()) {
            case METRIC_CONDITION:
                return create(alertCondition.getMetricCondition());
            case SYNTHETIC_MONITOR_CONDITION:
                return create(alertCondition.getSyntheticMonitorCondition());
            case HEARTBEAT_CONDITION:
                return create(alertCondition.getHeartbeatCondition());
            default:
                throw new IllegalStateException(
                        "Unexpected condition kind: " + alertCondition.getValCase().name());
        }
    }

    private static ImmutableEmailNotification create(
            AgentConfig.AlertConfig.AlertNotification.EmailNotification emailNotification) {
        return ImmutableEmailNotification.builder()
                .addAllEmailAddresses(emailNotification.getEmailAddressList())
                .build();
    }

    private static ImmutablePagerDutyNotification create(
            AgentConfig.AlertConfig.AlertNotification.PagerDutyNotification pagerDutyNotification) {
        return ImmutablePagerDutyNotification.builder()
                .pagerDutyIntegrationKey(pagerDutyNotification.getPagerDutyIntegrationKey())
                .build();
    }

    private static AgentConfig.AlertConfig.AlertCondition toProto(AlertCondition condition) {
        if (condition instanceof MetricCondition) {
            return AgentConfig.AlertConfig.AlertCondition.newBuilder()
                    .setMetricCondition(toProto((MetricCondition) condition))
                    .build();
        }
        if (condition instanceof SyntheticMonitorCondition) {
            return AgentConfig.AlertConfig.AlertCondition.newBuilder()
                    .setSyntheticMonitorCondition(toProto((SyntheticMonitorCondition) condition))
                    .build();
        }
        if (condition instanceof HeartbeatCondition) {
            return AgentConfig.AlertConfig.AlertCondition.newBuilder()
                    .setHeartbeatCondition(toProto((HeartbeatCondition) condition))
                    .build();
        }
        throw new IllegalStateException(
                "Unexpected alert condition type: " + condition.getClass().getName());
    }

    private static AgentConfig.AlertConfig.AlertNotification.EmailNotification toProto(
            EmailNotification emailNotification) {
        return AgentConfig.AlertConfig.AlertNotification.EmailNotification.newBuilder()
                .addAllEmailAddress(emailNotification.emailAddresses())
                .build();
    }

    private static AgentConfig.AlertConfig.AlertNotification.PagerDutyNotification toProto(
            PagerDutyNotification pagerDutyNotification) {
        return AgentConfig.AlertConfig.AlertNotification.PagerDutyNotification.newBuilder()
                .setPagerDutyIntegrationKey(pagerDutyNotification.pagerDutyIntegrationKey())
                .build();
    }

    private static MetricCondition create(
            AgentConfig.AlertConfig.AlertCondition.MetricCondition condition) {
        ImmutableMetricCondition.Builder builder = ImmutableMetricCondition.builder()
                .metric(condition.getMetric());
        String transactionType = condition.getTransactionType();
        if (transactionType != null) {
            builder.transactionType(transactionType);
        }
        String transactionName = condition.getTransactionName();
        if (transactionName != null) {
            builder.transactionName(transactionName);
        }
        if (condition.hasPercentile()) {
            builder.percentile(condition.getPercentile().getValue());
        }
        return builder.threshold(condition.getThreshold())
                .lowerBoundThreshold(condition.getLowerBoundThreshold())
                .timePeriodSeconds(condition.getTimePeriodSeconds())
                .minTransactionCount(condition.getMinTransactionCount())
                .build();
    }

    private static SyntheticMonitorCondition create(
            AgentConfig.AlertConfig.AlertCondition.SyntheticMonitorCondition condition) {
        return ImmutableSyntheticMonitorCondition.builder()
                .syntheticMonitorId(condition.getSyntheticMonitorId())
                .thresholdMillis(condition.getThresholdMillis())
                .build();
    }

    private static HeartbeatCondition create(
            AgentConfig.AlertConfig.AlertCondition.HeartbeatCondition condition) {
        return ImmutableHeartbeatCondition.builder()
                .timePeriodSeconds(condition.getTimePeriodSeconds())
                .build();
    }

    private static AgentConfig.AlertConfig.AlertCondition.MetricCondition toProto(
            MetricCondition condition) {
        AgentConfig.AlertConfig.AlertCondition.MetricCondition.Builder builder =
                AgentConfig.AlertConfig.AlertCondition.MetricCondition.newBuilder()
                        .setMetric(condition.metric());
        String transactionType = condition.transactionType();
        if (transactionType != null) {
            builder.setTransactionType(transactionType);
        }
        String transactionName = condition.transactionName();
        if (transactionName != null) {
            builder.setTransactionName(transactionName);
        }
        Double percentile = condition.percentile();
        if (percentile != null) {
            builder.setPercentile(OptionalDouble.newBuilder().setValue(percentile));
        }
        return builder.setThreshold(condition.threshold())
                .setLowerBoundThreshold(condition.lowerBoundThreshold())
                .setTimePeriodSeconds(condition.timePeriodSeconds())
                .setMinTransactionCount(condition.minTransactionCount())
                .build();
    }

    private static AgentConfig.AlertConfig.AlertCondition.SyntheticMonitorCondition toProto(
            SyntheticMonitorCondition condition) {
        return AgentConfig.AlertConfig.AlertCondition.SyntheticMonitorCondition.newBuilder()
                .setSyntheticMonitorId(condition.syntheticMonitorId())
                .setThresholdMillis(condition.thresholdMillis())
                .build();
    }

    private static AgentConfig.AlertConfig.AlertCondition.HeartbeatCondition toProto(
            HeartbeatCondition condition) {
        return AgentConfig.AlertConfig.AlertCondition.HeartbeatCondition.newBuilder()
                .setTimePeriodSeconds(condition.timePeriodSeconds())
                .build();
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY,
            property = "conditionType")
    @JsonSubTypes({
            @Type(value = ImmutableMetricCondition.class, name = "metric"),
            @Type(value = ImmutableSyntheticMonitorCondition.class, name = "synthetic-monitor"),
            @Type(value = ImmutableHeartbeatCondition.class, name = "heartbeat")})
    interface AlertCondition {}

    @Value.Immutable
    abstract static class MetricCondition implements AlertCondition {
        abstract String metric();
        abstract @Nullable String transactionType();
        abstract @Nullable String transactionName();
        abstract @Nullable Double percentile();
        abstract double threshold();
        @Value.Default
        @JsonInclude(value = Include.NON_EMPTY)
        boolean lowerBoundThreshold() {
            return false;
        }
        abstract int timePeriodSeconds();
        @Value.Default
        @JsonInclude(value = Include.NON_EMPTY)
        long minTransactionCount() {
            return 0;
        }
    }

    @Value.Immutable
    interface SyntheticMonitorCondition extends AlertCondition {
        String syntheticMonitorId();
        int thresholdMillis();
    }

    @Value.Immutable
    interface HeartbeatCondition extends AlertCondition {
        int timePeriodSeconds();
    }

    @Value.Immutable
    interface EmailNotification {
        ImmutableList<String> emailAddresses();
    }

    @Value.Immutable
    interface PagerDutyNotification {
        String pagerDutyIntegrationKey();
    }
}
