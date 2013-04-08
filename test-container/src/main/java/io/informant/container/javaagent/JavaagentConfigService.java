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
package io.informant.container.javaagent;

import java.util.List;

import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.informant.container.common.ObjectMappers;
import io.informant.container.config.CoarseProfilingConfig;
import io.informant.container.config.ConfigService;
import io.informant.container.config.FineProfilingConfig;
import io.informant.container.config.GeneralConfig;
import io.informant.container.config.PluginConfig;
import io.informant.container.config.PointcutConfig;
import io.informant.container.config.StorageConfig;
import io.informant.container.config.UserConfig;
import io.informant.markers.ThreadSafe;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
class JavaagentConfigService implements ConfigService {

    @ReadOnly
    private static final ObjectMapper mapper = ObjectMappers.create();

    private final JavaagentHttpClient httpClient;

    JavaagentConfigService(JavaagentHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public void setStoreThresholdMillis(int storeThresholdMillis) throws Exception {
        GeneralConfig generalConfig = getGeneralConfig();
        generalConfig.setStoreThresholdMillis(storeThresholdMillis);
        updateGeneralConfig(generalConfig);
    }

    public GeneralConfig getGeneralConfig() throws Exception {
        return getConfig().getGeneralConfig();
    }

    // returns new version
    public String updateGeneralConfig(GeneralConfig config) throws Exception {
        String content = httpClient.post("/config/general", mapper.writeValueAsString(config));
        return ObjectMappers.readRequiredValue(mapper, content, String.class);
    }

    public CoarseProfilingConfig getCoarseProfilingConfig() throws Exception {
        return getConfig().getCoarseProfilingConfig();
    }

    // returns new version
    public String updateCoarseProfilingConfig(CoarseProfilingConfig config) throws Exception {
        String content =
                httpClient.post("/config/coarse-profiling", mapper.writeValueAsString(config));
        return ObjectMappers.readRequiredValue(mapper, content, String.class);
    }

    public FineProfilingConfig getFineProfilingConfig() throws Exception {
        return getConfig().getFineProfilingConfig();
    }

    // returns new version
    public String updateFineProfilingConfig(FineProfilingConfig config) throws Exception {
        String content =
                httpClient.post("/config/fine-profiling", mapper.writeValueAsString(config));
        return ObjectMappers.readRequiredValue(mapper, content, String.class);
    }

    public UserConfig getUserConfig() throws Exception {
        return getConfig().getUserConfig();
    }

    // returns new version
    public String updateUserConfig(UserConfig config) throws Exception {
        String content = httpClient.post("/config/user", mapper.writeValueAsString(config));
        return ObjectMappers.readRequiredValue(mapper, content, String.class);
    }

    public StorageConfig getStorageConfig() throws Exception {
        return getConfig().getStorageConfig();
    }

    // returns new version
    public String updateStorageConfig(StorageConfig config) throws Exception {
        String content = httpClient.post("/config/storage", mapper.writeValueAsString(config));
        return ObjectMappers.readRequiredValue(mapper, content, String.class);
    }

    @Nullable
    public PluginConfig getPluginConfig(String pluginId) throws Exception {
        return getConfig().getPluginConfigs().get(pluginId);
    }

    // returns new version
    public String updatePluginConfig(String pluginId, PluginConfig config) throws Exception {
        String content =
                httpClient.post("/config/plugin/" + pluginId, mapper.writeValueAsString(config));
        return ObjectMappers.readRequiredValue(mapper, content, String.class);
    }

    public List<PointcutConfig> getPointcutConfigs() throws Exception {
        return getConfig().getPointcutConfigs();
    }

    // returns new version
    public String addPointcutConfig(PointcutConfig pointcutConfig) throws Exception {
        String content =
                httpClient.post("/config/pointcut/+", mapper.writeValueAsString(pointcutConfig));
        return ObjectMappers.readRequiredValue(mapper, content, String.class);
    }

    // returns new version
    public String updatePointcutConfig(String version, PointcutConfig pointcutConfig)
            throws Exception {
        String content = httpClient.post("/config/pointcut/" + version,
                mapper.writeValueAsString(pointcutConfig));
        return ObjectMappers.readRequiredValue(mapper, content, String.class);
    }

    public void removePointcutConfig(String version) throws Exception {
        httpClient.post("/config/pointcut/-", mapper.writeValueAsString(version));
    }

    public void compactData() throws Exception {
        httpClient.post("/admin/data/compact", "");
    }

    void resetAllConfig() throws Exception {
        httpClient.post("/admin/config/reset-all", "");
    }

    private Config getConfig() throws Exception {
        return ObjectMappers.readRequiredValue(mapper, httpClient.get("/config/read"),
                Config.class);
    }
}
