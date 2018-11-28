/*
 * Copyright 2016-2018 the original author or authors.
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

import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;

import org.glowroot.common.ConfigDefaults;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;

@Value.Immutable
public abstract class UiDefaultsConfig {

    @Value.Default
    public String defaultTransactionType() {
        return ConfigDefaults.UI_DEFAULTS_TRANSACTION_TYPE;
    }

    @Value.Default
    public ImmutableList<Double> defaultPercentiles() {
        return ConfigDefaults.UI_DEFAULTS_PERCENTILES;
    }

    @Value.Default
    public ImmutableList<String> defaultGaugeNames() {
        return ConfigDefaults.UI_DEFAULTS_GAUGE_NAMES;
    }

    public AgentConfig.UiDefaultsConfig toProto() {
        return AgentConfig.UiDefaultsConfig.newBuilder()
                .setDefaultTransactionType(defaultTransactionType())
                .addAllDefaultPercentile(defaultPercentiles())
                .addAllDefaultGaugeName(defaultGaugeNames())
                .build();
    }

    public static ImmutableUiDefaultsConfig create(AgentConfig.UiDefaultsConfig config) {
        return ImmutableUiDefaultsConfig.builder()
                .defaultTransactionType(config.getDefaultTransactionType())
                .defaultPercentiles(config.getDefaultPercentileList())
                .addAllDefaultGaugeNames(config.getDefaultGaugeNameList())
                .build();
    }
}
