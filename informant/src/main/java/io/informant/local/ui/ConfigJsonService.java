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
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.sql.SQLException;
import java.util.concurrent.ExecutionException;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.io.CharStreams;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.common.ObjectMappers;
import io.informant.config.AdvancedConfig;
import io.informant.config.CoarseProfilingConfig;
import io.informant.config.ConfigService;
import io.informant.config.ConfigService.OptimisticLockException;
import io.informant.config.FineProfilingConfig;
import io.informant.config.GeneralConfig;
import io.informant.config.JsonViews.UiView;
import io.informant.config.PluginConfig;
import io.informant.config.PluginDescriptorCache;
import io.informant.config.PointcutConfig;
import io.informant.config.StorageConfig;
import io.informant.config.UserInterfaceConfig;
import io.informant.config.UserInterfaceConfig.CurrentPasswordIncorrectException;
import io.informant.config.UserOverridesConfig;
import io.informant.local.store.RollingFile;
import io.informant.markers.Singleton;
import io.informant.trace.PointcutConfigAdviceCache;
import io.informant.trace.TraceModule;

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
    private final PointcutConfigAdviceCache pointcutConfigAdviceCache;
    private final HttpSessionManager httpSessionManager;
    private final TraceModule traceModule;

    // TODO address the cyclic dependency between HttpServer and ConfigJsonService created by this
    // reference
    private volatile HttpServer httpServer;

    ConfigJsonService(ConfigService configService, RollingFile rollingFile,
            PluginDescriptorCache pluginDescriptorCache, File dataDir,
            PointcutConfigAdviceCache pointcutConfigAdviceCache,
            HttpSessionManager httpSessionManager, TraceModule traceModule) {
        this.configService = configService;
        this.rollingFile = rollingFile;
        this.pluginDescriptorCache = pluginDescriptorCache;
        this.dataDir = dataDir;
        this.pointcutConfigAdviceCache = pointcutConfigAdviceCache;
        this.httpSessionManager = httpSessionManager;
        this.traceModule = traceModule;
    }

    void setHttpServer(HttpServer httpServer) {
        this.httpServer = httpServer;
    }

    @GET("/backend/config/general")
    String getGeneralConfig() throws IOException, SQLException {
        logger.debug("getGeneralConfig()");
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        ObjectWriter writer = mapper.writerWithView(UiView.class);
        jg.writeStartObject();
        jg.writeFieldName("config");
        writer.writeValue(jg, configService.getGeneralConfig());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET("/backend/config/coarse-profiling")
    String getCoarseProfilingConfig() throws IOException, SQLException {
        logger.debug("getCoarseProfilingConfig()");
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        ObjectWriter writer = mapper.writerWithView(UiView.class);
        jg.writeStartObject();
        jg.writeFieldName("config");
        writer.writeValue(jg, configService.getCoarseProfilingConfig());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET("/backend/config/fine-profiling")
    String getFineProfiling() throws IOException, SQLException {
        logger.debug("getFineProfiling()");
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

    @GET("/backend/config/user-overrides")
    String getUserOverridesConfig() throws IOException, SQLException {
        logger.debug("getUserOverridesConfig()");
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        ObjectWriter writer = mapper.writerWithView(UiView.class);
        jg.writeStartObject();
        jg.writeFieldName("config");
        writer.writeValue(jg, configService.getUserOverridesConfig());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET("/backend/config/storage")
    String getStorage() throws IOException, SQLException {
        logger.debug("getStorage()");
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

    @GET("/backend/config/user-interface")
    String getUserInterface() throws IOException, SQLException {
        logger.debug("getUserInterface()");
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        ObjectWriter writer = mapper.writerWithView(UiView.class);
        jg.writeStartObject();
        writeUserInterface(jg, writer);
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    private void writeUserInterface(JsonGenerator jg, ObjectWriter writer)
            throws IOException, JsonGenerationException, JsonMappingException {
        jg.writeFieldName("config");
        writer.writeValue(jg, configService.getUserInterfaceConfig());
        jg.writeNumberField("activePort", httpServer.getPort());
    }

    @GET("/backend/config/plugin/(.+)")
    String getPluginConfig(String pluginId) throws IOException, SQLException {
        logger.debug("getPluginConfig(): pluginId={}", pluginId);
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        ObjectWriter writer = mapper.writerWithView(UiView.class);
        jg.writeStartObject();
        jg.writeFieldName("descriptor");
        writer.writeValue(jg, pluginDescriptorCache.getPluginDescriptor(pluginId));
        jg.writeFieldName("config");
        writer.writeValue(jg, configService.getPluginConfig(pluginId));
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET("/backend/config/pointcut")
    String getPointcutConfig() throws IOException, SQLException {
        logger.debug("getPointcutConfig()");
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        ObjectWriter writer = mapper.writerWithView(UiView.class);
        jg.writeStartObject();
        jg.writeFieldName("configs");
        writer.writeValue(jg, configService.getPointcutConfigs());
        jg.writeBooleanField("jvmOutOfSync", pointcutConfigAdviceCache
                .isPointcutConfigsOutOfSync(configService.getPointcutConfigs()));
        jg.writeBooleanField("jvmRetransformClassesSupported",
                traceModule.isJvmRetransformClassesSupported());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET("/backend/config/advanced")
    String getAdvanced() throws IOException, SQLException {
        logger.debug("getAdvanced()");
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        ObjectWriter writer = mapper.writerWithView(UiView.class);
        jg.writeStartObject();
        jg.writeFieldName("config");
        writer.writeValue(jg, configService.getAdvancedConfig());
        jg.writeBooleanField("generateMetricNameWrapperMethodsActive",
                traceModule.isGenerateMetricNameWrapperMethods());
        jg.writeBooleanField("weavingDisabledActive", traceModule.isWeavingDisabled());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @POST("/backend/config/general")
    String updateGeneralConfig(String content) throws IOException, JsonServiceException,
            SQLException {
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
        try {
            configService.updateGeneralConfig(overlay.build(), priorVersion);
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(HttpResponseStatus.PRECONDITION_FAILED);
        }
        return getGeneralConfig();
    }

    @POST("/backend/config/coarse-profiling")
    String updateCoarseProfilingConfig(String content) throws JsonServiceException,
            IOException, SQLException {
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
        try {
            configService.updateCoarseProfilingConfig(overlay.build(), priorVersion);
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(HttpResponseStatus.PRECONDITION_FAILED);
        }
        return getCoarseProfilingConfig();
    }

    @POST("/backend/config/fine-profiling")
    String updateFineProfilingConfig(String content) throws JsonServiceException,
            IOException, SQLException {
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
        try {
            configService.updateFineProfilingConfig(overlay.build(), priorVersion);
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(HttpResponseStatus.PRECONDITION_FAILED);
        }
        return getFineProfiling();
    }

    @POST("/backend/config/user-overrides")
    String updateUserOverridesConfig(String content) throws JsonServiceException, IOException,
            SQLException {
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
        try {
            configService.updateUserOverridesConfig(overlay.build(), priorVersion);
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(HttpResponseStatus.PRECONDITION_FAILED);
        }
        return getUserOverridesConfig();
    }

    @POST("/backend/config/storage")
    String updateStorageConfig(String content) throws JsonServiceException, IOException,
            SQLException {
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
        try {
            configService.updateStorageConfig(overlay.build(), priorVersion);
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(HttpResponseStatus.PRECONDITION_FAILED);
        }
        // resize() doesn't do anything if the new and old value are the same
        rollingFile.resize(configService.getStorageConfig().getRollingSizeMb() * 1024);
        return getStorage();
    }

    @POST("/backend/config/user-interface")
    String updateUserInterfaceConfig(String content, HttpResponse response)
            throws JsonServiceException, IOException, NoSuchAlgorithmException,
            InvalidKeySpecException, SQLException {
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
        try {
            configService.updateUserInterfaceConfig(updatedConfig, priorVersion);
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(HttpResponseStatus.PRECONDITION_FAILED);
        }
        // only create/delete session on successful update
        if (!config.isPasswordEnabled() && updatedConfig.isPasswordEnabled()) {
            httpSessionManager.createSession(response);
        } else if (config.isPasswordEnabled() && !updatedConfig.isPasswordEnabled()) {
            httpSessionManager.deleteSession(response);
        }
        // lastly deal with ui port change
        if (config.getPort() != updatedConfig.getPort()) {
            try {
                httpServer.changePort(updatedConfig.getPort());
                response.headers().set("X-Informant-Port-Changed", "true");
            } catch (InterruptedException e) {
                return getUserInterfaceWithPortChangeFailed();
            } catch (ExecutionException e) {
                return getUserInterfaceWithPortChangeFailed();
            }
        }
        return getUserInterface();
    }

    private String getUserInterfaceWithPortChangeFailed() throws IOException {
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        ObjectWriter writer = mapper.writerWithView(UiView.class);
        jg.writeStartObject();
        writeUserInterface(jg, writer);
        jg.writeBooleanField("portChangeFailed", true);
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @POST("/backend/config/advanced")
    String updateAdvancedConfig(String content) throws JsonServiceException, IOException,
            SQLException {
        logger.debug("updateAdvancedConfig(): content={}", content);
        ObjectNode configNode = (ObjectNode) mapper.readTree(content);
        JsonNode versionNode = configNode.get("version");
        if (versionNode == null || !versionNode.isTextual()) {
            throw new IllegalStateException("Version is missing or is not a string value");
        }
        String priorVersion = versionNode.asText();
        configNode.remove("version");

        AdvancedConfig config = configService.getAdvancedConfig();
        AdvancedConfig.Overlay overlay = AdvancedConfig.overlay(config);
        mapper.readerForUpdating(overlay).readValue(configNode);
        try {
            configService.updateAdvancedConfig(overlay.build(), priorVersion);
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(HttpResponseStatus.PRECONDITION_FAILED);
        }
        return getAdvanced();
    }

    @POST("/backend/config/plugin/(.+)")
    String updatePluginConfig(String pluginId, String content) throws JsonServiceException,
            IOException, SQLException {
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
        try {
            configService.updatePluginConfig(builder.build(), priorVersion);
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(HttpResponseStatus.PRECONDITION_FAILED);
        }
        return getPluginConfig(pluginId);
    }

    @POST("/backend/config/pointcut/+")
    String addPointcutConfig(String content) throws JsonProcessingException, IOException {
        logger.debug("addPointcutConfig(): content={}", content);
        PointcutConfig pointcutConfig =
                ObjectMappers.readRequiredValue(mapper, content, PointcutConfig.class);
        configService.insertPointcutConfig(pointcutConfig);
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        ObjectWriter writer = mapper.writerWithView(UiView.class);
        writer.writeValue(jg, pointcutConfig);
        jg.close();
        return sb.toString();
    }

    @POST("/backend/config/pointcut/([0-9a-f]+)")
    String updatePointcutConfig(String priorVersion, String content)
            throws JsonProcessingException, IOException {
        logger.debug("updatePointcutConfig(): priorVersion={}, content={}", priorVersion,
                content);
        PointcutConfig pointcutConfig =
                ObjectMappers.readRequiredValue(mapper, content, PointcutConfig.class);
        configService.updatePointcutConfig(priorVersion, pointcutConfig);
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        ObjectWriter writer = mapper.writerWithView(UiView.class);
        writer.writeValue(jg, pointcutConfig);
        jg.close();
        return sb.toString();
    }

    @POST("/backend/config/pointcut/-")
    void removePointcutConfig(String content) throws IOException {
        logger.debug("removePointcutConfig(): content={}", content);
        String version = ObjectMappers.readRequiredValue(mapper, content, String.class);
        configService.deletePointcutConfig(version);
    }
}
