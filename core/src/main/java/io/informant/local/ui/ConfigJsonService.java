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
package io.informant.local.ui;

import io.informant.api.Logger;
import io.informant.api.LoggerFactory;
import io.informant.core.config.CoarseProfilingConfig;
import io.informant.core.config.ConfigService;
import io.informant.core.config.FineProfilingConfig;
import io.informant.core.config.GeneralConfig;
import io.informant.core.config.PluginConfig;
import io.informant.core.config.PluginInfo;
import io.informant.core.config.PluginInfoCache;
import io.informant.core.config.PointcutConfig;
import io.informant.core.config.UserConfig;
import io.informant.core.util.GsonFactory;
import io.informant.core.util.RollingFile;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;

/**
 * Json service to read config data.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class ConfigJsonService implements JsonService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigJsonService.class);
    private static final Gson gson = GsonFactory.create();

    private final ConfigService configService;
    private final RollingFile rollingFile;
    private final PluginInfoCache pluginInfoCache;
    private final File dataDir;
    private final int uiPort;

    @Inject
    ConfigJsonService(ConfigService configService, RollingFile rollingFile,
            PluginInfoCache pluginInfoCache, @Named("data.dir") File dataDir,
            @Named("ui.port") int uiPort) {
        this.configService = configService;
        this.rollingFile = rollingFile;
        this.pluginInfoCache = pluginInfoCache;
        this.dataDir = dataDir;
        this.uiPort = uiPort;
    }

    @JsonServiceMethod
    String getConfig() throws IOException, SQLException {
        logger.debug("getConfig()");
        JsonObject configJson = new JsonObject();
        configJson.add("generalConfig", configService.getGeneralConfig().toJson());
        configJson.add("coarseProfilingConfig", configService.getCoarseProfilingConfig().toJson());
        configJson.add("fineProfilingConfig", configService.getFineProfilingConfig().toJson());
        configJson.add("userConfig", configService.getUserConfig().toJson());
        configJson.add("pluginInfos", gson.toJsonTree(pluginInfoCache.getPluginInfos()));
        configJson.add("pluginConfigs", getPluginConfigMapObject());
        configJson.add("pointcutConfigs", getPoincutConfigArray());
        configJson.addProperty("dataDir", dataDir.getCanonicalPath());
        configJson.addProperty("uiPort", uiPort);
        return configJson.toString();
    }

    @JsonServiceMethod
    void updateGeneralConfig(String configJson) {
        logger.debug("updateGeneralConfig(): configJson={}", configJson);
        JsonObject configObject = new JsonParser().parse(configJson).getAsJsonObject();
        GeneralConfig config = configService.getGeneralConfig();
        GeneralConfig.Builder builder = GeneralConfig.builder(config);
        builder.overlay(configObject);
        configService.updateGeneralConfig(builder.build());
        try {
            // resize() doesn't do anything if the new and old value are the same
            rollingFile.resize(configService.getGeneralConfig().getRollingSizeMb() * 1024);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            // TODO return HTTP 500 Internal Server Error?
            return;
        }
    }

    @JsonServiceMethod
    void updateCoarseProfilingConfig(String configJson) {
        logger.debug("updateCoarseProfilingConfig(): configJson={}", configJson);
        JsonObject configObject = new JsonParser().parse(configJson).getAsJsonObject();
        CoarseProfilingConfig config = configService.getCoarseProfilingConfig();
        CoarseProfilingConfig.Builder builder = CoarseProfilingConfig.builder(config);
        builder.overlay(configObject);
        configService.updateCoarseProfilingConfig(builder.build());
    }

    @JsonServiceMethod
    void updateFineProfilingConfig(String configJson) {
        logger.debug("updateFineProfilingConfig(): configJson={}", configJson);
        JsonObject configObject = new JsonParser().parse(configJson).getAsJsonObject();
        FineProfilingConfig config = configService.getFineProfilingConfig();
        FineProfilingConfig.Builder builder = FineProfilingConfig.builder(config);
        builder.overlay(configObject);
        configService.updateFineProfilingConfig(builder.build());
    }

    @JsonServiceMethod
    void updateUserConfig(String configJson) {
        logger.debug("updateUserConfig(): configJson={}", configJson);
        JsonObject configObject = new JsonParser().parse(configJson).getAsJsonObject();
        UserConfig config = configService.getUserConfig();
        UserConfig.Builder builder = UserConfig.builder(config);
        builder.overlay(configObject);
        configService.updateUserConfig(builder.build());
    }

    @JsonServiceMethod
    void updatePluginConfig(String pluginId, String configJson) {
        logger.debug("updatePluginConfig(): pluginId={}, configJson={}", pluginId, configJson);
        JsonObject configObject = new JsonParser().parse(configJson).getAsJsonObject();
        PluginConfig config = configService.getPluginConfig(pluginId);
        if (config == null) {
            logger.warn("plugin id '{}' not found", pluginId);
            return;
        }
        PluginConfig.Builder builder = PluginConfig.builder(config);
        builder.overlay(configObject);
        configService.updatePluginConfig(builder.build());
    }

    @JsonServiceMethod
    String addPointcutConfig(String configJson) throws JsonSyntaxException {
        logger.debug("addPointcutConfig(): configJson={}", configJson);
        PointcutConfig pointcut = gson.fromJson(configJson, PointcutConfig.Builder.class).build();
        String uniqueHash = configService.insertPointcutConfig(pointcut);
        return gson.toJson(uniqueHash);
    }

    @JsonServiceMethod
    String updatePointcutConfig(String previousUniqueHash, String configJson)
            throws JsonSyntaxException {
        logger.debug("updatePointcutConfig(): previousUniqueHash={}, configJson={}",
                previousUniqueHash, configJson);
        PointcutConfig pointcutConfig = gson.fromJson(configJson, PointcutConfig.Builder.class)
                .build();
        String updatedUniqueHash = configService.updatePointcutConfig(previousUniqueHash,
                pointcutConfig);
        return gson.toJson(updatedUniqueHash);
    }

    @JsonServiceMethod
    void removePointcutConfig(String uniqueHashJson) {
        logger.debug("removePointcutConfig(): uniqueHashJson={}", uniqueHashJson);
        configService.deletePointcutConfig(new JsonParser().parse(uniqueHashJson).getAsString());
    }

    private JsonObject getPluginConfigMapObject() {
        JsonObject mapObject = new JsonObject();
        for (PluginInfo pluginInfo : pluginInfoCache.getPluginInfos()) {
            PluginConfig pluginConfig = configService.getPluginConfig(pluginInfo.getId());
            if (pluginConfig == null) {
                throw new IllegalStateException("Plugin config not found for plugin id '"
                        + pluginInfo.getId() + "'");
            }
            mapObject.add(pluginInfo.getId(), pluginConfig.toJson());
        }
        return mapObject;
    }

    private JsonArray getPoincutConfigArray() {
        JsonArray jsonArray = new JsonArray();
        for (PointcutConfig pointcutConfig : configService.readPointcutConfigs()) {
            jsonArray.add(pointcutConfig.toJsonWithUniqueHash());
        }
        return jsonArray;
    }
}
