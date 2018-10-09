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
package org.glowroot.common2.repo;

import java.util.Set;

import com.google.common.collect.Sets;

import org.glowroot.common.util.Versions;
import org.glowroot.common2.repo.ConfigRepository.DuplicateMBeanObjectNameException;
import org.glowroot.common2.repo.ConfigRepository.DuplicateSyntheticMonitorDisplayException;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;

public class ConfigValidation {

    private ConfigValidation() {}

    public static void validatePartOne(AgentConfig config) throws Exception {
        Set<String> gaugeMBeanObjectNames = Sets.newHashSet();
        for (AgentConfig.GaugeConfig gaugeConfig : config.getGaugeConfigList()) {
            if (!gaugeMBeanObjectNames.add(gaugeConfig.getMbeanObjectName())) {
                throw new DuplicateMBeanObjectNameException();
            }
        }
        Set<String> syntheticMonitorDisplays = Sets.newHashSet();
        for (AgentConfig.SyntheticMonitorConfig syntheticMonitorConfig : config
                .getSyntheticMonitorConfigList()) {
            if (!syntheticMonitorDisplays.add(syntheticMonitorConfig.getDisplay())) {
                throw new DuplicateSyntheticMonitorDisplayException();
            }
        }
        Set<String> alertVersions = Sets.newHashSet();
        for (AgentConfig.AlertConfig alertConfig : config.getAlertConfigList()) {
            if (!alertVersions.add(Versions.getVersion(alertConfig))) {
                throw new IllegalStateException("Duplicate alerts");
            }
        }
        Set<String> pluginIds = Sets.newHashSet();
        for (AgentConfig.PluginConfig pluginConfig : config.getPluginConfigList()) {
            if (!pluginIds.add(pluginConfig.getId())) {
                throw new IllegalStateException("Duplicate plugin id: " + pluginConfig.getId());
            }
        }
        Set<String> instrumentationVersions = Sets.newHashSet();
        for (AgentConfig.InstrumentationConfig instrumentationConfig : config
                .getInstrumentationConfigList()) {
            if (!instrumentationVersions.add(Versions.getVersion(instrumentationConfig))) {
                throw new IllegalStateException("Duplicate instrumentation");
            }
        }
    }

    public static void validatePartTwo(AgentConfig config, Set<String> validPluginIds)
            throws Exception {
        for (AgentConfig.PluginConfig pluginConfig : config.getPluginConfigList()) {
            if (!validPluginIds.contains(pluginConfig.getId())) {
                throw new IllegalStateException("Invalid plugin id: " + pluginConfig.getId());
            }
        }
    }
}
