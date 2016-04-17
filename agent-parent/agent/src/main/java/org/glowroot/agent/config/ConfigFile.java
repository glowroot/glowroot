/*
 * Copyright 2013-2016 the original author or authors.
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
import com.google.common.io.CharStreams;
import com.google.common.io.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.OnlyUsedByTests;

// TODO if config.json or admin.json file have unrecognized top-level node (something other than
// "transactions", "userRecording", "advanced", etc) then log warning and remove that node
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
        } else {
            configRootObjectNode = mapper.createObjectNode();
        }
        if (adminFile.exists()) {
            adminRootObjectNode = getRootObjectNode(adminFile);
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
        return rootObjectNode == null ? mapper.createObjectNode() : rootObjectNode;
    }
}
