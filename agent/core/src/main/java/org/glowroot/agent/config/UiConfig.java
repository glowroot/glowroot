/*
 * Copyright 2016-2017 the original author or authors.
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

import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;

import org.glowroot.common.config.ConfigDefaults;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;

@Value.Immutable
public abstract class UiConfig {

    @Value.Default
    public String defaultDisplayedTransactionType() {
        return ConfigDefaults.DEFAULT_DISPLAYED_TRANSACTION_TYPE;
    }

    @Value.Default
    public ImmutableList<Double> defaultDisplayedPercentiles() {
        return ImmutableList.of(ConfigDefaults.DEFAULT_DISPLAYED_PERCENTILE_1,
                ConfigDefaults.DEFAULT_DISPLAYED_PERCENTILE_2,
                ConfigDefaults.DEFAULT_DISPLAYED_PERCENTILE_3);
    }

    public AgentConfig.UiConfig toProto() {
        return AgentConfig.UiConfig.newBuilder()
                .setDefaultDisplayedTransactionType(defaultDisplayedTransactionType())
                .addAllDefaultDisplayedPercentile(defaultDisplayedPercentiles())
                .build();
    }

    public static UiConfig create(AgentConfig.UiConfig config) {
        return ImmutableUiConfig.builder()
                .defaultDisplayedTransactionType(config.getDefaultDisplayedTransactionType())
                .defaultDisplayedPercentiles(
                        ImmutableList.copyOf(config.getDefaultDisplayedPercentileList()))
                .build();
    }
}
