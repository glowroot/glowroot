/*
 * Copyright 2011-2018 the original author or authors.
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
package org.glowroot.common.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;

import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.Proto.OptionalInt32;

@Value.Immutable
public abstract class TransactionConfig {

    // 0 means mark all transactions as slow
    @Value.Default
    public int slowThresholdMillis() {
        return 2000;
    }

    // 0 means profiling disabled
    @Value.Default
    public int profilingIntervalMillis() {
        return 1000;
    }

    @Value.Default
    // do not use @JsonInclude NON_EMPTY
    // need to always write this value to config.json since default value is true
    public boolean captureThreadStats() {
        return true;
    }

    @JsonInclude(Include.NON_EMPTY)
    public abstract ImmutableList<ImmutableSlowThresholdOverride> slowThresholdOverrides();

    public AgentConfig.TransactionConfig toProto() {
        AgentConfig.TransactionConfig.Builder builder = AgentConfig.TransactionConfig.newBuilder()
                .setSlowThresholdMillis(of(slowThresholdMillis()))
                .setProfilingIntervalMillis(of(profilingIntervalMillis()))
                .setCaptureThreadStats(captureThreadStats());
        for (SlowThresholdOverride slowThresholdOverride : slowThresholdOverrides()) {
            builder.addSlowThresholdOverride(AgentConfig.SlowThresholdOverride.newBuilder()
                    .setTransactionType(slowThresholdOverride.transactionType())
                    .setTransactionName(slowThresholdOverride.transactionName())
                    .setThresholdMillis(slowThresholdOverride.thresholdMillis())
                    .build());
        }
        return builder.build();
    }

    public static ImmutableTransactionConfig create(AgentConfig.TransactionConfig config) {
        ImmutableTransactionConfig.Builder builder = ImmutableTransactionConfig.builder();
        if (config.hasSlowThresholdMillis()) {
            builder.slowThresholdMillis(config.getSlowThresholdMillis().getValue());
        }
        if (config.hasProfilingIntervalMillis()) {
            builder.profilingIntervalMillis(config.getProfilingIntervalMillis().getValue());
        }
        builder.captureThreadStats(config.getCaptureThreadStats());
        for (AgentConfig.SlowThresholdOverride slowThresholdOverride : config
                .getSlowThresholdOverrideList()) {
            builder.addSlowThresholdOverrides(ImmutableSlowThresholdOverride.builder()
                    .transactionType(slowThresholdOverride.getTransactionType())
                    .transactionName(slowThresholdOverride.getTransactionName())
                    .thresholdMillis(slowThresholdOverride.getThresholdMillis())
                    .build());
        }
        return builder.build();
    }

    private static OptionalInt32 of(int value) {
        return OptionalInt32.newBuilder().setValue(value).build();
    }

    @Value.Immutable
    public abstract static class SlowThresholdOverride {

        public abstract String transactionType();

        @Value.Default
        @JsonInclude(Include.NON_EMPTY)
        public String transactionName() {
            return "";
        }

        public abstract int thresholdMillis();
    }
}
