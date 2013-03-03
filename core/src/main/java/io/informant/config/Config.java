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
package io.informant.config;

import io.informant.util.GsonFactory;
import io.informant.util.JsonElements;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import checkers.igj.quals.Immutable;
import checkers.igj.quals.ReadOnly;

import com.google.common.base.Charsets;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
class Config {

    private static final Logger logger = LoggerFactory.getLogger(Config.class);
    private static final Gson gson = GsonFactory.newBuilder().disableHtmlEscaping()
            .serializeNulls().setPrettyPrinting().create();

    private static final String GENERAL = "general";
    private static final String COARSE_PROFILING = "coarse-profiling";
    private static final String FINE_PROFILING = "fine-profiling";
    private static final String USER = "user";
    private static final String PLUGINS = "plugins";
    private static final String POINTCUTS = "pointcuts";

    private final GeneralConfig generalConfig;
    private final CoarseProfilingConfig coarseProfilingConfig;
    private final FineProfilingConfig fineProfilingConfig;
    private final UserConfig userConfig;
    private final ImmutableList<PluginConfig> pluginConfigs;
    private final ImmutableList<PointcutConfig> pointcutConfigs;

    static Config fromFile(File configFile, @ReadOnly List<PluginInfo> pluginInfos)
            throws IOException, JsonSyntaxException {
        JsonObject rootConfigObject = createRootConfigObject(configFile);
        GeneralConfig generalConfig = GeneralConfig
                .fromJson(JsonElements.getOptionalObject(rootConfigObject, GENERAL));
        CoarseProfilingConfig coarseProfilingConfig = CoarseProfilingConfig
                .fromJson(JsonElements.getOptionalObject(rootConfigObject, COARSE_PROFILING));
        FineProfilingConfig fineProfilingConfig = FineProfilingConfig
                .fromJson(JsonElements.getOptionalObject(rootConfigObject, FINE_PROFILING));
        UserConfig userConfig = UserConfig.fromJson(JsonElements.getOptionalObject(
                rootConfigObject, USER));

        Map<String, JsonObject> pluginConfigObjects = createPluginConfigObjects(rootConfigObject);
        ImmutableList<PluginConfig> pluginConfigs =
                createPluginConfigs(pluginConfigObjects, pluginInfos);
        ImmutableList<PointcutConfig> pointcutConfigs = createPointcutConfigs(rootConfigObject);
        return new Config(generalConfig, coarseProfilingConfig, fineProfilingConfig, userConfig,
                pluginConfigs, pointcutConfigs);
    }

    static Config getDefault(@ReadOnly List<PluginInfo> pluginInfos) {
        ImmutableMap<String, JsonObject> pluginConfigObjects = ImmutableMap.of();
        ImmutableList<PluginConfig> pluginConfigs =
                createPluginConfigs(pluginConfigObjects, pluginInfos);
        return new Config(GeneralConfig.getDefault(), CoarseProfilingConfig.getDefault(),
                FineProfilingConfig.getDefault(), UserConfig.getDefault(),
                pluginConfigs, ImmutableList.<PointcutConfig> of());
    }

    static Builder builder(Config base) {
        return new Builder(base);
    }

    private Config(GeneralConfig generalConfig, CoarseProfilingConfig coarseProfilingConfig,
            FineProfilingConfig fineProfilingConfig, UserConfig userConfig,
            ImmutableList<PluginConfig> pluginConfigs,
            ImmutableList<PointcutConfig> pointcutConfigs) {
        this.generalConfig = generalConfig;
        this.coarseProfilingConfig = coarseProfilingConfig;
        this.fineProfilingConfig = fineProfilingConfig;
        this.userConfig = userConfig;
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

    UserConfig getUserConfig() {
        return userConfig;
    }

    ImmutableList<PluginConfig> getPluginConfigs() {
        return pluginConfigs;
    }

    ImmutableList<PointcutConfig> getPointcutConfigs() {
        return pointcutConfigs;
    }

    void writeToFileIfNeeded(File configFile) {
        JsonObject rootConfigObject = new JsonObject();
        rootConfigObject.add(GENERAL, generalConfig.toJsonWithoutVersionHash());
        rootConfigObject.add(COARSE_PROFILING, coarseProfilingConfig.toJsonWithoutVersionHash());
        rootConfigObject.add(FINE_PROFILING, fineProfilingConfig.toJsonWithoutVersionHash());
        rootConfigObject.add(USER, userConfig.toJsonWithoutVersionHash());

        JsonArray pluginsArray = new JsonArray();
        for (PluginConfig pluginConfig : pluginConfigs) {
            pluginsArray.add(pluginConfig.toJsonWithoutVersionHash());
        }
        JsonArray pointcutsArray = new JsonArray();
        for (PointcutConfig pointcutConfig : pointcutConfigs) {
            pointcutsArray.add(pointcutConfig.toJsonWithoutVersionHash());
        }
        rootConfigObject.add(PLUGINS, pluginsArray);
        rootConfigObject.add(POINTCUTS, pointcutsArray);

        String configJson = gson.toJson(rootConfigObject);
        boolean contentEqual = false;
        if (configFile.exists()) {
            try {
                String existingConfigJson = Files.toString(configFile, Charsets.UTF_8);
                contentEqual = configJson.equals(existingConfigJson);
            } catch (IOException e) {
                logger.error("error reading config.json file: " + e.getMessage(), e);
            }
        }
        if (contentEqual) {
            // it's nice to preserve the correct modification stamp on the file to track when it was
            // last really changed
            return;
        }
        try {
            Files.write(gson.toJson(rootConfigObject), configFile, Charsets.UTF_8);
        } catch (IOException e) {
            logger.error("error writing config.json file: " + e.getMessage(), e);
        }
    }

    private static JsonObject createRootConfigObject(File configFile) throws IOException,
            JsonSyntaxException {
        String configJson = Files.toString(configFile, Charsets.UTF_8);
        JsonElement jsonElement = new JsonParser().parse(configJson);
        if (jsonElement.isJsonObject()) {
            return jsonElement.getAsJsonObject();
        } else {
            throw new JsonSyntaxException("Expecting root element to be a json object");
        }
    }

    private static Map<String, JsonObject> createPluginConfigObjects(JsonObject rootConfigObject)
            throws JsonSyntaxException {
        Map<String, JsonObject> pluginConfigObjects = Maps.newHashMap();
        JsonArray pluginsJsonArray = JsonElements.getOptionalArray(rootConfigObject, PLUGINS);
        for (Iterator<JsonElement> i = pluginsJsonArray.iterator(); i.hasNext();) {
            JsonElement pluginConfigElement = i.next();
            if (!pluginConfigElement.isJsonObject()) {
                throw new JsonSyntaxException("Expecting plugin element to be a json object");
            }
            JsonObject pluginConfigObject = pluginConfigElement.getAsJsonObject();
            JsonElement groupId = pluginConfigObject.get("groupId");
            if (groupId == null) {
                logger.warn("error in config.json file, groupId is missing");
                continue;
            }
            if (!(groupId instanceof JsonPrimitive) || !((JsonPrimitive) groupId).isString()) {
                logger.warn("error in config.json file, groupId is not a json string");
                continue;
            }
            JsonElement artifactId = pluginConfigObject.get("artifactId");
            if (artifactId == null) {
                logger.warn("error in config.json file, artifactId is missing");
                continue;
            }
            if (!(artifactId instanceof JsonPrimitive)
                    || !((JsonPrimitive) artifactId).isString()) {
                logger.warn("error in config.json file, artifactId is not a json string");
                continue;
            }
            pluginConfigObjects.put(groupId.getAsString() + ":" + artifactId.getAsString(),
                    pluginConfigObject);
        }
        return pluginConfigObjects;
    }

    private static ImmutableList<PluginConfig> createPluginConfigs(
            @ReadOnly Map<String, JsonObject> pluginConfigJsonObjects,
            @ReadOnly List<PluginInfo> pluginInfos) {
        ImmutableList.Builder<PluginConfig> pluginConfigs = ImmutableList.builder();
        for (PluginInfo pluginInfo : pluginInfos) {
            JsonObject pluginConfigObject = Objects.firstNonNull(
                    pluginConfigJsonObjects.get(pluginInfo.getId()), new JsonObject());
            PluginConfig pluginConfig = PluginConfig.fromJson(pluginConfigObject, pluginInfo);
            pluginConfigs.add(pluginConfig);
        }
        return pluginConfigs.build();
    }

    private static ImmutableList<PointcutConfig> createPointcutConfigs(JsonObject rootConfigObject)
            throws JsonSyntaxException {
        ImmutableList.Builder<PointcutConfig> pointcutConfigs = ImmutableList.builder();
        JsonArray pointcutsJsonArray = JsonElements.getOptionalArray(rootConfigObject, POINTCUTS);
        for (Iterator<JsonElement> i = pointcutsJsonArray.iterator(); i.hasNext();) {
            PointcutConfig pointcutConfig = PointcutConfig.fromJson(i.next().getAsJsonObject());
            pointcutConfigs.add(pointcutConfig);
        }
        return pointcutConfigs.build();
    }

    static class Builder {

        private GeneralConfig generalConfig;
        private CoarseProfilingConfig coarseProfilingConfig;
        private FineProfilingConfig fineProfilingConfig;
        private UserConfig userConfig;
        private ImmutableList<PluginConfig> pluginConfigs;
        private ImmutableList<PointcutConfig> pointcutConfigs;

        private Builder(Config base) {
            generalConfig = base.generalConfig;
            coarseProfilingConfig = base.coarseProfilingConfig;
            fineProfilingConfig = base.fineProfilingConfig;
            userConfig = base.userConfig;
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
        Builder userConfig(UserConfig userConfig) {
            this.userConfig = userConfig;
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
                    userConfig, pluginConfigs, pointcutConfigs);
        }
    }
}
