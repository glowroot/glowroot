/*
 * Copyright 2011-2016 the original author or authors.
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
    @JsonInclude(value = Include.NON_EMPTY)
    public boolean captureThreadStats() {
        return true;
    }

    public AgentConfig.TransactionConfig toProto() {
        return AgentConfig.TransactionConfig.newBuilder()
                .setSlowThresholdMillis(of(slowThresholdMillis()))
                .setProfilingIntervalMillis(of(profilingIntervalMillis()))
                .setCaptureThreadStats(captureThreadStats())
                .build();
    }

    public static TransactionConfig create(AgentConfig.TransactionConfig config) {
        ImmutableTransactionConfig.Builder builder = ImmutableTransactionConfig.builder();
        if (config.hasSlowThresholdMillis()) {
            builder.slowThresholdMillis(config.getSlowThresholdMillis().getValue());
        }
        if (config.hasProfilingIntervalMillis()) {
            builder.profilingIntervalMillis(config.getProfilingIntervalMillis().getValue());
        }
        return builder.captureThreadStats(config.getCaptureThreadStats())
                .build();
    }

    private static OptionalInt32 of(int value) {
        return OptionalInt32.newBuilder().setValue(value).build();
    }
}
