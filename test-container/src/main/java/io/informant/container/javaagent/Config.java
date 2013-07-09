/*
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
package io.informant.container.javaagent;

import java.util.List;
import java.util.Map;

import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.Objects;

import io.informant.container.config.CoarseProfilingConfig;
import io.informant.container.config.FineProfilingConfig;
import io.informant.container.config.GeneralConfig;
import io.informant.container.config.PluginConfig;
import io.informant.container.config.PointcutConfig;
import io.informant.container.config.StorageConfig;
import io.informant.container.config.UserConfig;

import static io.informant.container.common.ObjectMappers.checkRequiredProperty;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@JsonIgnoreProperties("pluginDescriptors")
class Config {

    private final GeneralConfig generalConfig;
    private final CoarseProfilingConfig coarseProfilingConfig;
    private final FineProfilingConfig fineProfilingConfig;
    private final UserConfig userConfig;
    private final StorageConfig storageConfig;
    private final Map<String, PluginConfig> pluginConfigs;
    private final String dataDir;
    private final List<PointcutConfig> pointcutConfigs;
    private final boolean pointcutConfigsOutOfSync;
    private final boolean retransformClassesSupported;

    @JsonCreator
    Config(@JsonProperty("generalConfig") @Nullable GeneralConfig generalConfig,
            @JsonProperty("coarseProfilingConfig") @Nullable CoarseProfilingConfig coarseProfilingConfig,
            @JsonProperty("fineProfilingConfig") @Nullable FineProfilingConfig fineProfilingConfig,
            @JsonProperty("userConfig") @Nullable UserConfig userConfig,
            @JsonProperty("storageConfig") @Nullable StorageConfig storageConfig,
            @JsonProperty("pluginConfigs") @Nullable Map<String, PluginConfig> pluginConfigs,
            @JsonProperty("dataDir") @Nullable String dataDir,
            @JsonProperty("pointcutConfigs") @Nullable List<PointcutConfig> pointcutConfigs,
            @JsonProperty("pointcutConfigsOutOfSync") @Nullable Boolean pointcutConfigsOutOfSync,
            @JsonProperty("retransformClassesSupported") @Nullable Boolean retransformClassesSupported)
            throws JsonMappingException {
        checkRequiredProperty(generalConfig, "generalConfig");
        checkRequiredProperty(coarseProfilingConfig, "coarseProfilingConfig");
        checkRequiredProperty(fineProfilingConfig, "fineProfilingConfig");
        checkRequiredProperty(userConfig, "userConfig");
        checkRequiredProperty(storageConfig, "storageConfig");
        checkRequiredProperty(pluginConfigs, "pluginConfigs");
        checkRequiredProperty(dataDir, "dataDir");
        checkRequiredProperty(pointcutConfigs, "pointcutConfigs");
        checkRequiredProperty(pointcutConfigsOutOfSync, "pointcutConfigsOutOfSync");
        checkRequiredProperty(retransformClassesSupported, "jdk5");
        this.generalConfig = generalConfig;
        this.coarseProfilingConfig = coarseProfilingConfig;
        this.fineProfilingConfig = fineProfilingConfig;
        this.userConfig = userConfig;
        this.storageConfig = storageConfig;
        this.pluginConfigs = pluginConfigs;
        this.dataDir = dataDir;
        this.pointcutConfigs = pointcutConfigs;
        this.pointcutConfigsOutOfSync = pointcutConfigsOutOfSync;
        this.retransformClassesSupported = retransformClassesSupported;
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

    UserConfig getUserConfig() {
        return userConfig;
    }

    StorageConfig getStorageConfig() {
        return storageConfig;
    }

    Map<String, PluginConfig> getPluginConfigs() {
        return pluginConfigs;
    }

    String getDataDir() {
        return dataDir;
    }

    List<PointcutConfig> getPointcutConfigs() {
        return pointcutConfigs;
    }

    boolean isPointcutConfigsOutOfSync() {
        return pointcutConfigsOutOfSync;
    }

    boolean isRetransformClassesSupported() {
        return retransformClassesSupported;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("generalConfig", generalConfig)
                .add("coarseProfilingConfig", coarseProfilingConfig)
                .add("fineProfilingConfig", fineProfilingConfig)
                .add("userConfig", userConfig)
                .add("pluginConfigs", pluginConfigs)
                .add("dataDir", dataDir)
                .add("pointcutConfigs", pointcutConfigs)
                .add("pointcutConfigsOutOfSync", pointcutConfigsOutOfSync)
                .add("retransformClassesSupported", retransformClassesSupported)
                .toString();
    }
}
