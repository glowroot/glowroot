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

    private final TraceConfig traceConfig;
    private final ProfilingConfig profilingConfig;
    private final UserRecordingConfig userRecordingConfig;
    private final StorageConfig storageConfig;
    private final UserInterfaceConfig userInterfaceConfig;
    private final AdvancedConfig advancedConfig;
    private final ImmutableList<PluginConfig> pluginConfigs;
    private final ImmutableList<CapturePoint> capturePoints;

    static Config getDefault(ImmutableList<PluginDescriptor> pluginDescriptors) {
        return new Config(TraceConfig.getDefault(), ProfilingConfig.getDefault(),
                UserRecordingConfig.getDefault(), StorageConfig.getDefault(),
                UserInterfaceConfig.getDefault(pluginDescriptors), AdvancedConfig.getDefault(),
                createPluginConfigs(pluginDescriptors), ImmutableList.<CapturePoint>of());
    }

    static Builder builder(Config base) {
        return new Builder(base);
    }

    Config(TraceConfig traceConfig, ProfilingConfig profilingConfig,
            UserRecordingConfig userRecordingConfig, StorageConfig storageConfig,
            UserInterfaceConfig userInterfaceConfig, AdvancedConfig advancedConfig,
            List<PluginConfig> pluginConfigs, List<CapturePoint> capturePoints) {
        this.traceConfig = traceConfig;
        this.profilingConfig = profilingConfig;
        this.userRecordingConfig = userRecordingConfig;
        this.storageConfig = storageConfig;
        this.userInterfaceConfig = userInterfaceConfig;
        this.advancedConfig = advancedConfig;
        this.pluginConfigs = ImmutableList.copyOf(pluginConfigs);
        this.capturePoints = ImmutableList.copyOf(capturePoints);
    }

    TraceConfig getTraceConfig() {
        return traceConfig;
    }

    ProfilingConfig getProfilingConfig() {
        return profilingConfig;
    }

    UserRecordingConfig getUserRecordingConfig() {
        return userRecordingConfig;
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

    ImmutableList<CapturePoint> getCapturePoints() {
        return capturePoints;
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

        private TraceConfig traceConfig;
        private ProfilingConfig profilingConfig;
        private UserRecordingConfig userRecordingConfig;
        private StorageConfig storageConfig;
        private UserInterfaceConfig userInterfaceConfig;
        private AdvancedConfig advancedConfig;
        private List<PluginConfig> pluginConfigs;
        private List<CapturePoint> capturePoints;

        private Builder(Config base) {
            traceConfig = base.traceConfig;
            profilingConfig = base.profilingConfig;
            userRecordingConfig = base.userRecordingConfig;
            storageConfig = base.storageConfig;
            userInterfaceConfig = base.userInterfaceConfig;
            advancedConfig = base.advancedConfig;
            pluginConfigs = base.pluginConfigs;
            capturePoints = base.capturePoints;
        }
        Builder traceConfig(TraceConfig traceConfig) {
            this.traceConfig = traceConfig;
            return this;
        }
        Builder profilingConfig(ProfilingConfig profilingConfig) {
            this.profilingConfig = profilingConfig;
            return this;
        }
        Builder userRecordingConfig(UserRecordingConfig userRecordingConfig) {
            this.userRecordingConfig = userRecordingConfig;
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
        Builder pluginConfigs(List<PluginConfig> pluginConfigs) {
            this.pluginConfigs = pluginConfigs;
            return this;
        }
        Builder capturePoints(List<CapturePoint> capturePoints) {
            this.capturePoints = capturePoints;
            return this;
        }
        Config build() {
            return new Config(traceConfig, profilingConfig, userRecordingConfig, storageConfig,
                    userInterfaceConfig, advancedConfig, pluginConfigs, capturePoints);
        }
    }
}
