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

import com.google.common.base.Objects;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class Config {

    @Nullable
    private GeneralConfig generalConfig;
    @Nullable
    private CoarseProfilingConfig coarseProfilingConfig;
    @Nullable
    private FineProfilingConfig fineProfilingConfig;
    @Nullable
    private UserConfig userConfig;
    @Nullable
    private Map<String, PluginConfig> pluginConfigs;
    @Nullable
    private List<PointcutConfig> pointcutConfigs;

    @Nullable
    public GeneralConfig getGeneralConfig() {
        return generalConfig;
    }

    @Nullable
    public CoarseProfilingConfig getCoarseProfilingConfig() {
        return coarseProfilingConfig;
    }

    @Nullable
    public FineProfilingConfig getFineProfilingConfig() {
        return fineProfilingConfig;
    }

    @Nullable
    public UserConfig getUserConfig() {
        return userConfig;
    }

    @Nullable
    public Map<String, PluginConfig> getPluginConfigs() {
        return pluginConfigs;
    }

    @Nullable
    public List<PointcutConfig> getPointcutConfigs() {
        return pointcutConfigs;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof Config) {
            Config that = (Config) obj;
            return Objects.equal(generalConfig, that.generalConfig)
                    && Objects.equal(coarseProfilingConfig, that.coarseProfilingConfig)
                    && Objects.equal(fineProfilingConfig, that.fineProfilingConfig)
                    && Objects.equal(userConfig, that.userConfig)
                    && Objects.equal(pluginConfigs, that.pluginConfigs)
                    && Objects.equal(pointcutConfigs, that.pointcutConfigs);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(generalConfig, coarseProfilingConfig, fineProfilingConfig,
                userConfig, pluginConfigs, pointcutConfigs);
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
                .toString();
    }
}
