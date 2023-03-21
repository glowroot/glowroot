/*
 * Copyright 2018-2023 the original author or authors.
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
package org.glowroot.ui;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import org.immutables.value.Value;

import org.glowroot.common.config.AdvancedConfig;
import org.glowroot.common.config.AlertConfig;
import org.glowroot.common.config.GaugeConfig;
import org.glowroot.common.config.ImmutableAdvancedConfig;
import org.glowroot.common.config.ImmutableAlertConfig;
import org.glowroot.common.config.ImmutableGaugeConfig;
import org.glowroot.common.config.ImmutableInstrumentationConfig;
import org.glowroot.common.config.ImmutableJvmConfig;
import org.glowroot.common.config.ImmutableSyntheticMonitorConfig;
import org.glowroot.common.config.ImmutableTransactionConfig;
import org.glowroot.common.config.ImmutableUiDefaultsConfig;
import org.glowroot.common.config.InstrumentationConfig;
import org.glowroot.common.config.JvmConfig;
import org.glowroot.common.config.PropertyValue;
import org.glowroot.common.config.SyntheticMonitorConfig;
import org.glowroot.common.config.TransactionConfig;
import org.glowroot.common.config.UiDefaultsConfig;
import org.glowroot.common.util.Versions;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginProperty;

@Value.Immutable
abstract class AllConfigDto {

    @Value.Default
    ImmutableTransactionConfig transactions() {
        return ImmutableTransactionConfig.builder().build();
    }

    @Value.Default
    ImmutableJvmConfig jvm() {
        return ImmutableJvmConfig.builder().build();
    }

    @Value.Default
    ImmutableUiDefaultsConfig uiDefaults() {
        return ImmutableUiDefaultsConfig.builder().build();
    }

    @Value.Default
    ImmutableAdvancedConfig advanced() {
        return ImmutableAdvancedConfig.builder().build();
    }

    @JsonInclude(Include.NON_EMPTY)
    abstract List<ImmutableGaugeConfig> gauges();

    @JsonInclude(Include.NON_EMPTY)
    abstract List<ImmutableSyntheticMonitorConfig> syntheticMonitors();

    @JsonInclude(Include.NON_EMPTY)
    abstract List<ImmutableAlertConfig> alerts();

    @JsonInclude(Include.NON_EMPTY)
    abstract List<ImmutablePluginConfig> plugins();

    @JsonInclude(Include.NON_EMPTY)
    abstract List<ImmutableInstrumentationConfig> instrumentation();

    abstract @Nullable String version();

    AgentConfig toProto() {
        AgentConfig.Builder builder = AgentConfig.newBuilder()
                .setTransactionConfig(transactions().toProto())
                .setJvmConfig(jvm().toProto())
                .setUiDefaultsConfig(uiDefaults().toProto())
                .setAdvancedConfig(advanced().toProto());
        for (GaugeConfig gaugeConfig : gauges()) {
            builder.addGaugeConfig(gaugeConfig.toProto());
        }
        for (SyntheticMonitorConfig syntheticMonitorConfig : syntheticMonitors()) {
            builder.addSyntheticMonitorConfig(syntheticMonitorConfig.toProto());
        }
        for (AlertConfig alertConfig : alerts()) {
            builder.addAlertConfig(alertConfig.toProto());
        }
        for (PluginConfig pluginConfig : plugins()) {
            builder.addPluginConfig(pluginConfig.toProto());
        }
        for (InstrumentationConfig instrumentationConfig : instrumentation()) {
            builder.addInstrumentationConfig(instrumentationConfig.toProto());
        }
        return builder.build();
    }

    static AllConfigDto create(AgentConfig config) {
        ImmutableAllConfigDto.Builder builder = ImmutableAllConfigDto.builder()
                .transactions(TransactionConfig.create(config.getTransactionConfig()))
                .jvm(JvmConfig.create(config.getJvmConfig()))
                .uiDefaults(UiDefaultsConfig.create(config.getUiDefaultsConfig()))
                .advanced(AdvancedConfig.create(config.getAdvancedConfig()));
        for (AgentConfig.GaugeConfig gaugeConfig : config.getGaugeConfigList()) {
            builder.addGauges(GaugeConfig.create(gaugeConfig));
        }
        for (AgentConfig.SyntheticMonitorConfig syntheticMonitorConfig : config
                .getSyntheticMonitorConfigList()) {
            builder.addSyntheticMonitors(SyntheticMonitorConfig.create(syntheticMonitorConfig));
        }
        for (AgentConfig.AlertConfig alertConfig : config.getAlertConfigList()) {
            builder.addAlerts(AlertConfig.create(alertConfig));
        }
        for (AgentConfig.PluginConfig pluginConfig : config.getPluginConfigList()) {
            if (pluginConfig.getPropertyCount() > 0) {
                builder.addPlugins(PluginConfig.create(pluginConfig));
            }
        }
        for (AgentConfig.InstrumentationConfig instrumentationConfig : config
                .getInstrumentationConfigList()) {
            builder.addInstrumentation(InstrumentationConfig.create(instrumentationConfig));
        }
        return builder.version(Versions.getVersion(config))
                .build();
    }

    @Value.Immutable
    abstract static class PluginConfig {

        abstract String id();

        // when written to config.json, this will have all plugin properties
        // so not using @Json.ForceEmpty since new plugin properties can't be added in config.json
        // anyways
        abstract Map<String, PropertyValue> properties();

        AgentConfig.PluginConfig toProto() {
            AgentConfig.PluginConfig.Builder builder = AgentConfig.PluginConfig.newBuilder()
                    .setId(id());
            for (Map.Entry<String, PropertyValue> entry : properties().entrySet()) {
                PluginProperty.Builder property = PluginProperty.newBuilder()
                        .setName(entry.getKey())
                        .setValue(entry.getValue().toProto());
                builder.addProperty(property);
            }
            return builder.build();
        }

        public static ImmutablePluginConfig create(AgentConfig.PluginConfig config) {
            ImmutablePluginConfig.Builder builder = ImmutablePluginConfig.builder()
                    .id(config.getId());
            for (PluginProperty prop : config.getPropertyList()) {
                builder.putProperties(prop.getName(), PropertyValue.create(prop.getValue()));
            }
            return builder.build();
        }
    }
}
