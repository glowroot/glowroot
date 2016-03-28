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

    // transaction alert
    public abstract @Nullable String transactionType();
    public abstract @Nullable Double transactionPercentile();
    public abstract @Nullable Integer transactionThresholdMillis();
    public abstract @Nullable Integer minTransactionCount();

    // gauge alert
    @JsonInclude(value = Include.NON_EMPTY)
    public abstract @Nullable String gaugeName();
    public abstract @Nullable Double gaugeThreshold();

    // both
    public abstract int timePeriodSeconds();

    public abstract ImmutableList<String> emailAddresses();

    public static AlertConfig create(AgentConfig.AlertConfig config) {
        ImmutableAlertConfig.Builder builder = ImmutableAlertConfig.builder()
                .kind(config.getKind());
        String transactionType = config.getTransactionType();
        if (!transactionType.isEmpty()) {
            builder.transactionType(transactionType);
        }
        if (config.hasTransactionPercentile()) {
            builder.transactionPercentile(config.getTransactionPercentile().getValue());
        }
        if (config.hasTransactionThresholdMillis()) {
            builder.transactionThresholdMillis(config.getTransactionThresholdMillis().getValue());
        }
        if (config.hasMinTransactionCount()) {
            builder.minTransactionCount(config.getMinTransactionCount().getValue());
        }
        String gaugeName = config.getGaugeName();
        if (!gaugeName.isEmpty()) {
            builder.gaugeName(gaugeName);
        }
        if (config.hasGaugeThreshold()) {
            builder.gaugeThreshold(config.getGaugeThreshold().getValue());
        }
        return builder.timePeriodSeconds(config.getTimePeriodSeconds())
                .addAllEmailAddresses(config.getEmailAddressList())
                .build();
    }

    public AgentConfig.AlertConfig toProto() {
        AgentConfig.AlertConfig.Builder builder = AgentConfig.AlertConfig.newBuilder()
                .setKind(kind());
        String transactionType = transactionType();
        if (transactionType != null) {
            builder.setTransactionType(transactionType);
        }
        Double transactionPercentile = transactionPercentile();
        if (transactionPercentile != null) {
            builder.setTransactionPercentile(OptionalDouble.newBuilder()
                    .setValue(transactionPercentile));
        }
        Integer transactionThresholdMillis = transactionThresholdMillis();
        if (transactionThresholdMillis != null) {
            builder.setTransactionThresholdMillis(OptionalInt32.newBuilder()
                    .setValue(transactionThresholdMillis));
        }
        Integer minTransactionCount = minTransactionCount();
        if (minTransactionCount != null) {
            builder.setMinTransactionCount(OptionalInt32.newBuilder()
                    .setValue(minTransactionCount));
        }
        String gaugeName = gaugeName();
        if (gaugeName != null) {
            builder.setGaugeName(gaugeName);
        }
        Double gaugeThreshold = gaugeThreshold();
        if (gaugeThreshold != null) {
            builder.setGaugeThreshold(OptionalDouble.newBuilder()
                    .setValue(gaugeThreshold));
        }
        return builder.setTimePeriodSeconds(timePeriodSeconds())
                .addAllEmailAddress(emailAddresses())
                .build();
    }
}
