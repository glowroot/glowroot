/*
 * Copyright 2013-2015 the original author or authors.
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
package org.glowroot.container.config;

import java.util.List;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.Lists;

import org.glowroot.container.common.HttpClient;
import org.glowroot.container.common.ObjectMappers;

public class ConfigService {

    private static final ObjectMapper mapper = ObjectMappers.create();

    private final HttpClient httpClient;
    private final GetUiPortCommand getUiPortCommand;

    public ConfigService(HttpClient httpClient, GetUiPortCommand getUiPortCommand) {
        this.httpClient = httpClient;
        this.getUiPortCommand = getUiPortCommand;
    }

    public void setPluginProperty(String pluginId, String propertyName,
            @Nullable Object propertyValue) throws Exception {
        PluginConfig config = getPluginConfig(pluginId);
        if (config == null) {
            throw new IllegalStateException("Plugin not found for pluginId: " + pluginId);
        }
        config.setProperty(propertyName, propertyValue);
        updatePluginConfig(pluginId, config);
    }

    public TraceConfig getTraceConfig() throws Exception {
        return getConfig("/backend/config/trace", TraceConfig.class);
    }

    public void updateTraceConfig(TraceConfig config) throws Exception {
        httpClient.post("/backend/config/trace", mapper.writeValueAsString(config));
    }

    public ProfilingConfig getProfilingConfig() throws Exception {
        return getConfig("/backend/config/profiling", ProfilingConfig.class);
    }

    public void updateProfilingConfig(ProfilingConfig config) throws Exception {
        httpClient.post("/backend/config/profiling", mapper.writeValueAsString(config));
    }

    public UserRecordingConfig getUserRecordingConfig() throws Exception {
        return getConfig("/backend/config/user-recording", UserRecordingConfig.class);
    }

    public void updateUserRecordingConfig(UserRecordingConfig config) throws Exception {
        httpClient.post("/backend/config/user-recording", mapper.writeValueAsString(config));
    }

    public StorageConfig getStorageConfig() throws Exception {
        return getConfig("/backend/config/storage", StorageConfig.class);
    }

    public void updateStorageConfig(StorageConfig config) throws Exception {
        httpClient.post("/backend/config/storage", mapper.writeValueAsString(config));
    }

    public UserInterfaceConfig getUserInterfaceConfig() throws Exception {
        return getConfig("/backend/config/user-interface", UserInterfaceConfig.class);
    }

    // throws CurrentPasswordIncorrectException
    public void updateUserInterfaceConfig(UserInterfaceConfig config) throws Exception {
        String response = httpClient.post("/backend/config/user-interface",
                mapper.writeValueAsString(config));
        JsonNode node = mapper.readTree(response);
        JsonNode currentPasswordIncorrectNode = node.get("currentPasswordIncorrect");
        if (currentPasswordIncorrectNode != null && currentPasswordIncorrectNode.asBoolean()) {
            throw new CurrentPasswordIncorrectException();
        }
        JsonNode portChangeFailedNode = node.get("portChangeFailed");
        if (portChangeFailedNode != null && portChangeFailedNode.asBoolean()) {
            throw new PortChangeFailedException();
        }
        httpClient.updateUiPort(getUiPortCommand.getUiPort());
    }

    public AdvancedConfig getAdvancedConfig() throws Exception {
        return getConfig("/backend/config/advanced", AdvancedConfig.class);
    }

    public void updateAdvancedConfig(AdvancedConfig config) throws Exception {
        httpClient.post("/backend/config/advanced", mapper.writeValueAsString(config));
    }

    public @Nullable PluginConfig getPluginConfig(String pluginId) throws Exception {
        return getConfig("/backend/config/plugin/" + pluginId, PluginConfig.class);
    }

    public void updatePluginConfig(String pluginId, PluginConfig config) throws Exception {
        httpClient.post("/backend/config/plugin/" + pluginId, mapper.writeValueAsString(config));
    }

    public List<Gauge> getGauges() throws Exception {
        String response = httpClient.get("/backend/config/gauges");
        ArrayNode rootNode = ObjectMappers.readRequiredValue(mapper, response, ArrayNode.class);
        List<Gauge> configs = Lists.newArrayList();
        for (JsonNode childNode : rootNode) {
            ObjectNode configNode =
                    (ObjectNode) ObjectMappers.getRequiredChildNode(childNode, "config");
            configs.add(mapper.readValue(mapper.treeAsTokens(configNode), Gauge.class));
        }
        return configs;
    }

    // returns new version
    public Gauge addGauge(Gauge gauge) throws Exception {
        String response = httpClient.post("/backend/config/gauges/add",
                mapper.writeValueAsString(gauge));
        ObjectNode rootNode = ObjectMappers.readRequiredValue(mapper, response, ObjectNode.class);
        ObjectNode configNode = (ObjectNode) ObjectMappers.getRequiredChildNode(rootNode, "config");
        return mapper.readValue(mapper.treeAsTokens(configNode), Gauge.class);
    }

    public Gauge updateGauge(Gauge gauge) throws Exception {
        String response = httpClient.post("/backend/config/gauges/update",
                mapper.writeValueAsString(gauge));
        ObjectNode rootNode = ObjectMappers.readRequiredValue(mapper, response, ObjectNode.class);
        ObjectNode configNode = (ObjectNode) ObjectMappers.getRequiredChildNode(rootNode, "config");
        return mapper.readValue(mapper.treeAsTokens(configNode), Gauge.class);
    }

    public void removeGauge(String version) throws Exception {
        httpClient.post("/backend/config/gauges/remove", mapper.writeValueAsString(version));
    }

    public List<CapturePoint> getCapturePoints() throws Exception {
        String response = httpClient.get("/backend/config/capture-points");
        ObjectNode rootNode = ObjectMappers.readRequiredValue(mapper, response, ObjectNode.class);
        JsonNode configsNode = ObjectMappers.getRequiredChildNode(rootNode, "configs");
        return mapper.readValue(mapper.treeAsTokens(configsNode),
                new TypeReference<List<CapturePoint>>() {});
    }

    // returns new version
    public CapturePoint addCapturePoint(CapturePoint capturePoint) throws Exception {
        String response = httpClient.post("/backend/config/capture-points/add",
                mapper.writeValueAsString(capturePoint));
        return ObjectMappers.readRequiredValue(mapper, response, CapturePoint.class);
    }

    public CapturePoint updateCapturePoint(CapturePoint capturePoint) throws Exception {
        String response = httpClient.post("/backend/config/capture-points/update",
                mapper.writeValueAsString(capturePoint));
        return ObjectMappers.readRequiredValue(mapper, response, CapturePoint.class);
    }

    public void removeCapturePoint(String version) throws Exception {
        httpClient.post("/backend/config/capture-points/remove",
                mapper.writeValueAsString(version));
    }

    public int reweavePointcuts() throws Exception {
        String response = httpClient.post("/backend/admin/reweave-capture-points", "");
        ObjectNode rootNode = ObjectMappers.readRequiredValue(mapper, response, ObjectNode.class);
        JsonNode classesNode = ObjectMappers.getRequiredChildNode(rootNode, "classes");
        return classesNode.asInt();
    }

    public void compactData() throws Exception {
        httpClient.post("/backend/admin/compact-data", "");
    }

    public void resetAllConfig() throws Exception {
        httpClient.post("/backend/admin/reset-all-config", "");
        // traceStoreThresholdMillis=0 is by far the most useful setting for testing
        setTraceStoreThresholdMillis(0);
    }

    public void setTraceStoreThresholdMillis(int traceStoreThresholdMillis) throws Exception {
        TraceConfig traceConfig = getTraceConfig();
        traceConfig.setStoreThresholdMillis(traceStoreThresholdMillis);
        updateTraceConfig(traceConfig);
    }

    private <T> T getConfig(String url, Class<T> valueType) throws Exception {
        String response = httpClient.get(url);
        ObjectNode rootNode = ObjectMappers.readRequiredValue(mapper, response, ObjectNode.class);
        JsonNode configNode = ObjectMappers.getRequiredChildNode(rootNode, "config");
        return mapper.treeToValue(configNode, valueType);
    }

    public interface GetUiPortCommand {
        int getUiPort() throws Exception;
    }

    @SuppressWarnings("serial")
    public class CurrentPasswordIncorrectException extends Exception {}

    @SuppressWarnings("serial")
    public class PortChangeFailedException extends Exception {}
}
