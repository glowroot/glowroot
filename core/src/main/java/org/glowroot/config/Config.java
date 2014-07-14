/*
 * Copyright 2013-2014 the original author or authors.
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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import org.glowroot.markers.Immutable;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
class Config {

    private final GeneralConfig generalConfig;
    private final ProfilingConfig profilingConfig;
    private final OutlierProfilingConfig outlierProfilingConfig;
    private final UserTracingConfig userTracingConfig;
    private final StorageConfig storageConfig;
    private final UserInterfaceConfig userInterfaceConfig;
    private final AdvancedConfig advancedConfig;
    private final ImmutableList<PointcutConfig> pointcutConfigs;
    private final ImmutableList<PluginConfig> pluginConfigs;

    static Config getDefault(ImmutableList<PluginDescriptor> pluginDescriptors) {
        return new Config(GeneralConfig.getDefault(), ProfilingConfig.getDefault(),
                OutlierProfilingConfig.getDefault(), UserTracingConfig.getDefault(),
                StorageConfig.getDefault(), UserInterfaceConfig.getDefault(pluginDescriptors),
                AdvancedConfig.getDefault(), ImmutableList.<PointcutConfig>of(),
                createPluginConfigs(pluginDescriptors));
    }

    static Builder builder(Config base) {
        return new Builder(base);
    }

    Config(GeneralConfig generalConfig, ProfilingConfig profilingConfig,
            OutlierProfilingConfig outlierProfilingConfig, UserTracingConfig userTracingConfig,
            StorageConfig storageConfig, UserInterfaceConfig userInterfaceConfig,
            AdvancedConfig advancedConfig, List<PointcutConfig> pointcutConfigs,
            List<PluginConfig> pluginConfigs) {
        this.generalConfig = generalConfig;
        this.profilingConfig = profilingConfig;
        this.outlierProfilingConfig = outlierProfilingConfig;
        this.userTracingConfig = userTracingConfig;
        this.storageConfig = storageConfig;
        this.userInterfaceConfig = userInterfaceConfig;
        this.advancedConfig = advancedConfig;
        this.pointcutConfigs = ImmutableList.copyOf(pointcutConfigs);
        this.pluginConfigs = ImmutableList.copyOf(pluginConfigs);
    }

    GeneralConfig getGeneralConfig() {
        return generalConfig;
    }

    ProfilingConfig getProfilingConfig() {
        return profilingConfig;
    }

    OutlierProfilingConfig getOutlierProfilingConfig() {
        return outlierProfilingConfig;
    }

    UserTracingConfig getUserTracingConfig() {
        return userTracingConfig;
    }

    StorageConfig getStorageConfig() {
        return storageConfig;
    }

    UserInterfaceConfig getUserInterfaceConfig() {
        return userInterfaceConfig;
    }

    ImmutableList<PointcutConfig> getPointcutConfigs() {
        return pointcutConfigs;
    }

    AdvancedConfig getAdvancedConfig() {
        return advancedConfig;
    }

    ImmutableList<PluginConfig> getPluginConfigs() {
        return pluginConfigs;
    }

    private static List<PluginConfig> createPluginConfigs(
            ImmutableList<PluginDescriptor> pluginDescriptors) {
        List<PluginConfig> pluginConfigs = Lists.newArrayList();
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            pluginConfigs.add(PluginConfig.getDefault(pluginDescriptor));
        }
        return pluginConfigs;
    }

    static class Builder {

        private GeneralConfig generalConfig;
        private ProfilingConfig profilingConfig;
        private OutlierProfilingConfig outlierProfilingConfig;
        private UserTracingConfig userTracingConfig;
        private StorageConfig storageConfig;
        private UserInterfaceConfig userInterfaceConfig;
        private AdvancedConfig advancedConfig;
        private List<PointcutConfig> pointcutConfigs;
        private List<PluginConfig> pluginConfigs;

        private Builder(Config base) {
            generalConfig = base.generalConfig;
            profilingConfig = base.profilingConfig;
            outlierProfilingConfig = base.outlierProfilingConfig;
            userTracingConfig = base.userTracingConfig;
            storageConfig = base.storageConfig;
            userInterfaceConfig = base.userInterfaceConfig;
            advancedConfig = base.advancedConfig;
            pointcutConfigs = base.pointcutConfigs;
            pluginConfigs = base.pluginConfigs;
        }
        Builder generalConfig(GeneralConfig generalConfig) {
            this.generalConfig = generalConfig;
            return this;
        }
        Builder profilingConfig(ProfilingConfig profilingConfig) {
            this.profilingConfig = profilingConfig;
            return this;
        }
        Builder outlierProfilingConfig(OutlierProfilingConfig outlierProfilingConfig) {
            this.outlierProfilingConfig = outlierProfilingConfig;
            return this;
        }
        Builder userTracingConfig(UserTracingConfig userTracingConfig) {
            this.userTracingConfig = userTracingConfig;
            return this;
        }
        Builder storageConfig(StorageConfig storageConfig) {
            this.storageConfig = storageConfig;
            return this;
        }
        Builder userInterfaceConfig(UserInterfaceConfig userInterfaceConfig) {
            this.userInterfaceConfig = userInterfaceConfig;
            return this;
        }
        Builder advancedConfig(AdvancedConfig advancedConfig) {
            this.advancedConfig = advancedConfig;
            return this;
        }
        Builder pointcutConfigs(List<PointcutConfig> pointcutConfigs) {
            this.pointcutConfigs = pointcutConfigs;
            return this;
        }
        Builder pluginConfigs(List<PluginConfig> pluginConfigs) {
            this.pluginConfigs = pluginConfigs;
            return this;
        }
        Config build() {
            return new Config(generalConfig, profilingConfig, outlierProfilingConfig,
                    userTracingConfig, storageConfig, userInterfaceConfig, advancedConfig,
                    pointcutConfigs, pluginConfigs);
        }
    }
}
