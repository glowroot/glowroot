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
package org.glowroot.config;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.immutables.value.Value;

@Value.Immutable
@JsonSerialize(as = ImmutableConfig.class)
@JsonDeserialize(as = ImmutableConfig.class)
abstract class Config {

    @Value.Default
    @JsonProperty("general")
    GeneralConfig generalConfig() {
        return ImmutableGeneralConfig.builder().build();
    }

    @Value.Default
    @JsonProperty("ui")
    UserInterfaceConfig userInterfaceConfig() {
        return ImmutableUserInterfaceConfig.builder().build();
    }

    @Value.Default
    @JsonProperty("storage")
    StorageConfig storageConfig() {
        return ImmutableStorageConfig.builder().build();
    }

    @Value.Default
    @JsonProperty("smtp")
    SmtpConfig smtpConfig() {
        return ImmutableSmtpConfig.builder().build();
    }

    @Value.Default
    @JsonProperty("userRecording")
    UserRecordingConfig userRecordingConfig() {
        return ImmutableUserRecordingConfig.builder().build();
    }

    @Value.Default
    @JsonProperty("advanced")
    AdvancedConfig advancedConfig() {
        return ImmutableAdvancedConfig.builder().build();
    }

    @JsonProperty("plugins")
    abstract List<PluginConfig> pluginConfigs();

    @JsonProperty("instrumentation")
    abstract List<InstrumentationConfig> instrumentationConfigs();

    @JsonProperty("gauges")
    abstract List<GaugeConfig> gaugeConfigs();

    @JsonProperty("alerts")
    abstract List<AlertConfig> alertConfigs();
}
