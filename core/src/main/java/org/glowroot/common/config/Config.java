/*
 * Copyright 2013-2015 the original author or authors.
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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;

@Value.Immutable
// ignore this old property name as part of upgrade from 0.8.3 to 0.8.4
@JsonIgnoreProperties("general")
public abstract class Config {

    @Value.Default
    @JsonProperty("transaction")
    public TransactionConfig transactionConfig() {
        return ImmutableTransactionConfig.builder().build();
    }

    @Value.Default
    @JsonProperty("ui")
    public UserInterfaceConfig userInterfaceConfig() {
        return ImmutableUserInterfaceConfig.builder().build();
    }

    @Value.Default
    @JsonProperty("storage")
    public StorageConfig storageConfig() {
        return ImmutableStorageConfig.builder().build();
    }

    @Value.Default
    @JsonProperty("smtp")
    public SmtpConfig smtpConfig() {
        return ImmutableSmtpConfig.builder().build();
    }

    @Value.Default
    @JsonProperty("userRecording")
    public UserRecordingConfig userRecordingConfig() {
        return ImmutableUserRecordingConfig.builder().build();
    }

    @Value.Default
    @JsonProperty("advanced")
    public AdvancedConfig advancedConfig() {
        return ImmutableAdvancedConfig.builder().build();
    }

    @JsonProperty("plugins")
    public abstract ImmutableList<PluginConfig> pluginConfigs();

    @JsonProperty("instrumentation")
    public abstract ImmutableList<InstrumentationConfig> instrumentationConfigs();

    @JsonProperty("gauges")
    public abstract ImmutableList<GaugeConfig> gaugeConfigs();

    @JsonProperty("alerts")
    public abstract ImmutableList<AlertConfig> alertConfigs();
}
