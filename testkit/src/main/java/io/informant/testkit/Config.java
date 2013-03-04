/**
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.testkit;

import java.util.List;
import java.util.Map;

import checkers.nullness.quals.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Objects;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class Config {

    @JsonProperty
    @Nullable
    private GeneralConfig generalConfig;
    @JsonProperty
    @Nullable
    private CoarseProfilingConfig coarseProfilingConfig;
    @JsonProperty
    @Nullable
    private FineProfilingConfig fineProfilingConfig;
    @JsonProperty
    @Nullable
    private UserConfig userConfig;
    @JsonProperty
    @Nullable
    private Map<String, PluginConfig> pluginConfigs;
    @JsonProperty
    @Nullable
    private List<PointcutConfig> pointcutConfigs;
    @JsonProperty
    @Nullable
    private List<PluginDescriptor> pluginDescriptors;
    @JsonProperty
    @Nullable
    private String dataDir;
    @JsonProperty
    @Nullable
    private Integer uiPort;

    @Nullable
    GeneralConfig getGeneralConfig() {
        return generalConfig;
    }

    @Nullable
    CoarseProfilingConfig getCoarseProfilingConfig() {
        return coarseProfilingConfig;
    }

    @Nullable
    FineProfilingConfig getFineProfilingConfig() {
        return fineProfilingConfig;
    }

    @Nullable
    UserConfig getUserConfig() {
        return userConfig;
    }

    @Nullable
    Map<String, PluginConfig> getPluginConfigs() {
        return pluginConfigs;
    }

    @Nullable
    List<PointcutConfig> getPointcutConfigs() {
        return pointcutConfigs;
    }

    @Nullable
    List<PluginDescriptor> getPluginDescriptors() {
        return pluginDescriptors;
    }

    @Nullable
    String getDataDir() {
        return dataDir;
    }

    @Nullable
    public Integer getUiPort() {
        return uiPort;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("generalConfig", generalConfig)
                .add("coarseProfilingConfig", coarseProfilingConfig)
                .add("fineProfilingConfig", fineProfilingConfig)
                .add("userConfig", userConfig)
                .add("pluginConfigs", pluginConfigs)
                .add("pointcutConfigs", pointcutConfigs)
                .add("pluginDescriptors", pluginDescriptors)
                .add("dataDir", dataDir)
                .add("uiPort", uiPort)
                .toString();
    }
}
