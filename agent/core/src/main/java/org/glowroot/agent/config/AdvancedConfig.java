/*
 * Copyright 2013-2018 the original author or authors.
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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import org.immutables.value.Value;

import org.glowroot.common.ConfigDefaults;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.Proto.OptionalInt32;

@Value.Immutable
public abstract class AdvancedConfig {

    public static final int OVERALL_AGGREGATE_QUERIES_HARD_LIMIT_MULTIPLIER = 10;
    public static final int TRANSACTION_AGGREGATE_QUERIES_HARD_LIMIT_MULTIPLIER = 2;

    public static final int OVERALL_AGGREGATE_SERVICE_CALLS_HARD_LIMIT_MULTIPLIER = 10;
    public static final int TRANSACTION_AGGREGATE_SERVICE_CALLS_HARD_LIMIT_MULTIPLIER = 2;

    @Value.Default
    public int immediatePartialStoreThresholdSeconds() {
        return 60;
    }

    // used to limit memory requirement
    @Value.Default
    public int maxTransactionAggregates() {
        return ConfigDefaults.ADVANCED_MAX_TRANSACTION_AGGREGATES;
    }

    // used to limit memory requirement
    // applied to individual traces, transaction aggregates and overall aggregates
    @Value.Default
    public int maxQueryAggregates() {
        return ConfigDefaults.ADVANCED_MAX_QUERY_AGGREGATES;
    }

    // used to limit memory requirement
    // applied to individual traces, transaction aggregates and overall aggregates
    @Value.Default
    public int maxServiceCallAggregates() {
        return ConfigDefaults.ADVANCED_MAX_QUERY_AGGREGATES;
    }

    // used to limit memory requirement, also used to help limit trace capture size
    @Value.Default
    public int maxTraceEntriesPerTransaction() {
        return 2000;
    }

    // used to limit memory requirement, also used to help limit trace capture size
    @Value.Default
    public int maxProfileSamplesPerTransaction() {
        return 50000;
    }

    @Value.Default
    public int mbeanGaugeNotFoundDelaySeconds() {
        return 60;
    }

    @Value.Default
    @JsonInclude(Include.NON_EMPTY)
    public boolean weavingTimer() {
        return false;
    }

    public AgentConfig.AdvancedConfig toProto() {
        return AgentConfig.AdvancedConfig.newBuilder()
                .setImmediatePartialStoreThresholdSeconds(
                        of(immediatePartialStoreThresholdSeconds()))
                .setMaxTransactionAggregates(of(maxTransactionAggregates()))
                .setMaxQueryAggregates(of(maxQueryAggregates()))
                .setMaxServiceCallAggregates(of(maxServiceCallAggregates()))
                .setMaxTraceEntriesPerTransaction(of(maxTraceEntriesPerTransaction()))
                .setMaxProfileSamplesPerTransaction(of(maxProfileSamplesPerTransaction()))
                .setMbeanGaugeNotFoundDelaySeconds(of(mbeanGaugeNotFoundDelaySeconds()))
                .setWeavingTimer(weavingTimer())
                .build();
    }

    public static AdvancedConfig create(AgentConfig.AdvancedConfig config) {
        ImmutableAdvancedConfig.Builder builder = ImmutableAdvancedConfig.builder();
        if (config.hasImmediatePartialStoreThresholdSeconds()) {
            builder.immediatePartialStoreThresholdSeconds(
                    config.getImmediatePartialStoreThresholdSeconds().getValue());
        }
        if (config.hasMaxTransactionAggregates()) {
            builder.maxTransactionAggregates(config.getMaxTransactionAggregates().getValue());
        }
        if (config.hasMaxQueryAggregates()) {
            builder.maxQueryAggregates(config.getMaxQueryAggregates().getValue());
        }
        if (config.hasMaxServiceCallAggregates()) {
            builder.maxServiceCallAggregates(config.getMaxServiceCallAggregates().getValue());
        }
        if (config.hasMaxTraceEntriesPerTransaction()) {
            builder.maxTraceEntriesPerTransaction(
                    config.getMaxTraceEntriesPerTransaction().getValue());
        }
        if (config.hasMaxProfileSamplesPerTransaction()) {
            builder.maxProfileSamplesPerTransaction(
                    config.getMaxProfileSamplesPerTransaction().getValue());
        }
        if (config.hasMbeanGaugeNotFoundDelaySeconds()) {
            builder.mbeanGaugeNotFoundDelaySeconds(
                    config.getMbeanGaugeNotFoundDelaySeconds().getValue());
        }
        return builder.weavingTimer(config.getWeavingTimer())
                .build();
    }

    private static OptionalInt32 of(int value) {
        return OptionalInt32.newBuilder().setValue(value).build();
    }
}
