/*
 * Copyright 2013 the original author or authors.
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

import checkers.igj.quals.Immutable;
import checkers.igj.quals.ReadOnly;
import com.google.common.collect.ImmutableList;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
class Config {

    private final GeneralConfig generalConfig;
    private final CoarseProfilingConfig coarseProfilingConfig;
    private final FineProfilingConfig fineProfilingConfig;
    private final UserOverridesConfig userOverridesConfig;
    private final StorageConfig storageConfig;
    private final UserInterfaceConfig userInterfaceConfig;
    private final AdvancedConfig advancedConfig;
    private final ImmutableList<PluginConfig> pluginConfigs;
    private final ImmutableList<PointcutConfig> pointcutConfigs;

    static Config getDefault(@ReadOnly List<PluginDescriptor> pluginDescriptors) {
        return new Config(GeneralConfig.getDefault(), CoarseProfilingConfig.getDefault(),
                FineProfilingConfig.getDefault(), UserOverridesConfig.getDefault(),
                StorageConfig.getDefault(), UserInterfaceConfig.getDefault(),
                AdvancedConfig.getDefault(), createPluginConfigs(pluginDescriptors),
                ImmutableList.<PointcutConfig>of());
    }

    static Builder builder(Config base) {
        return new Builder(base);
    }

    Config(GeneralConfig generalConfig, CoarseProfilingConfig coarseProfilingConfig,
            FineProfilingConfig fineProfilingConfig, UserOverridesConfig userOverridesConfig,
            StorageConfig storageConfig, UserInterfaceConfig userInterfaceConfig,
            AdvancedConfig advancedConfig, ImmutableList<PluginConfig> pluginConfigs,
            ImmutableList<PointcutConfig> pointcutConfigs) {
        this.generalConfig = generalConfig;
        this.coarseProfilingConfig = coarseProfilingConfig;
        this.fineProfilingConfig = fineProfilingConfig;
        this.userOverridesConfig = userOverridesConfig;
        this.storageConfig = storageConfig;
        this.userInterfaceConfig = userInterfaceConfig;
        this.advancedConfig = advancedConfig;
        this.pluginConfigs = pluginConfigs;
        this.pointcutConfigs = pointcutConfigs;
    }

    GeneralConfig getGeneralConfig() {
        return generalConfig;
    }

    CoarseProfilingConfig getCoarseProfilingConfig() {
        return coarseProfilingConfig;
    }

    FineProfilingConfig getFineProfilingConfig() {
        return fineProfilingConfig;
    }

    UserOverridesConfig getUserOverridesConfig() {
        return userOverridesConfig;
    }

    StorageConfig getStorageConfig() {
        return storageConfig;
    }

    UserInterfaceConfig getUserInterfaceConfig() {
        return userInterfaceConfig;
    }

    AdvancedConfig getAdvancedConfig() {
        return advancedConfig;
    }

    ImmutableList<PluginConfig> getPluginConfigs() {
        return pluginConfigs;
    }

    ImmutableList<PointcutConfig> getPointcutConfigs() {
        return pointcutConfigs;
    }

    private static ImmutableList<PluginConfig> createPluginConfigs(
            @ReadOnly List<PluginDescriptor> pluginDescriptors) {
        ImmutableList.Builder<PluginConfig> pluginConfigs = ImmutableList.builder();
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            pluginConfigs.add(PluginConfig.getDefault(pluginDescriptor));
        }
        return pluginConfigs.build();
    }

    static class Builder {

        private GeneralConfig generalConfig;
        private CoarseProfilingConfig coarseProfilingConfig;
        private FineProfilingConfig fineProfilingConfig;
        private UserOverridesConfig userOverridesConfig;
        private StorageConfig storageConfig;
        private UserInterfaceConfig userInterfaceConfig;
        private AdvancedConfig advancedConfig;
        private ImmutableList<PluginConfig> pluginConfigs;
        private ImmutableList<PointcutConfig> pointcutConfigs;

        private Builder(Config base) {
            generalConfig = base.generalConfig;
            coarseProfilingConfig = base.coarseProfilingConfig;
            fineProfilingConfig = base.fineProfilingConfig;
            userOverridesConfig = base.userOverridesConfig;
            storageConfig = base.storageConfig;
            userInterfaceConfig = base.userInterfaceConfig;
            advancedConfig = base.advancedConfig;
            pluginConfigs = base.pluginConfigs;
            pointcutConfigs = base.pointcutConfigs;
        }
        Builder generalConfig(GeneralConfig generalConfig) {
            this.generalConfig = generalConfig;
            return this;
        }
        Builder coarseProfilingConfig(CoarseProfilingConfig coarseProfilingConfig) {
            this.coarseProfilingConfig = coarseProfilingConfig;
            return this;
        }
        Builder fineProfilingConfig(FineProfilingConfig fineProfilingConfig) {
            this.fineProfilingConfig = fineProfilingConfig;
            return this;
        }
        Builder userOverridesConfig(UserOverridesConfig userOverridesConfig) {
            this.userOverridesConfig = userOverridesConfig;
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
        Builder pluginConfigs(ImmutableList<PluginConfig> pluginConfigs) {
            this.pluginConfigs = pluginConfigs;
            return this;
        }
        Builder pointcutConfigs(ImmutableList<PointcutConfig> pointcutConfigs) {
            this.pointcutConfigs = pointcutConfigs;
            return this;
        }
        Config build() {
            return new Config(generalConfig, coarseProfilingConfig, fineProfilingConfig,
                    userOverridesConfig, storageConfig, userInterfaceConfig, advancedConfig,
                    pluginConfigs, pointcutConfigs);
        }
    }
}
