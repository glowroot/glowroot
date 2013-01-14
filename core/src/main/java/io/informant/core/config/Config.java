/**
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
package io.informant.core.config;

import io.informant.api.Logger;
import io.informant.api.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import com.google.common.base.Charsets;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
class Config {

    private static final Logger logger = LoggerFactory.getLogger(Config.class);
    private static final Gson gson = new GsonBuilder().disableHtmlEscaping().serializeNulls()
            .setPrettyPrinting().create();

    private static final String GENERAL = "general";
    private static final String COARSE_PROFILING = "coarse-profiling";
    private static final String FINE_PROFILING = "fine-profiling";
    private static final String USER = "user";
    private static final String PLUGINS = "plugins";

    private final GeneralConfig generalConfig;
    private final CoarseProfilingConfig coarseProfilingConfig;
    private final FineProfilingConfig fineProfilingConfig;
    private final UserConfig userConfig;
    private final ImmutableList<PluginConfig> pluginConfigs;

    static Config fromFile(File configFile) {
        JsonObject rootJsonObject;
        if (configFile.exists()) {
            try {
                String configJson = Files.toString(configFile, Charsets.UTF_8);
                JsonElement jsonElement = new JsonParser().parse(configJson);
                rootJsonObject = asJsonObject(jsonElement);
            } catch (IOException e) {
                logger.error("error reading config.json file", e);
                rootJsonObject = new JsonObject();
            }
        } else {
            rootJsonObject = new JsonObject();
        }
        GeneralConfig generalConfig = GeneralConfig
                .fromJson(asJsonObject(rootJsonObject.get(GENERAL)));
        CoarseProfilingConfig coarseProfilingConfig = CoarseProfilingConfig
                .fromJson(asJsonObject(rootJsonObject.get(COARSE_PROFILING)));
        FineProfilingConfig fineProfilingConfig = FineProfilingConfig
                .fromJson(asJsonObject(rootJsonObject.get(FINE_PROFILING)));
        UserConfig userConfig = UserConfig.fromJson(asJsonObject(rootJsonObject.get(USER)));

        Map<String, JsonObject> pluginConfigJsonObjects = Maps.newHashMap();
        JsonArray pluginsJsonArray = asJsonArray(rootJsonObject.get(PLUGINS));
        for (Iterator<JsonElement> i = pluginsJsonArray.iterator(); i.hasNext();) {
            JsonObject pluginConfigJsonObject = i.next().getAsJsonObject();
            String groupId = pluginConfigJsonObject.get("groupId").getAsString();
            String artifactId = pluginConfigJsonObject.get("artifactId").getAsString();
            pluginConfigJsonObjects.put(groupId + ":" + artifactId, pluginConfigJsonObject);
        }
        Iterable<PluginDescriptor> pluginDescriptors = Iterables.concat(
                Plugins.getPackagedPluginDescriptors(), Plugins.getInstalledPluginDescriptors());
        ImmutableList.Builder<PluginConfig> pluginConfigs = ImmutableList.builder();
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            JsonObject pluginConfigJsonObject = Objects.firstNonNull(
                    pluginConfigJsonObjects.get(pluginDescriptor.getId()), new JsonObject());
            PluginConfig pluginConfig = PluginConfig.fromJson(pluginConfigJsonObject,
                    pluginDescriptor);
            pluginConfigs.add(pluginConfig);
        }

        return new Config(generalConfig, coarseProfilingConfig, fineProfilingConfig, userConfig,
                pluginConfigs.build());
    }

    static Builder builder(Config base) {
        return new Builder(base);
    }

    private Config(GeneralConfig generalConfig, CoarseProfilingConfig coarseProfilingConfig,
            FineProfilingConfig fineProfilingConfig, UserConfig userConfig,
            ImmutableList<PluginConfig> pluginConfigs) {
        this.generalConfig = generalConfig;
        this.coarseProfilingConfig = coarseProfilingConfig;
        this.fineProfilingConfig = fineProfilingConfig;
        this.userConfig = userConfig;
        this.pluginConfigs = pluginConfigs;
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

    ImmutableList<PluginConfig> getPluginConfigs() {
        return pluginConfigs;
    }

    void writeToFileIfNeeded(File configFile) {
        JsonObject rootJsonObject = new JsonObject();
        rootJsonObject.add(GENERAL, generalConfig.toJson());
        rootJsonObject.add(COARSE_PROFILING, coarseProfilingConfig.toJson());
        rootJsonObject.add(FINE_PROFILING, fineProfilingConfig.toJson());
        rootJsonObject.add(USER, userConfig.toJson());

        JsonArray pluginsJsonArray = new JsonArray();
        for (PluginConfig pluginConfig : pluginConfigs) {
            pluginsJsonArray.add(pluginConfig.toJson());
        }
        rootJsonObject.add(PLUGINS, pluginsJsonArray);

        String configJson = gson.toJson(rootJsonObject);
        boolean contentEqual = false;
        if (configFile.exists()) {
            try {
                String existingConfigJson = Files.toString(configFile, Charsets.UTF_8);
                contentEqual = configJson.equals(existingConfigJson);
            } catch (IOException e) {
                logger.error("error reading config.json file", e);
            }
        }
        if (contentEqual) {
            // it's nice to preserve the correct modification stamp on the file to track when it was
            // last really changed
            return;
        }
        try {
            Files.write(gson.toJson(rootJsonObject), configFile, Charsets.UTF_8);
        } catch (IOException e) {
            logger.error("error writing config.json file", e);
        }
    }

    private static JsonObject asJsonObject(@Nullable JsonElement jsonElement) {
        if (jsonElement == null) {
            return new JsonObject();
        } else if (jsonElement.isJsonObject()) {
            return jsonElement.getAsJsonObject();
        } else {
            logger.warn("error in config.json file, expecting json object but found: {}",
                    jsonElement);
            return new JsonObject();
        }
    }

    private static JsonArray asJsonArray(@Nullable JsonElement jsonElement) {
        if (jsonElement == null) {
            return new JsonArray();
        } else if (jsonElement.isJsonArray()) {
            return jsonElement.getAsJsonArray();
        } else {
            logger.warn("error in config.json file, expecting json array but found: {}",
                    jsonElement);
            return new JsonArray();
        }
    }

    static class Builder {

        private GeneralConfig generalConfig;
        private CoarseProfilingConfig coarseProfilingConfig;
        private FineProfilingConfig fineProfilingConfig;
        private UserConfig userConfig;
        private ImmutableList<PluginConfig> pluginConfigs;

        private Builder(Config base) {
            generalConfig = base.generalConfig;
            coarseProfilingConfig = base.coarseProfilingConfig;
            fineProfilingConfig = base.fineProfilingConfig;
            userConfig = base.userConfig;
            pluginConfigs = base.pluginConfigs;
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
        Builder userConfig(UserConfig userConfig) {
            this.userConfig = userConfig;
            return this;
        }
        Builder pluginConfigs(ImmutableList<PluginConfig> pluginConfigs) {
            this.pluginConfigs = pluginConfigs;
            return this;
        }
        Config build() {
            return new Config(generalConfig, coarseProfilingConfig, fineProfilingConfig,
                    userConfig, pluginConfigs);
        }
    }
}
