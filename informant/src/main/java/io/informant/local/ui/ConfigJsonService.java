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
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.sql.SQLException;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarInputStream;

import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.Maps;
import com.google.common.io.CharStreams;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.common.ObjectMappers;
import io.informant.config.AdhocPointcutConfig;
import io.informant.config.CoarseProfilingConfig;
import io.informant.config.ConfigService;
import io.informant.config.ConfigService.OptimisticLockException;
import io.informant.config.FineProfilingConfig;
import io.informant.config.GeneralConfig;
import io.informant.config.JsonViews.UiView;
import io.informant.config.PluginConfig;
import io.informant.config.PluginDescriptor;
import io.informant.config.PluginDescriptorCache;
import io.informant.config.StorageConfig;
import io.informant.config.UserInterfaceConfig;
import io.informant.config.UserInterfaceConfig.CurrentPasswordIncorrectException;
import io.informant.config.UserOverridesConfig;
import io.informant.jvm.JDK6;
import io.informant.local.store.RollingFile;
import io.informant.markers.Singleton;
import io.informant.trace.AdhocAdviceCache;

/**
 * Json service to read and update config data, bound to /backend/config.
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
    private final AdhocAdviceCache adhocAdviceCache;
    private final HttpSessionManager httpSessionManager;
    @Nullable
    private final Instrumentation instrumentation;

    ConfigJsonService(ConfigService configService, RollingFile rollingFile,
            PluginDescriptorCache pluginDescriptorCache, File dataDir,
            AdhocAdviceCache adhocAdviceCache, HttpSessionManager httpSessionManager,
            @Nullable Instrumentation instrumentation) {
        this.configService = configService;
        this.rollingFile = rollingFile;
        this.pluginDescriptorCache = pluginDescriptorCache;
        this.dataDir = dataDir;
        this.adhocAdviceCache = adhocAdviceCache;
        this.httpSessionManager = httpSessionManager;
        this.instrumentation = instrumentation;
    }

    @JsonServiceMethod
    String getGeneralConfig() throws IOException, SQLException {
        logger.debug("getGeneralConfig()");
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        ObjectWriter writer = mapper.writerWithView(UiView.class);
        writer.writeValue(jg, configService.getGeneralConfig());
        jg.close();
        return sb.toString();
    }

    @JsonServiceMethod
    String getCoarseProfilingConfig() throws IOException, SQLException {
        logger.debug("getCoarseProfilingConfig()");
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        ObjectWriter writer = mapper.writerWithView(UiView.class);
        writer.writeValue(jg, configService.getCoarseProfilingConfig());
        jg.close();
        return sb.toString();
    }

    @JsonServiceMethod
    String getFineProfilingSection() throws IOException, SQLException {
        logger.debug("getFineProfilingSection()");
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        ObjectWriter writer = mapper.writerWithView(UiView.class);
        jg.writeStartObject();
        jg.writeFieldName("config");
        writer.writeValue(jg, configService.getFineProfilingConfig());
        jg.writeNumberField("generalStoreThresholdMillis",
                configService.getGeneralConfig().getStoreThresholdMillis());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @JsonServiceMethod
    String getUserOverridesConfig() throws IOException, SQLException {
        logger.debug("getUserOverridesConfig()");
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        ObjectWriter writer = mapper.writerWithView(UiView.class);
        writer.writeValue(jg, configService.getUserOverridesConfig());
        jg.close();
        return sb.toString();
    }

    @JsonServiceMethod
    String getStorageSection() throws IOException, SQLException {
        logger.debug("getStorageSection()");
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        ObjectWriter writer = mapper.writerWithView(UiView.class);
        jg.writeStartObject();
        jg.writeFieldName("config");
        writer.writeValue(jg, configService.getStorageConfig());
        jg.writeStringField("dataDir", dataDir.getCanonicalPath());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @JsonServiceMethod
    String getUserInterface() throws IOException, SQLException {
        logger.debug("getUserInterface()");
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        ObjectWriter writer = mapper.writerWithView(UiView.class);
        writer.writeValue(jg, configService.getUserInterfaceConfig());
        jg.close();
        return sb.toString();
    }

    @JsonServiceMethod
    String getPluginSection() throws IOException, SQLException {
        logger.debug("getPluginSection()");
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        ObjectWriter writer = mapper.writerWithView(UiView.class);
        jg.writeStartObject();
        jg.writeFieldName("descriptors");
        writer.writeValue(jg, pluginDescriptorCache.getPluginDescriptors());
        jg.writeFieldName("configs");
        writer.writeValue(jg, getPluginConfigMap());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @JsonServiceMethod
    String getAdhocPointcutSection() throws IOException, SQLException {
        logger.debug("getAdhocPointcutSection()");
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        ObjectWriter writer = mapper.writerWithView(UiView.class);
        jg.writeStartObject();
        jg.writeFieldName("configs");
        writer.writeValue(jg, configService.getAdhocPointcutConfigs());
        jg.writeBooleanField("jvmOutOfSync", adhocAdviceCache
                .isAdhocPointcutConfigsOutOfSync(configService.getAdhocPointcutConfigs()));
        if (instrumentation == null) {
            // debugging with IsolatedWeavingClassLoader instead of javaagent
            jg.writeBooleanField("jvmRetransformClassesSupported", false);
        } else {
            jg.writeBooleanField("jvmRetransformClassesSupported",
                    JDK6.isRetransformClassesSupported(instrumentation));
        }
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @JsonServiceMethod
    String getVersion() throws IOException {
        logger.debug("getVersion()");
        JarInputStream jarIn = new JarInputStream(ConfigJsonService.class.getProtectionDomain()
                .getCodeSource().getLocation().openStream());
        try {
            Attributes m = jarIn.getManifest().getMainAttributes();
            String version = m.getValue("Implementation-Version");
            if (version == null) {
                logger.warn("could not find Implementation-Version attribute in"
                        + " META-INF/MANIFEST.MF file");
                return "<unknown>";
            }
            if (version.endsWith("-SNAPSHOT")) {
                String snapshotTimestamp = m.getValue("Build-Time");
                if (snapshotTimestamp == null) {
                    logger.warn("could not find Build-Time attribute in META-INF/MANIFEST.MF file");
                    return version + " (<timestamp unknown>)";
                }
                return version + " (" + snapshotTimestamp + ")";
            }
            return version;
        } finally {
            jarIn.close();
        }
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
        GeneralConfig.Overlay overlay = GeneralConfig.overlay(config);
        mapper.readerForUpdating(overlay).readValue(configNode);
        return configService.updateGeneralConfig(overlay.build(), priorVersion);
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

        CoarseProfilingConfig config = configService.getCoarseProfilingConfig();
        CoarseProfilingConfig.Overlay overlay = CoarseProfilingConfig.overlay(config);
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

        FineProfilingConfig config = configService.getFineProfilingConfig();
        FineProfilingConfig.Overlay overlay = FineProfilingConfig.overlay(config);
        mapper.readerForUpdating(overlay).readValue(configNode);
        return configService.updateFineProfilingConfig(overlay.build(), priorVersion);
    }

    @JsonServiceMethod
    String updateUserOverridesConfig(String content) throws OptimisticLockException, IOException {
        logger.debug("updateUserOverridesConfig(): content={}", content);
        ObjectNode configNode = (ObjectNode) mapper.readTree(content);
        JsonNode versionNode = configNode.get("version");
        if (versionNode == null || !versionNode.isTextual()) {
            throw new IllegalStateException("Version is missing or is not a string value");
        }
        String priorVersion = versionNode.asText();
        configNode.remove("version");

        UserOverridesConfig config = configService.getUserOverridesConfig();
        UserOverridesConfig.Overlay overlay = UserOverridesConfig.overlay(config);
        mapper.readerForUpdating(overlay).readValue(configNode);
        return configService.updateUserOverridesConfig(overlay.build(), priorVersion);
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
        StorageConfig.Overlay overlay = StorageConfig.overlay(config);
        mapper.readerForUpdating(overlay).readValue(configNode);
        String updatedVersion = configService.updateStorageConfig(overlay.build(), priorVersion);
        // resize() doesn't do anything if the new and old value are the same
        rollingFile.resize(configService.getStorageConfig().getRollingSizeMb() * 1024);
        return updatedVersion;
    }

    @JsonServiceMethod
    String updateUserInterfaceConfig(String content, HttpResponse response)
            throws OptimisticLockException,
            IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        logger.debug("updateUserInterfaceConfig(): content={}", content);
        ObjectNode configNode = (ObjectNode) mapper.readTree(content);
        JsonNode versionNode = configNode.get("version");
        if (versionNode == null || !versionNode.isTextual()) {
            throw new IllegalStateException("Version is missing or is not a string value");
        }
        String priorVersion = versionNode.asText();
        configNode.remove("version");

        UserInterfaceConfig config = configService.getUserInterfaceConfig();
        UserInterfaceConfig.Overlay overlay = UserInterfaceConfig.overlay(config);
        mapper.readerForUpdating(overlay).readValue(configNode);
        UserInterfaceConfig updatedConfig;
        try {
            updatedConfig = overlay.build();
        } catch (CurrentPasswordIncorrectException e) {
            return "{\"currentPasswordIncorrect\":true}";
        }
        String updatedVersion =
                configService.updateUserInterfaceConfig(updatedConfig, priorVersion);
        // only create/delete session on successful update
        if (!config.isPasswordEnabled() && updatedConfig.isPasswordEnabled()) {
            httpSessionManager.createSession(response);
        } else if (config.isPasswordEnabled() && !updatedConfig.isPasswordEnabled()) {
            httpSessionManager.deleteSession(response);
        }
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
    String addAdhocPointcutConfig(String content) throws JsonProcessingException, IOException {
        logger.debug("addAdhocPointcutConfig(): content={}", content);
        AdhocPointcutConfig adhocPointcutConfig =
                ObjectMappers.readRequiredValue(mapper, content, AdhocPointcutConfig.class);
        return configService.insertAdhocPointcutConfig(adhocPointcutConfig);
    }

    @JsonServiceMethod
    String updateAdhocPointcutConfig(String priorVersion, String content)
            throws JsonProcessingException, IOException {
        logger.debug("updateAdhocPointcutConfig(): priorVersion={}, content={}", priorVersion,
                content);
        AdhocPointcutConfig adhocPointcutConfig =
                ObjectMappers.readRequiredValue(mapper, content, AdhocPointcutConfig.class);
        return configService.updateAdhocPointcutConfig(priorVersion, adhocPointcutConfig);
    }

    @JsonServiceMethod
    void removeAdhocPointcutConfig(String content) throws IOException {
        logger.debug("removeAdhocPointcutConfig(): content={}", content);
        String version = ObjectMappers.readRequiredValue(mapper, content, String.class);
        configService.deleteAdhocPointcutConfig(version);
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
