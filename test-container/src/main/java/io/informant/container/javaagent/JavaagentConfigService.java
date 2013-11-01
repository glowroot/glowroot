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

import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.informant.container.common.ObjectMappers;
import io.informant.container.config.AdhocPointcutConfig;
import io.informant.container.config.AdvancedConfig;
import io.informant.container.config.CoarseProfilingConfig;
import io.informant.container.config.ConfigService;
import io.informant.container.config.FineProfilingConfig;
import io.informant.container.config.GeneralConfig;
import io.informant.container.config.PluginConfig;
import io.informant.container.config.StorageConfig;
import io.informant.container.config.UserInterfaceConfig;
import io.informant.container.config.UserOverridesConfig;
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
        return ObjectMappers.readRequiredValue(mapper, httpClient.get("/backend/config/general"),
                GeneralConfig.class);
    }

    // returns new version
    public String updateGeneralConfig(GeneralConfig config) throws Exception {
        return httpClient.post("/backend/config/general", mapper.writeValueAsString(config));
    }

    public CoarseProfilingConfig getCoarseProfilingConfig() throws Exception {
        return ObjectMappers.readRequiredValue(mapper,
                httpClient.get("/backend/config/coarse-profiling"), CoarseProfilingConfig.class);
    }

    // returns new version
    public String updateCoarseProfilingConfig(CoarseProfilingConfig config) throws Exception {
        return httpClient.post("/backend/config/coarse-profiling",
                mapper.writeValueAsString(config));
    }

    public FineProfilingConfig getFineProfilingConfig() throws Exception {
        return ObjectMappers.readRequiredValue(mapper,
                httpClient.get("/backend/config/fine-profiling-section"),
                FineProfilingConfigSection.class).getConfig();
    }

    // returns new version
    public String updateFineProfilingConfig(FineProfilingConfig config) throws Exception {
        return httpClient.post("/backend/config/fine-profiling", mapper.writeValueAsString(config));
    }

    public UserOverridesConfig getUserOverridesConfig() throws Exception {
        return ObjectMappers.readRequiredValue(mapper,
                httpClient.get("/backend/config/user-overrides"), UserOverridesConfig.class);
    }

    // returns new version
    public String updateUserOverridesConfig(UserOverridesConfig config) throws Exception {
        return httpClient.post("/backend/config/user-overrides", mapper.writeValueAsString(config));
    }

    public StorageConfig getStorageConfig() throws Exception {
        return ObjectMappers.readRequiredValue(mapper,
                httpClient.get("/backend/config/storage-section"),
                StorageConfigSection.class).getConfig();
    }

    // returns new version
    public String updateStorageConfig(StorageConfig config) throws Exception {
        return httpClient.post("/backend/config/storage", mapper.writeValueAsString(config));
    }

    public UserInterfaceConfig getUserInterfaceConfig() throws Exception {
        return ObjectMappers.readRequiredValue(mapper,
                httpClient.get("/backend/config/user-interface"), UserInterfaceConfig.class);
    }

    // returns new version
    public String updateUserInterfaceConfig(UserInterfaceConfig config) throws Exception {
        String response = httpClient.post("/backend/config/user-interface",
                mapper.writeValueAsString(config));
        if (response.matches("[0-9a-f]{40}")) {
            // version number
            return response;
        }
        JsonNode node = mapper.readTree(response);
        boolean currentPasswordIncorrect = node.get("currentPasswordIncorrect").asBoolean();
        if (currentPasswordIncorrect) {
            throw new CurrentPasswordIncorrectException();
        } else {
            // currently there are no other expected responses
            throw new IllegalStateException("Unexpected response: " + node);
        }
    }

    public AdvancedConfig getAdvancedConfig() throws Exception {
        return ObjectMappers.readRequiredValue(mapper,
                httpClient.get("/backend/config/advanced-section"),
                AdvancedConfigSection.class).getConfig();
    }

    // returns new version
    public String updateAdvancedConfig(AdvancedConfig config) throws Exception {
        return httpClient.post("/backend/config/advanced", mapper.writeValueAsString(config));
    }

    @Nullable
    public PluginConfig getPluginConfig(String pluginId) throws Exception {
        return ObjectMappers.readRequiredValue(mapper,
                httpClient.get("/backend/config/plugin-section"), PluginConfigSection.class)
                .getConfigs().get(pluginId);
    }

    // returns new version
    public String updatePluginConfig(String pluginId, PluginConfig config) throws Exception {
        return httpClient.post("/backend/config/plugin/" + pluginId,
                mapper.writeValueAsString(config));
    }

    public List<AdhocPointcutConfig> getAdhocPointcutConfigs() throws Exception {
        return ObjectMappers.readRequiredValue(mapper,
                httpClient.get("/backend/config/adhoc-pointcut-section"),
                AdhocPointcutConfigSection.class).getConfigs();
    }

    // returns new version
    public String addAdhocPointcutConfig(AdhocPointcutConfig adhocPointcutConfig) throws Exception {
        return httpClient.post("/backend/config/adhoc-pointcut/+",
                mapper.writeValueAsString(adhocPointcutConfig));
    }

    // returns new version
    public String updateAdhocPointcutConfig(String version, AdhocPointcutConfig adhocPointcutConfig)
            throws Exception {
        return httpClient.post("/backend/config/adhoc-pointcut/" + version,
                mapper.writeValueAsString(adhocPointcutConfig));
    }

    public void removeAdhocPointcutConfig(String version) throws Exception {
        httpClient.post("/backend/config/adhoc-pointcut/-", mapper.writeValueAsString(version));
    }

    public void reweaveAdhocPointcuts() throws Exception {
        httpClient.post("/backend/admin/adhoc-pointcuts/reweave", "");
    }

    public void compactData() throws Exception {
        httpClient.post("/backend/admin/data/compact", "");
    }

    void resetAllConfig() throws Exception {
        httpClient.post("/backend/admin/config/reset-all", "");
    }
}
