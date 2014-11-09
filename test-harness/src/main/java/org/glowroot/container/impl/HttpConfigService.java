/*
 * Copyright 2011-2014 the original author or authors.
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
package org.glowroot.container.impl;

import java.util.List;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.Lists;

import org.glowroot.container.common.ObjectMappers;
import org.glowroot.container.config.AdvancedConfig;
import org.glowroot.container.config.CapturePoint;
import org.glowroot.container.config.ConfigService;
import org.glowroot.container.config.MBeanGauge;
import org.glowroot.container.config.PluginConfig;
import org.glowroot.container.config.ProfilingConfig;
import org.glowroot.container.config.StorageConfig;
import org.glowroot.container.config.TraceConfig;
import org.glowroot.container.config.UserInterfaceConfig;
import org.glowroot.container.config.UserRecordingConfig;

class HttpConfigService implements ConfigService {

    private static final ObjectMapper mapper = ObjectMappers.create();

    private final HttpClient httpClient;
    private final GetUiPortCommand getUiPortCommand;

    HttpConfigService(HttpClient httpClient, GetUiPortCommand getUiPortCommand) {
        this.httpClient = httpClient;
        this.getUiPortCommand = getUiPortCommand;
    }

    @Override
    public void setPluginProperty(String pluginId, String propertyName,
            @Nullable Object propertyValue) throws Exception {
        PluginConfig config = getPluginConfig(pluginId);
        if (config == null) {
            throw new IllegalStateException("Plugin not found for pluginId: " + pluginId);
        }
        config.setProperty(propertyName, propertyValue);
        updatePluginConfig(pluginId, config);
    }

    @Override
    public TraceConfig getTraceConfig() throws Exception {
        return getConfig("/backend/config/trace", TraceConfig.class);
    }

    @Override
    public void updateTraceConfig(TraceConfig config) throws Exception {
        httpClient.post("/backend/config/trace", mapper.writeValueAsString(config));
    }

    @Override
    public ProfilingConfig getProfilingConfig() throws Exception {
        return getConfig("/backend/config/profiling", ProfilingConfig.class);
    }

    @Override
    public void updateProfilingConfig(ProfilingConfig config) throws Exception {
        httpClient.post("/backend/config/profiling", mapper.writeValueAsString(config));
    }

    @Override
    public UserRecordingConfig getUserRecordingConfig() throws Exception {
        return getConfig("/backend/config/user-recording", UserRecordingConfig.class);
    }

    @Override
    public void updateUserRecordingConfig(UserRecordingConfig config) throws Exception {
        httpClient.post("/backend/config/user-recording", mapper.writeValueAsString(config));
    }

    @Override
    public StorageConfig getStorageConfig() throws Exception {
        return getConfig("/backend/config/storage", StorageConfig.class);
    }

    @Override
    public void updateStorageConfig(StorageConfig config) throws Exception {
        httpClient.post("/backend/config/storage", mapper.writeValueAsString(config));
    }

    @Override
    public UserInterfaceConfig getUserInterfaceConfig() throws Exception {
        return getConfig("/backend/config/user-interface", UserInterfaceConfig.class);
    }

    @Override
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

    @Override
    public AdvancedConfig getAdvancedConfig() throws Exception {
        return getConfig("/backend/config/advanced", AdvancedConfig.class);
    }

    @Override
    public void updateAdvancedConfig(AdvancedConfig config) throws Exception {
        httpClient.post("/backend/config/advanced", mapper.writeValueAsString(config));
    }

    @Override
    @Nullable
    public PluginConfig getPluginConfig(String pluginId) throws Exception {
        return getConfig("/backend/config/plugin/" + pluginId, PluginConfig.class);
    }

    @Override
    public void updatePluginConfig(String pluginId, PluginConfig config) throws Exception {
        httpClient.post("/backend/config/plugin/" + pluginId, mapper.writeValueAsString(config));
    }

    @Override
    public List<MBeanGauge> getMBeanGauges() throws Exception {
        String response = httpClient.get("/backend/config/mbean-gauges");
        ArrayNode rootNode = ObjectMappers.readRequiredValue(mapper, response, ArrayNode.class);
        List<MBeanGauge> configs = Lists.newArrayList();
        for (JsonNode childNode : rootNode) {
            ObjectNode configNode =
                    (ObjectNode) ObjectMappers.getRequiredChildNode(childNode, "config");
            configs.add(mapper.readValue(mapper.treeAsTokens(configNode), MBeanGauge.class));
        }
        return configs;
    }

    // returns new version
    @Override
    public MBeanGauge addMBeanGauge(MBeanGauge mbeanGauge) throws Exception {
        String response = httpClient.post("/backend/config/mbean-gauges/add",
                mapper.writeValueAsString(mbeanGauge));
        ObjectNode rootNode = ObjectMappers.readRequiredValue(mapper, response, ObjectNode.class);
        ObjectNode configNode = (ObjectNode) ObjectMappers.getRequiredChildNode(rootNode, "config");
        return mapper.readValue(mapper.treeAsTokens(configNode), MBeanGauge.class);
    }

    @Override
    public MBeanGauge updateMBeanGauge(MBeanGauge mbeanGauge) throws Exception {
        String response = httpClient.post("/backend/config/mbean-gauges/update",
                mapper.writeValueAsString(mbeanGauge));
        ObjectNode rootNode = ObjectMappers.readRequiredValue(mapper, response, ObjectNode.class);
        ObjectNode configNode = (ObjectNode) ObjectMappers.getRequiredChildNode(rootNode, "config");
        return mapper.readValue(mapper.treeAsTokens(configNode), MBeanGauge.class);
    }

    @Override
    public void removeMBeanGauge(String version) throws Exception {
        httpClient.post("/backend/config/mbean-gauges/remove", mapper.writeValueAsString(version));
    }

    @Override
    public List<CapturePoint> getCapturePoints() throws Exception {
        String response = httpClient.get("/backend/config/capture-points");
        ObjectNode rootNode = ObjectMappers.readRequiredValue(mapper, response, ObjectNode.class);
        JsonNode configsNode = ObjectMappers.getRequiredChildNode(rootNode, "configs");
        return mapper.readValue(mapper.treeAsTokens(configsNode),
                new TypeReference<List<CapturePoint>>() {});
    }

    // returns new version
    @Override
    public CapturePoint addCapturePoint(CapturePoint capturePoint) throws Exception {
        String response = httpClient.post("/backend/config/capture-points/add",
                mapper.writeValueAsString(capturePoint));
        return ObjectMappers.readRequiredValue(mapper, response, CapturePoint.class);
    }

    @Override
    public CapturePoint updateCapturePoint(CapturePoint capturePoint) throws Exception {
        String response = httpClient.post("/backend/config/capture-points/update",
                mapper.writeValueAsString(capturePoint));
        return ObjectMappers.readRequiredValue(mapper, response, CapturePoint.class);
    }

    @Override
    public void removeCapturePoint(String version) throws Exception {
        httpClient.post("/backend/config/capture-points/remove",
                mapper.writeValueAsString(version));
    }

    @Override
    public int reweavePointcuts() throws Exception {
        String response = httpClient.post("/backend/admin/reweave-capture-points", "");
        ObjectNode rootNode = ObjectMappers.readRequiredValue(mapper, response, ObjectNode.class);
        JsonNode classesNode = ObjectMappers.getRequiredChildNode(rootNode, "classes");
        return classesNode.asInt();
    }

    @Override
    public void compactData() throws Exception {
        httpClient.post("/backend/admin/compact-data", "");
    }

    void resetAllConfig() throws Exception {
        httpClient.post("/backend/admin/reset-all-config", "");
        // traceStoreThresholdMillis=0 is by far the most useful setting for testing
        setTraceStoreThresholdMillis(0);
    }

    void setTraceStoreThresholdMillis(int traceStoreThresholdMillis) throws Exception {
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

    interface GetUiPortCommand {
        int getUiPort() throws Exception;
    }
}
