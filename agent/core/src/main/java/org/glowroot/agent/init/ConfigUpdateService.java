/*
 * Copyright 2015-2017 the original author or authors.
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
package org.glowroot.agent.init;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import org.glowroot.agent.collector.Collector.AgentConfigUpdater;
import org.glowroot.agent.config.AdvancedConfig;
import org.glowroot.agent.config.AlertConfig;
import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.config.GaugeConfig;
import org.glowroot.agent.config.InstrumentationConfig;
import org.glowroot.agent.config.JvmConfig;
import org.glowroot.agent.config.PluginCache;
import org.glowroot.agent.config.PluginConfig;
import org.glowroot.agent.config.PluginDescriptor;
import org.glowroot.agent.config.SyntheticMonitorConfig;
import org.glowroot.agent.config.TransactionConfig;
import org.glowroot.agent.config.UiConfig;
import org.glowroot.agent.config.UserRecordingConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;

class ConfigUpdateService implements AgentConfigUpdater {

    private final ConfigService configService;
    private final PluginCache pluginCache;

    private final Object lock = new Object();

    ConfigUpdateService(ConfigService configService, PluginCache pluginCache) {
        this.configService = configService;
        this.pluginCache = pluginCache;
    }

    // ui config, synthetic monitor configs and alert configs are not used by agent, but updated
    // here to keep config.json in sync with central, to allow copying to another deployment
    @Override
    public void update(AgentConfig agentConfig) throws IOException {
        synchronized (lock) {
            configService.updateTransactionConfig(
                    TransactionConfig.create(agentConfig.getTransactionConfig()));
            configService.updateJvmConfig(JvmConfig.create(agentConfig.getJvmConfig()));
            configService.updateUiConfig(UiConfig.create(agentConfig.getUiConfig()));
            configService.updateUserRecordingConfig(
                    UserRecordingConfig.create(agentConfig.getUserRecordingConfig()));
            configService
                    .updateAdvancedConfig(AdvancedConfig.create(agentConfig.getAdvancedConfig()));
            List<GaugeConfig> gaugeConfigs = Lists.newArrayList();
            for (AgentConfig.GaugeConfig gaugeConfig : agentConfig.getGaugeConfigList()) {
                gaugeConfigs.add(GaugeConfig.create(gaugeConfig));
            }
            configService.updateGaugeConfigs(gaugeConfigs);
            List<SyntheticMonitorConfig> syntheticMonitorConfigs = Lists.newArrayList();
            for (AgentConfig.SyntheticMonitorConfig syntheticMonitorConfig : agentConfig
                    .getSyntheticMonitorConfigList()) {
                syntheticMonitorConfigs.add(SyntheticMonitorConfig.create(syntheticMonitorConfig));
            }
            configService.updateSyntheticMonitorConfigs(syntheticMonitorConfigs);
            List<AlertConfig> alertConfigs = Lists.newArrayList();
            for (AgentConfig.AlertConfig alertConfig : agentConfig.getAlertConfigList()) {
                alertConfigs.add(AlertConfig.create(alertConfig));
            }
            configService.updateAlertConfigs(alertConfigs);
            Map<String, AgentConfig.PluginConfig> map = Maps.newHashMap();
            for (AgentConfig.PluginConfig pluginConfig : agentConfig.getPluginConfigList()) {
                map.put(pluginConfig.getId(), pluginConfig);
            }
            List<PluginConfig> pluginConfigs = Lists.newArrayList();
            for (PluginDescriptor pluginDescriptor : pluginCache.pluginDescriptors()) {
                AgentConfig.PluginConfig pluginConfig = map.get(pluginDescriptor.id());
                if (pluginConfig == null) {
                    pluginConfig = AgentConfig.PluginConfig.newBuilder()
                            .setId(pluginDescriptor.id())
                            .build();
                }
                pluginConfigs
                        .add(PluginConfig.create(pluginDescriptor, pluginConfig.getPropertyList()));
            }
            configService.updatePluginConfigs(pluginConfigs);
            List<InstrumentationConfig> instrumentationConfigs = Lists.newArrayList();
            for (AgentConfig.InstrumentationConfig instrumentationConfig : agentConfig
                    .getInstrumentationConfigList()) {
                instrumentationConfigs.add(InstrumentationConfig.create(instrumentationConfig));
            }
            configService.updateInstrumentationConfigs(instrumentationConfigs);
        }
    }
}
