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
package io.informant.local.ui;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.sql.SQLException;
import java.util.Map;

import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.Maps;
import com.google.common.io.CharStreams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.common.ObjectMappers;
import io.informant.config.CoarseProfilingConfig;
import io.informant.config.ConfigService;
import io.informant.config.ConfigService.OptimisticLockException;
import io.informant.config.FineProfilingConfig;
import io.informant.config.GeneralConfig;
import io.informant.config.PluginConfig;
import io.informant.config.PluginDescriptor;
import io.informant.config.PluginDescriptorCache;
import io.informant.config.PointcutConfig;
import io.informant.config.StorageConfig;
import io.informant.config.UserConfig;
import io.informant.config.WithVersionJsonView;
import io.informant.dynamicadvice.DynamicAdviceCache;
import io.informant.dynamicadvice.RetransformClasses;
import io.informant.local.store.RollingFile;
import io.informant.markers.Singleton;

/**
 * Json service to read config data.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
@JsonService
class ConfigJsonService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigJsonService.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private final ConfigService configService;
    private final RollingFile rollingFile;
    private final PluginDescriptorCache pluginDescriptorCache;
    private final File dataDir;
    private final DynamicAdviceCache dynamicAdviceCache;
    @Nullable
    private final Instrumentation instrumentation;

    ConfigJsonService(ConfigService configService, RollingFile rollingFile,
            PluginDescriptorCache pluginDescriptorCache, File dataDir,
            DynamicAdviceCache dynamicAdviceCache, @Nullable Instrumentation instrumentation) {
        this.configService = configService;
        this.rollingFile = rollingFile;
        this.pluginDescriptorCache = pluginDescriptorCache;
        this.dataDir = dataDir;
        this.dynamicAdviceCache = dynamicAdviceCache;
        this.instrumentation = instrumentation;
    }

    @JsonServiceMethod
    String getConfig() throws IOException, SQLException {
        logger.debug("getConfig()");
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        ObjectWriter writer = mapper.writerWithView(WithVersionJsonView.class);
        jg.writeStartObject();
        jg.writeFieldName("generalConfig");
        writer.writeValue(jg, configService.getGeneralConfig());
        jg.writeFieldName("coarseProfilingConfig");
        writer.writeValue(jg, configService.getCoarseProfilingConfig());
        jg.writeFieldName("fineProfilingConfig");
        writer.writeValue(jg, configService.getFineProfilingConfig());
        jg.writeFieldName("userConfig");
        writer.writeValue(jg, configService.getUserConfig());
        jg.writeFieldName("storageConfig");
        writer.writeValue(jg, configService.getStorageConfig());
        jg.writeFieldName("pluginDescriptors");
        writer.writeValue(jg, pluginDescriptorCache.getPluginDescriptors());
        jg.writeFieldName("pluginConfigs");
        writer.writeValue(jg, getPluginConfigMap());
        jg.writeStringField("dataDir", dataDir.getCanonicalPath());
        jg.writeFieldName("pointcutConfigs");
        writer.writeValue(jg, configService.getPointcutConfigs());
        jg.writeBooleanField("pointcutConfigsOutOfSync",
                dynamicAdviceCache.isPointcutConfigsOutOfSync(configService.getPointcutConfigs()));
        if (instrumentation == null) {
            // debugging with IsolatedWeavingClassLoader instead of javaagent
            jg.writeBooleanField("retransformClassesSupported", false);
        } else {
            jg.writeBooleanField("retransformClassesSupported",
                    RetransformClasses.isRetransformClassesSupported(instrumentation));
        }
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @JsonServiceMethod
    String updateGeneralConfig(String content) throws OptimisticLockException, IOException {
        logger.debug("updateGeneralConfig(): content={}", content);
        ObjectNode configNode = (ObjectNode) mapper.readTree(content);
        JsonNode versionNode = configNode.get("version");
        if (versionNode == null || !versionNode.isTextual()) {
            throw new IllegalStateException("Version is missing or is not a string value");
        }
        String priorVersion = versionNode.asText();
        configNode.remove("version");

        GeneralConfig config = configService.getGeneralConfig();
        GeneralConfig.Overlay builder = GeneralConfig.overlay(config);
        mapper.readerForUpdating(builder).readValue(configNode);
        return configService.updateGeneralConfig(builder.build(), priorVersion);
    }

    @JsonServiceMethod
    String updateCoarseProfilingConfig(String content) throws OptimisticLockException,
            IOException {
        logger.debug("updateCoarseProfilingConfig(): content={}", content);
        ObjectNode configNode = (ObjectNode) mapper.readTree(content);
        JsonNode versionNode = configNode.get("version");
        if (versionNode == null || !versionNode.isTextual()) {
            throw new IllegalStateException("Version is missing or is not a string value");
        }
        String priorVersion = versionNode.asText();
        configNode.remove("version");

        CoarseProfilingConfig.Overlay overlay =
                CoarseProfilingConfig.overlay(configService.getCoarseProfilingConfig());
        mapper.readerForUpdating(overlay).readValue(configNode);

        return configService.updateCoarseProfilingConfig(overlay.build(), priorVersion);
    }

    @JsonServiceMethod
    String updateFineProfilingConfig(String content) throws OptimisticLockException,
            IOException {
        logger.debug("updateFineProfilingConfig(): content={}", content);
        ObjectNode configNode = (ObjectNode) mapper.readTree(content);
        JsonNode versionNode = configNode.get("version");
        if (versionNode == null || !versionNode.isTextual()) {
            throw new IllegalStateException("Version is missing or is not a string value");
        }
        String priorVersion = versionNode.asText();
        configNode.remove("version");

        FineProfilingConfig.Overlay overlay =
                FineProfilingConfig.overlay(configService.getFineProfilingConfig());
        mapper.readerForUpdating(overlay).readValue(configNode);

        return configService.updateFineProfilingConfig(overlay.build(), priorVersion);
    }

    @JsonServiceMethod
    String updateUserConfig(String content) throws OptimisticLockException, IOException {
        logger.debug("updateUserConfig(): content={}", content);
        ObjectNode configNode = (ObjectNode) mapper.readTree(content);
        JsonNode versionNode = configNode.get("version");
        if (versionNode == null || !versionNode.isTextual()) {
            throw new IllegalStateException("Version is missing or is not a string value");
        }
        String priorVersion = versionNode.asText();
        configNode.remove("version");

        UserConfig.Overlay overlay = UserConfig.overlay(configService.getUserConfig());
        mapper.readerForUpdating(overlay).readValue(configNode);

        return configService.updateUserConfig(overlay.build(), priorVersion);
    }

    @JsonServiceMethod
    String updateStorageConfig(String content) throws OptimisticLockException, IOException {
        logger.debug("updateStorageConfig(): content={}", content);
        ObjectNode configNode = (ObjectNode) mapper.readTree(content);
        JsonNode versionNode = configNode.get("version");
        if (versionNode == null || !versionNode.isTextual()) {
            throw new IllegalStateException("Version is missing or is not a string value");
        }
        String priorVersion = versionNode.asText();
        configNode.remove("version");

        StorageConfig config = configService.getStorageConfig();
        StorageConfig.Overlay builder = StorageConfig.overlay(config);
        mapper.readerForUpdating(builder).readValue(configNode);
        String updatedVersion = configService.updateStorageConfig(builder.build(), priorVersion);
        // resize() doesn't do anything if the new and old value are the same
        rollingFile.resize(configService.getStorageConfig().getRollingSizeMb() * 1024);
        return updatedVersion;
    }

    @JsonServiceMethod
    String updatePluginConfig(String pluginId, String content) throws OptimisticLockException,
            IOException {
        logger.debug("updatePluginConfig(): pluginId={}, content={}", pluginId, content);
        ObjectNode configNode = (ObjectNode) mapper.readTree(content);
        JsonNode versionNode = configNode.get("version");
        if (versionNode == null || !versionNode.isTextual()) {
            throw new IllegalStateException("Version is missing or is not a string value");
        }
        String priorVersion = versionNode.asText();
        PluginConfig config = configService.getPluginConfig(pluginId);
        if (config == null) {
            throw new IllegalArgumentException("Plugin id '" + pluginId + "' not found");
        }
        PluginConfig.Builder builder = PluginConfig.builder(config);
        builder.overlay(configNode);
        return configService.updatePluginConfig(builder.build(), priorVersion);
    }

    @JsonServiceMethod
    String addPointcutConfig(String content) throws JsonProcessingException, IOException {
        logger.debug("addPointcutConfig(): content={}", content);
        PointcutConfig pointcutConfig =
                ObjectMappers.readRequiredValue(mapper, content, PointcutConfig.class);
        return configService.insertPointcutConfig(pointcutConfig);
    }

    @JsonServiceMethod
    String updatePointcutConfig(String priorVersion, String content)
            throws JsonProcessingException, IOException {
        logger.debug("updatePointcutConfig(): priorVersion={}, content={}", priorVersion,
                content);
        PointcutConfig pointcutConfig =
                ObjectMappers.readRequiredValue(mapper, content, PointcutConfig.class);
        return configService.updatePointcutConfig(priorVersion, pointcutConfig);
    }

    @JsonServiceMethod
    void removePointcutConfig(String content) throws IOException {
        logger.debug("removePointcutConfig(): content={}", content);
        String version = ObjectMappers.readRequiredValue(mapper, content, String.class);
        configService.deletePointcutConfig(version);
    }

    private Map<String, PluginConfig> getPluginConfigMap() {
        Map<String, PluginConfig> pluginConfigMap = Maps.newHashMap();
        for (PluginDescriptor pluginDescriptor : pluginDescriptorCache.getPluginDescriptors()) {
            PluginConfig pluginConfig = configService.getPluginConfig(pluginDescriptor.getId());
            if (pluginConfig == null) {
                throw new IllegalStateException("Plugin config not found for plugin id '"
                        + pluginDescriptor.getId() + "'");
            }
            pluginConfigMap.put(pluginDescriptor.getId(), pluginConfig);
        }
        return pluginConfigMap;
    }
}
