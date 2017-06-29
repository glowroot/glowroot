/*
 * Copyright 2013-2017 the original author or authors.
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
package org.glowroot.agent.config;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.common.io.CharStreams;
import com.google.common.io.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.config.PermissionParser;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.OnlyUsedByTests;

// TODO if config.json or admin.json file have unrecognized top-level node (something other than
// "transactions", "ui", "userRecording", "advanced", etc) then log warning and remove that node
class ConfigFile {

    private static final Logger logger = LoggerFactory.getLogger(ConfigFile.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private final File configFile;
    private final File adminFile;
    private final ObjectNode configRootObjectNode;
    private final ObjectNode adminRootObjectNode;

    ConfigFile(File configFile, File adminFile) {
        this.configFile = configFile;
        this.adminFile = adminFile;
        if (configFile.exists()) {
            configRootObjectNode = getRootObjectNode(configFile);
            upgradeAlertsIfNeeded(configRootObjectNode);
        } else {
            configRootObjectNode = mapper.createObjectNode();
        }
        if (adminFile.exists()) {
            adminRootObjectNode = getRootObjectNode(adminFile);
            upgradeRolesIfNeeded(adminRootObjectNode);
            upgradeSmtpIfNeeded(adminRootObjectNode);
        } else {
            adminRootObjectNode = mapper.createObjectNode();
        }
    }

    @Nullable
    <T> T getConfigNode(String key, Class<T> clazz, ObjectMapper mapper) {
        JsonNode node = configRootObjectNode.get(key);
        if (node == null) {
            return null;
        }
        try {
            return mapper.treeToValue(node, clazz);
        } catch (JsonProcessingException e) {
            logger.error("error parsing config json node '{}': ", key, e);
            return null;
        }
    }

    @Nullable
    <T> T getAdminNode(String key, Class<T> clazz, ObjectMapper mapper) {
        JsonNode node = adminRootObjectNode.get(key);
        if (node == null) {
            return null;
        }
        try {
            return mapper.treeToValue(node, clazz);
        } catch (JsonProcessingException e) {
            logger.error("error parsing admin json node '{}': ", key, e);
            return null;
        }
    }

    <T extends /*@NonNull*/ Object> /*@Nullable*/ T getConfigNode(String key,
            TypeReference<T> typeReference, ObjectMapper mapper) {
        JsonNode node = configRootObjectNode.get(key);
        if (node == null) {
            return null;
        }
        try {
            return mapper.readValue(mapper.treeAsTokens(node), typeReference);
        } catch (IOException e) {
            logger.error("error parsing config json node '{}': ", key, e);
            writeBackupFile(configFile);
            return null;
        }
    }

    <T extends /*@NonNull*/ Object> /*@Nullable*/ T getAdminNode(String key,
            TypeReference<T> typeReference, ObjectMapper mapper) {
        JsonNode node = adminRootObjectNode.get(key);
        if (node == null) {
            return null;
        }
        try {
            return mapper.readValue(mapper.treeAsTokens(node), typeReference);
        } catch (IOException e) {
            logger.error("error parsing admin json node '{}': ", key, e);
            return null;
        }
    }

    void writeConfig(String key, Object config, ObjectMapper mapper) throws IOException {
        configRootObjectNode.replace(key, mapper.valueToTree(config));
        writeToFileIfNeeded(configFile, configRootObjectNode);
    }

    void writeAdmin(String key, Object config, ObjectMapper mapper) throws IOException {
        adminRootObjectNode.replace(key, mapper.valueToTree(config));
        writeToFileIfNeeded(adminFile, adminRootObjectNode);
    }

    void writeConfig(Map<String, Object> config, ObjectMapper mapper) throws IOException {
        for (Entry<String, Object> entry : config.entrySet()) {
            configRootObjectNode.replace(entry.getKey(), mapper.valueToTree(entry.getValue()));
        }
        writeToFileIfNeeded(configFile, configRootObjectNode);
    }

    void writeAdmin(Map<String, Object> config, ObjectMapper mapper) throws IOException {
        for (Entry<String, Object> entry : config.entrySet()) {
            adminRootObjectNode.replace(entry.getKey(), mapper.valueToTree(entry.getValue()));
        }
        writeToFileIfNeeded(adminFile, adminRootObjectNode);
    }

    @OnlyUsedByTests
    void delete() throws IOException {
        if (!configFile.delete()) {
            throw new IOException("Could not delete file: " + configFile.getCanonicalPath());
        }
    }

    private static void upgradeAlertsIfNeeded(ObjectNode configRootObjectNode) {
        JsonNode alertsNode = configRootObjectNode.get("alerts");
        if (alertsNode == null || !alertsNode.isArray()) {
            return;
        }
        for (JsonNode alertNode : alertsNode) {
            if (!(alertNode instanceof ObjectNode)) {
                continue;
            }
            ObjectNode alertObjectNode = (ObjectNode) alertNode;
            if (alertObjectNode.has("transactionThresholdMillis")) {
                // upgrade from 0.9.9 to 0.9.10
                alertObjectNode.set("thresholdMillis",
                        alertObjectNode.remove("transactionThresholdMillis"));
            }
            if (alertObjectNode.has("kind")) {
                // upgrade from 0.9.17 to 0.9.18
                String alertKind = alertObjectNode.remove("kind").asText();
                if (alertKind.equals("transaction")) {
                    ObjectNode conditionObjectNode = mapper.createObjectNode();
                    conditionObjectNode.put("conditionType", "metric");
                    conditionObjectNode.put("metric", "transaction:x-percentile");
                    conditionObjectNode.set("transactionType",
                            alertObjectNode.remove("transactionType"));
                    conditionObjectNode.set("percentile",
                            alertObjectNode.remove("transactionPercentile"));
                    conditionObjectNode.set("threshold", alertObjectNode.remove("thresholdMillis"));
                    conditionObjectNode.set("timePeriodSeconds",
                            alertObjectNode.remove("timePeriodSeconds"));
                    conditionObjectNode.set("minTransactionCount",
                            alertObjectNode.remove("minTransactionCount"));
                    alertObjectNode.set("condition", conditionObjectNode);
                } else if (alertKind.equals("gauge")) {
                    ObjectNode conditionObjectNode = mapper.createObjectNode();
                    conditionObjectNode.put("conditionType", "metric");
                    conditionObjectNode.put("metric",
                            "gauge:" + alertObjectNode.remove("gaugeName").asText());
                    conditionObjectNode.set("threshold", alertObjectNode.remove("gaugeThreshold"));
                    conditionObjectNode.set("timePeriodSeconds",
                            alertObjectNode.remove("timePeriodSeconds"));
                    alertObjectNode.set("condition", conditionObjectNode);
                } else {
                    logger.error("unexpected alert kind: {}", alertKind);
                }
                ObjectNode emailNotificationObjectNode = mapper.createObjectNode();
                emailNotificationObjectNode.set("emailAddresses",
                        alertObjectNode.remove("emailAddresses"));
                alertObjectNode.set("emailNotification", emailNotificationObjectNode);
            }
        }
    }

    private static void upgradeRolesIfNeeded(ObjectNode adminRootObjectNode) {
        // upgrade from 0.9.1 to 0.9.2
        JsonNode rolesNode = adminRootObjectNode.get("roles");
        if (rolesNode == null || !rolesNode.isArray()) {
            return;
        }
        for (JsonNode roleNode : rolesNode) {
            JsonNode permissionsNode = roleNode.get("permissions");
            if (permissionsNode == null || !permissionsNode.isArray()) {
                continue;
            }
            List<String> permissions = Lists.newArrayList();
            ArrayNode permissionsArrayNode = (ArrayNode) permissionsNode;
            for (int i = 0; i < permissionsArrayNode.size(); i++) {
                JsonNode permissionNode = permissionsArrayNode.get(i);
                if (!permissionNode.isTextual()) {
                    continue;
                }
                permissions.add(permissionNode.asText());
            }
            boolean upgraded =
                    PermissionParser.upgradeAgentPermissionsFrom_0_9_1_to_0_9_2(permissions);
            if (upgraded && permissions.contains("admin:view")
                    && permissions.contains("admin:edit")) {
                // only apply these updates if upgrading from 0.9.1 to 0.9.2
                permissions.remove("admin:view");
                permissions.remove("admin:edit");
                permissions.add("admin");
            }
            // upgrade from 0.9.19 to 0.9.20
            int index = permissions.indexOf("agent:alert");
            if (index != -1) {
                permissions.set(index, "agent:incident");
            }
            permissionsArrayNode.removeAll();
            for (String permission : permissions) {
                permissionsArrayNode.add(new TextNode(permission));
            }
        }
    }

    private static void upgradeSmtpIfNeeded(ObjectNode adminRootObjectNode) {
        // upgrade from 0.9.19 to 0.9.20
        JsonNode smtpNode = adminRootObjectNode.get("smtp");
        if (smtpNode == null || !smtpNode.isObject()) {
            return;
        }
        ObjectNode smtpObjectNode = (ObjectNode) smtpNode;
        JsonNode sslNode = smtpObjectNode.remove("ssl");
        if (sslNode != null && sslNode.isBoolean() && sslNode.asBoolean()) {
            smtpObjectNode.put("connectionSecurity", "ssl-tls");
        }
    }

    private static void writeToFileIfNeeded(File file, ObjectNode rootObjectNode)
            throws IOException {
        String content = writeConfigAsString(rootObjectNode);
        if (file.exists()) {
            String existingContent = Files.toString(file, Charsets.UTF_8);
            if (content.equals(existingContent)) {
                // it's nice to preserve the correct modification stamp on the file to track when it
                // was last really changed
                return;
            }
        }
        Files.write(content, file, Charsets.UTF_8);
    }

    private static String writeConfigAsString(ObjectNode rootObjectNode) throws IOException {
        ObjectNode rootObjectNodeCopy = rootObjectNode.deepCopy();
        ObjectMappers.stripEmptyContainerNodes(rootObjectNodeCopy);
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb))
                .setPrettyPrinter(ObjectMappers.getPrettyPrinter());
        jg.writeTree(rootObjectNodeCopy);
        jg.close();
        // newline is not required, just a personal preference
        return sb.toString() + ObjectMappers.NEWLINE;
    }

    private static ObjectNode getRootObjectNode(File file) {
        String content;
        try {
            content = Files.toString(file, Charsets.UTF_8);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            return mapper.createObjectNode();
        }
        ObjectNode rootObjectNode = null;
        try {
            JsonNode rootNode = mapper.readTree(content);
            if (rootNode instanceof ObjectNode) {
                rootObjectNode = (ObjectNode) rootNode;
            }
        } catch (IOException e) {
            logger.warn("error processing config file: {}", file.getAbsolutePath(), e);
            writeBackupFile(file);
        }
        return rootObjectNode == null ? mapper.createObjectNode() : rootObjectNode;
    }

    private static void writeBackupFile(File file) {
        File backupFile = new File(file.getParentFile(), file.getName() + ".invalid-orig");
        try {
            Files.copy(file, backupFile);
            logger.warn("due to an error in the config file, it has been backed up to extension"
                    + " '.invalid-orig' and will be overwritten with the default config");
        } catch (IOException f) {
            logger.warn("error making a copy of the invalid config file before overwriting it",
                    f);
        }
    }
}
