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
import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;

import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertKind;
import org.glowroot.wire.api.model.Proto.OptionalDouble;
import org.glowroot.wire.api.model.Proto.OptionalInt32;

@Value.Immutable
public abstract class AlertConfig {

    public abstract AlertKind kind();

    // === transaction alerts ===

    @Value.Default
    @JsonInclude(value = Include.NON_EMPTY)
    public String transactionType() {
        return "";
    }

    public abstract @Nullable Double transactionPercentile();

    public abstract @Nullable Integer minTransactionCount();

    // === gauge alerts ===

    @Value.Default
    @JsonInclude(value = Include.NON_EMPTY)
    public String gaugeName() {
        return "";
    }

    public abstract @Nullable Double gaugeThreshold();

    // === synthetic monitor alerts ===

    @Value.Default
    @JsonInclude(value = Include.NON_EMPTY)
    public String syntheticMonitorId() {
        return "";
    }

    // === transaction and synthetic monitor alerts ===

    public abstract @Nullable Integer thresholdMillis();

    // === transaction, gauge and heartbeat alerts ===

    public abstract @Nullable Integer timePeriodSeconds();

    // === all alerts ===

    public abstract ImmutableList<String> emailAddresses();

    public static AlertConfig create(AgentConfig.AlertConfig config) {
        ImmutableAlertConfig.Builder builder = ImmutableAlertConfig.builder()
                .kind(config.getKind())
                .transactionType(config.getTransactionType());
        if (config.hasTransactionPercentile()) {
            builder.transactionPercentile(config.getTransactionPercentile().getValue());
        }
        if (config.hasMinTransactionCount()) {
            builder.minTransactionCount(config.getMinTransactionCount().getValue());
        }
        builder.gaugeName(config.getGaugeName());
        if (config.hasGaugeThreshold()) {
            builder.gaugeThreshold(config.getGaugeThreshold().getValue());
        }
        builder.syntheticMonitorId(config.getSyntheticMonitorId());
        if (config.hasThresholdMillis()) {
            builder.thresholdMillis(config.getThresholdMillis().getValue());
        }
        int timePeriodSeconds = config.getTimePeriodSeconds();
        if (timePeriodSeconds != 0) {
            builder.timePeriodSeconds(timePeriodSeconds);
        }
        return builder.addAllEmailAddresses(config.getEmailAddressList())
                .build();
    }

    public AgentConfig.AlertConfig toProto() {
        AgentConfig.AlertConfig.Builder builder = AgentConfig.AlertConfig.newBuilder()
                .setKind(kind())
                .setTransactionType(transactionType());
        Double transactionPercentile = transactionPercentile();
        if (transactionPercentile != null) {
            builder.setTransactionPercentile(OptionalDouble.newBuilder()
                    .setValue(transactionPercentile));
        }
        Integer minTransactionCount = minTransactionCount();
        if (minTransactionCount != null) {
            builder.setMinTransactionCount(OptionalInt32.newBuilder()
                    .setValue(minTransactionCount));
        }
        builder.setGaugeName(gaugeName());
        Double gaugeThreshold = gaugeThreshold();
        if (gaugeThreshold != null) {
            builder.setGaugeThreshold(OptionalDouble.newBuilder()
                    .setValue(gaugeThreshold));
        }
        builder.setSyntheticMonitorId(syntheticMonitorId());
        Integer thresholdMillis = thresholdMillis();
        if (thresholdMillis != null) {
            builder.setThresholdMillis(OptionalInt32.newBuilder().setValue(thresholdMillis));
        }
        Integer timePeriodSeconds = timePeriodSeconds();
        if (timePeriodSeconds != null) {
            builder.setTimePeriodSeconds(timePeriodSeconds);
        }
        return builder.addAllEmailAddress(emailAddresses())
                .build();
    }
}
