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
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import org.checkerframework.checker.nullness.qual.Nullable;
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
    private final ObjectNode rootObjectNode;

    ConfigFile(List<File> confDirs) {
        file = new File(confDirs.get(0), "config.json");
        if (file.exists()) {
            rootObjectNode = readRootObjectNode(file);
        } else {
            File defaultFile = getConfigDefaultFile(confDirs);
            if (defaultFile == null) {
                rootObjectNode = mapper.createObjectNode();
            } else {
                rootObjectNode = readRootObjectNode(defaultFile);
            }
        }
    }

    <T> /*@Nullable*/ T getConfig(String key, Class<T> clazz) {
        return ConfigFileUtil.getConfig(rootObjectNode, key, clazz);
    }

    <T> /*@Nullable*/ T getConfig(String key, TypeReference<T> typeReference) {
        return ConfigFileUtil.getConfig(rootObjectNode, key, typeReference);
    }

    void writeConfig(String key, Object config) throws IOException {
        rootObjectNode.replace(key, mapper.valueToTree(config));
        ConfigFileUtil.writeToFileIfNeeded(file, rootObjectNode, keyOrder);
    }

    void writeConfigs(Map<String, Object> configs) throws IOException {
        for (Map.Entry<String, Object> entry : configs.entrySet()) {
            rootObjectNode.replace(entry.getKey(), mapper.valueToTree(entry.getValue()));
        }
        ConfigFileUtil.writeToFileIfNeeded(file, rootObjectNode, keyOrder);
    }

    @OnlyUsedByTests
    void delete() throws IOException {
        if (!file.delete()) {
            throw new IOException("Could not delete file: " + file.getCanonicalPath());
        }
    }

    private static ObjectNode readRootObjectNode(File file) {
        ObjectNode rootObjectNode = ConfigFileUtil.getRootObjectNode(file);
        upgradeAlertsIfNeeded(rootObjectNode);
        upgradeUiIfNeeded(rootObjectNode);
        upgradeAdvancedIfNeeded(rootObjectNode);
        return rootObjectNode;
    }

    private static @Nullable File getConfigDefaultFile(List<File> confDirs) {
        for (File confDir : confDirs) {
            File defaultFile = new File(confDir, "config-default.json");
            if (defaultFile.exists()) {
                return defaultFile;
            }
        }
        return null;
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

    private static void upgradeAdvancedIfNeeded(ObjectNode configRootObjectNode) {
        JsonNode advancedNode = configRootObjectNode.get("advanced");
        if (advancedNode == null || !advancedNode.isObject()) {
            return;
        }
        ObjectNode advancedObjectNode = (ObjectNode) advancedNode;
        if (advancedObjectNode.has("maxAggregateTransactionsPerType")) {
            // upgrade from 0.10.5 to 0.10.6
            advancedObjectNode.set("maxTransactionAggregates",
                    advancedObjectNode.remove("maxAggregateTransactionsPerType"));
        }
        if (advancedObjectNode.has("maxAggregateQueriesPerType")) {
            // upgrade from 0.10.5 to 0.10.6
            advancedObjectNode.set("maxQueryAggregates",
                    advancedObjectNode.remove("maxAggregateQueriesPerType"));
        }
        if (advancedObjectNode.has("maxAggregateServiceCallsPerType")) {
            // upgrade from 0.10.5 to 0.10.6
            advancedObjectNode.set("maxServiceCallAggregates",
                    advancedObjectNode.remove("maxAggregateServiceCallsPerType"));
        }
        if (advancedObjectNode.has("maxStackTraceSamplesPerTransaction")) {
            // upgrade from 0.10.5 to 0.10.6
            advancedObjectNode.set("maxProfileSamplesPerTransaction",
                    advancedObjectNode.remove("maxStackTraceSamplesPerTransaction"));
        }
    }
}
