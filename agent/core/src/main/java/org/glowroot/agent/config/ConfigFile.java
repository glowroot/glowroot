/*
 * Copyright 2013-2018 the original author or authors.
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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.io.CharStreams;
import com.google.common.io.Files;
import com.google.common.primitives.Ints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.OnlyUsedByTests;

// TODO if config.json file has unrecognized top-level node (something other than "transactions",
// "ui", "userRecording", "advanced", etc) then log warning and remove that node
class ConfigFile {

    private static final Logger logger = LoggerFactory.getLogger(ConfigFile.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private static final List<String> keyOrder =
            ImmutableList.of("transactions", "jvm", "ui", "userRecording", "advanced", "gauges",
                    "syntheticMonitors", "alerts", "plugins", "instrumentation");

    private final File file;
    private final ObjectNode configRootObjectNode;

    ConfigFile(File file) {
        this.file = file;
        if (file.exists()) {
            configRootObjectNode = getRootObjectNode(file);
            upgradeAlertsIfNeeded(configRootObjectNode);
            upgradeUiIfNeeded(configRootObjectNode);
        } else {
            configRootObjectNode = mapper.createObjectNode();
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
            writeBackupFile(file);
            return null;
        }
    }

    void writeConfig(String key, Object config, ObjectMapper mapper) throws IOException {
        configRootObjectNode.replace(key, mapper.valueToTree(config));
        writeToFileIfNeeded(file, configRootObjectNode, keyOrder);
    }

    void writeConfig(Map<String, Object> config, ObjectMapper mapper) throws IOException {
        for (Entry<String, Object> entry : config.entrySet()) {
            configRootObjectNode.replace(entry.getKey(), mapper.valueToTree(entry.getValue()));
        }
        writeToFileIfNeeded(file, configRootObjectNode, keyOrder);
    }

    @OnlyUsedByTests
    void delete() throws IOException {
        if (!file.delete()) {
            throw new IOException("Could not delete file: " + file.getCanonicalPath());
        }
    }

    static ObjectNode getRootObjectNode(File file) {
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

    static void writeToFileIfNeeded(File file, ObjectNode rootObjectNode, List<String> keyOrder)
            throws IOException {
        String content = writeConfigAsString(rootObjectNode, keyOrder);
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

    private static String writeConfigAsString(ObjectNode rootObjectNode, List<String> keyOrder)
            throws IOException {
        ObjectNode orderedRootObjectNode = getOrderedObjectNode(rootObjectNode, keyOrder);
        ObjectMappers.stripEmptyContainerNodes(orderedRootObjectNode);
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        try {
            jg.setPrettyPrinter(ObjectMappers.getPrettyPrinter());
            jg.writeTree(orderedRootObjectNode);
        } finally {
            jg.close();
        }
        // newline is not required, just a personal preference
        return sb.toString() + ObjectMappers.NEWLINE;
    }

    private static ObjectNode getOrderedObjectNode(ObjectNode objectNode, List<String> keyOrder) {
        Map<String, JsonNode> map = Maps.newHashMap();
        Iterator<Entry<String, JsonNode>> i = objectNode.fields();
        while (i.hasNext()) {
            Entry<String, JsonNode> entry = i.next();
            map.put(entry.getKey(), entry.getValue());
        }
        ObjectNode orderedObjectNode = mapper.createObjectNode();
        for (Entry<String, JsonNode> entry : new ExplicitOrdering(keyOrder)
                .sortedCopy(map.entrySet())) {
            orderedObjectNode.set(entry.getKey(), entry.getValue());
        }
        return orderedObjectNode;
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
            if (!alertObjectNode.has("severity")) {
                // upgrade from 0.9.21 to 0.9.22
                alertObjectNode.put("severity", "critical");
            }
        }
    }

    private static void upgradeUiIfNeeded(ObjectNode configRootObjectNode) {
        JsonNode uiNode = configRootObjectNode.get("ui");
        if (uiNode == null || !uiNode.isObject()) {
            return;
        }
        ObjectNode uiObjectNode = (ObjectNode) uiNode;
        if (uiObjectNode.has("defaultDisplayedTransactionType")) {
            // upgrade from 0.9.28 to 0.10.0
            uiObjectNode.set("defaultTransactionType",
                    uiObjectNode.remove("defaultDisplayedTransactionType"));
        }
        if (uiObjectNode.has("defaultDisplayedPercentiles")) {
            // upgrade from 0.9.28 to 0.10.0
            uiObjectNode.set("defaultPercentiles",
                    uiObjectNode.remove("defaultDisplayedPercentiles"));
        }
    }

    private static class ExplicitOrdering extends Ordering<Entry<String, JsonNode>> {

        private final List<String> ordering;

        private ExplicitOrdering(List<String> ordering) {
            this.ordering = ordering;
        }

        @Override
        public int compare(Entry<String, JsonNode> left, Entry<String, JsonNode> right) {
            String leftKey = left.getKey();
            String rightKey = right.getKey();
            int compare = Ints.compare(getIndex(leftKey), getIndex(rightKey));
            if (compare != 0) {
                return compare;
            }
            return Ordering.natural().compare(leftKey, rightKey);
        }

        private int getIndex(String key) {
            int index = ordering.indexOf(key);
            return index == -1 ? Integer.MAX_VALUE : index;
        }
    }
}
