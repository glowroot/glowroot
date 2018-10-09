/*
 * Copyright 2018 the original author or authors.
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

import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.immutables.value.Value;

import org.glowroot.common.config.AdvancedConfig;
import org.glowroot.common.config.AlertConfig;
import org.glowroot.common.config.GaugeConfig;
import org.glowroot.common.config.InstrumentationConfig;
import org.glowroot.common.config.JvmConfig;
import org.glowroot.common.config.SyntheticMonitorConfig;
import org.glowroot.common.config.TransactionConfig;
import org.glowroot.common.config.UiDefaultsConfig;
import org.glowroot.common.config.UserRecordingConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginProperty;

@Value.Immutable
public abstract class AllConfig {

    abstract TransactionConfig transaction();
    abstract JvmConfig jvm();
    abstract UiDefaultsConfig uiDefaults();
    abstract UserRecordingConfig userRecording();
    abstract AdvancedConfig advanced();
    abstract List<GaugeConfig> gauges();
    abstract List<SyntheticMonitorConfig> syntheticMonitors();
    abstract List<AlertConfig> alerts();
    abstract List<PluginConfig> plugins();
    abstract List<InstrumentationConfig> instrumentation();

    public static AllConfig create(AgentConfig config, List<PluginDescriptor> pluginDescriptors) {
        ImmutableAllConfig.Builder builder = ImmutableAllConfig.builder()
                .transaction(TransactionConfig.create(config.getTransactionConfig()))
                .jvm(JvmConfig.create(config.getJvmConfig()))
                .uiDefaults(UiDefaultsConfig.create(config.getUiDefaultsConfig()))
                .userRecording(UserRecordingConfig.create(config.getUserRecordingConfig()))
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
        Map<String, AgentConfig.PluginConfig> newPluginConfigs = Maps.newHashMap();
        for (AgentConfig.PluginConfig newPluginConfig : config.getPluginConfigList()) {
            newPluginConfigs.put(newPluginConfig.getId(), newPluginConfig);
        }
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            AgentConfig.PluginConfig pluginConfig = newPluginConfigs.get(pluginDescriptor.id());
            List<PluginProperty> properties = Lists.newArrayList();
            if (pluginConfig == null) {
                properties = ImmutableList.of();
            } else {
                properties = pluginConfig.getPropertyList();
            }
            builder.addPlugins(PluginConfig.create(pluginDescriptor, properties));
        }
        for (AgentConfig.InstrumentationConfig instrumentationConfig : config
                .getInstrumentationConfigList()) {
            builder.addInstrumentation(InstrumentationConfig.create(instrumentationConfig));
        }
        return builder.build();
    }
}
