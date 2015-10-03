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
package org.glowroot.agent.harness.config;

import java.util.List;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import org.glowroot.agent.harness.common.HttpClient;
import org.glowroot.agent.harness.common.ObjectMappers;

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

    public TransactionConfig getTransactionConfig() throws Exception {
        return getConfig("/backend/config/transaction?server=", TransactionConfig.class);
    }

    public void updateTransactionConfig(TransactionConfig config) throws Exception {
        httpClient.post("/backend/config/transaction", toJson(config));
    }

    public UserRecordingConfig getUserRecordingConfig() throws Exception {
        return getConfig("/backend/config/user-recording?server=", UserRecordingConfig.class);
    }

    public void updateUserRecordingConfig(UserRecordingConfig config) throws Exception {
        httpClient.post("/backend/config/user-recording", toJson(config));
    }

    public AdvancedConfig getAdvancedConfig() throws Exception {
        return getConfig("/backend/config/advanced?server=", AdvancedConfig.class);
    }

    public void updateAdvancedConfig(AdvancedConfig config) throws Exception {
        httpClient.post("/backend/config/advanced", toJson(config));
    }

    public @Nullable PluginConfig getPluginConfig(String pluginId) throws Exception {
        return getConfig("/backend/config/plugins?server=&plugin-id=" + pluginId,
                PluginConfig.class);
    }

    public void updatePluginConfig(String pluginId, PluginConfig config) throws Exception {
        ObjectNode node = mapper.valueToTree(config);
        node.put("server", 0);
        node.put("pluginId", pluginId);
        httpClient.post("/backend/config/plugins", mapper.writeValueAsString(node));
    }

    public InstrumentationConfig getInstrumentationConfig(String version) throws Exception {
        String response =
                httpClient.get("/backend/config/instrumentation?server=&version=" + version);
        ObjectNode rootNode = ObjectMappers.readRequiredValue(mapper, response, ObjectNode.class);
        ObjectNode configNode = (ObjectNode) ObjectMappers.getRequiredChildNode(rootNode, "config");
        return mapper.readValue(mapper.treeAsTokens(configNode),
                new TypeReference<InstrumentationConfig>() {});
    }

    public List<InstrumentationConfig> getInstrumentationConfigs() throws Exception {
        String response = httpClient.get("/backend/config/instrumentation?server=");
        ObjectNode rootNode = ObjectMappers.readRequiredValue(mapper, response, ObjectNode.class);
        JsonNode configsNode = rootNode.get("configs");
        if (configsNode == null) {
            return ImmutableList.of();
        }
        return mapper.readValue(mapper.treeAsTokens(configsNode),
                new TypeReference<List<InstrumentationConfig>>() {});
    }

    // returns new version
    public InstrumentationConfig addInstrumentationConfig(InstrumentationConfig config)
            throws Exception {
        String response = httpClient.post("/backend/config/instrumentation/add", toJson(config));
        ObjectNode rootNode = ObjectMappers.readRequiredValue(mapper, response, ObjectNode.class);
        ObjectNode configNode = (ObjectNode) ObjectMappers.getRequiredChildNode(rootNode, "config");
        return mapper.readValue(mapper.treeAsTokens(configNode), InstrumentationConfig.class);
    }

    public InstrumentationConfig updateInstrumentationConfig(InstrumentationConfig config)
            throws Exception {
        String response = httpClient.post("/backend/config/instrumentation/update", toJson(config));
        ObjectNode rootNode = ObjectMappers.readRequiredValue(mapper, response, ObjectNode.class);
        ObjectNode configNode = (ObjectNode) ObjectMappers.getRequiredChildNode(rootNode, "config");
        return mapper.readValue(mapper.treeAsTokens(configNode), InstrumentationConfig.class);
    }

    public void removeInstrumentationConfig(String version) throws Exception {
        httpClient.post("/backend/config/instrumentation/remove", toJson(version));
    }

    public GaugeConfig getGaugeConfig(String version) throws Exception {
        String response = httpClient.get("/backend/config/gauges?server=&version=" + version);
        ObjectNode rootNode = ObjectMappers.readRequiredValue(mapper, response, ObjectNode.class);
        ObjectNode configNode = (ObjectNode) ObjectMappers.getRequiredChildNode(rootNode, "config");
        return mapper.readValue(mapper.treeAsTokens(configNode),
                new TypeReference<GaugeConfig>() {});
    }

    public List<GaugeConfig> getGaugeConfigs() throws Exception {
        String response = httpClient.get("/backend/config/gauges?server=");
        ArrayNode rootNode = ObjectMappers.readRequiredValue(mapper, response, ArrayNode.class);
        List<GaugeConfig> configs = Lists.newArrayList();
        for (JsonNode childNode : rootNode) {
            ObjectNode configNode =
                    (ObjectNode) ObjectMappers.getRequiredChildNode(childNode, "config");
            configs.add(mapper.readValue(mapper.treeAsTokens(configNode), GaugeConfig.class));
        }
        return configs;
    }

    // returns new version
    public GaugeConfig addGaugeConfig(GaugeConfig config) throws Exception {
        String response = httpClient.post("/backend/config/gauges/add", toJson(config));
        ObjectNode rootNode = ObjectMappers.readRequiredValue(mapper, response, ObjectNode.class);
        ObjectNode configNode = (ObjectNode) ObjectMappers.getRequiredChildNode(rootNode, "config");
        return mapper.readValue(mapper.treeAsTokens(configNode), GaugeConfig.class);
    }

    public GaugeConfig updateGaugeConfig(GaugeConfig config) throws Exception {
        String response = httpClient.post("/backend/config/gauges/update", toJson(config));
        ObjectNode rootNode = ObjectMappers.readRequiredValue(mapper, response, ObjectNode.class);
        ObjectNode configNode = (ObjectNode) ObjectMappers.getRequiredChildNode(rootNode, "config");
        return mapper.readValue(mapper.treeAsTokens(configNode), GaugeConfig.class);
    }

    public void removeGaugeConfig(String version) throws Exception {
        httpClient.post("/backend/config/gauges/remove", toJson(version));
    }

    public AlertConfig getAlertConfig(String version) throws Exception {
        String response = httpClient.get("/backend/config/alerts?server=&version=" + version);
        return mapper.readValue(response, new TypeReference<AlertConfig>() {});
    }

    public List<AlertConfig> getAlertConfigs() throws Exception {
        String response = httpClient.get("/backend/config/alerts?server=");
        return mapper.readValue(response, new TypeReference<List<AlertConfig>>() {});
    }

    // returns new version
    public AlertConfig addAlertConfig(AlertConfig config) throws Exception {
        String response = httpClient.post("/backend/config/alerts/add", toJson(config));
        return ObjectMappers.readRequiredValue(mapper, response, AlertConfig.class);
    }

    public AlertConfig updateAlertConfig(AlertConfig config) throws Exception {
        String response = httpClient.post("/backend/config/alerts/update", toJson(config));
        return ObjectMappers.readRequiredValue(mapper, response, AlertConfig.class);
    }

    public void removeAlertConfig(String version) throws Exception {
        httpClient.post("/backend/config/alerts/remove", toJson(version));
    }

    public UserInterfaceConfig getUserInterfaceConfig() throws Exception {
        return getConfig("/backend/config/ui", UserInterfaceConfig.class);
    }

    // throws CurrentPasswordIncorrectException
    public void updateUserInterfaceConfig(UserInterfaceConfig config) throws Exception {
        String response = httpClient.post("/backend/config/ui", mapper.writeValueAsString(config));
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

    public StorageConfig getStorageConfig() throws Exception {
        return getConfig("/backend/config/storage", StorageConfig.class);
    }

    public void updateStorageConfig(StorageConfig config) throws Exception {
        httpClient.post("/backend/config/storage", mapper.writeValueAsString(config));
    }

    public SmtpConfig getSmtpConfig() throws Exception {
        return getConfig("/backend/config/smtp", SmtpConfig.class);
    }

    public void updateSmtpConfig(SmtpConfig config) throws Exception {
        httpClient.post("/backend/config/smtp", mapper.writeValueAsString(config));
    }

    public int reweave() throws Exception {
        String response = httpClient.post("/backend/admin/reweave", "{\"server\":\"\"}");
        ObjectNode rootNode = ObjectMappers.readRequiredValue(mapper, response, ObjectNode.class);
        JsonNode classesNode = ObjectMappers.getRequiredChildNode(rootNode, "classes");
        return classesNode.asInt();
    }

    public void compactData() throws Exception {
        httpClient.post("/backend/admin/defrag-data", "");
    }

    public void resetAllConfig() throws Exception {
        httpClient.post("/backend/admin/reset-all-config", "{\"server\":\"\"}");
        // slowThresholdMillis=0 is by far the most useful setting for testing
        setTransactionSlowThresholdMillis(0);
    }

    public void setTransactionSlowThresholdMillis(int slowThresholdMillis) throws Exception {
        TransactionConfig transactionConfig = getTransactionConfig();
        transactionConfig.setSlowThresholdMillis(slowThresholdMillis);
        updateTransactionConfig(transactionConfig);
    }

    private <T> T getConfig(String url, Class<T> valueType) throws Exception {
        String response = httpClient.get(url);
        ObjectNode rootNode = ObjectMappers.readRequiredValue(mapper, response, ObjectNode.class);
        JsonNode configNode = rootNode.get("config");
        if (configNode == null) {
            configNode = rootNode;
        }
        return mapper.treeToValue(configNode, valueType);
    }

    private String toJson(Object config) throws JsonProcessingException {
        ObjectNode node = mapper.valueToTree(config);
        node.put("server", 0);
        return mapper.writeValueAsString(node);
    }

    private String toJson(String version) {
        return "{\"server\":\"\",\"version\":\"" + version + "\"}";
    }

    public interface GetUiPortCommand {
        int getUiPort() throws Exception;
    }

    @SuppressWarnings("serial")
    public class CurrentPasswordIncorrectException extends Exception {}

    @SuppressWarnings("serial")
    public class PortChangeFailedException extends Exception {}
}
