/*
 * Copyright 2018 the original author or authors.
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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.io.CharStreams;
import com.google.common.io.Files;
import com.google.common.primitives.Ints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.util.ObjectMappers;

import static com.google.common.base.Charsets.UTF_8;

public class ConfigFileUtil {

    private static final Logger logger = LoggerFactory.getLogger(ConfigFile.class);

    // log startup messages using logger name "org.glowroot"
    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

    private static final ObjectMapper mapper = ObjectMappers.create();

    private ConfigFileUtil() {}

    public static ObjectNode getRootObjectNode(File file) {
        String content;
        try {
            content = Files.toString(file, UTF_8);
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

    public static <T> /*@Nullable*/ T getConfig(ObjectNode rootObjectNode, String key,
            Class<T> clazz) {
        JsonNode node = rootObjectNode.get(key);
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

    public static <T> /*@Nullable*/ T getConfig(ObjectNode rootObjectNode, String key,
            TypeReference<T> typeReference) {
        JsonNode node = rootObjectNode.get(key);
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

    public static void writeToFileIfNeeded(File file, ObjectNode rootObjectNode,
            List<String> keyOrder, boolean logStartupMessageInstead) throws IOException {
        String content = writeConfigAsString(rootObjectNode, keyOrder);
        String existingContent = "";
        if (file.exists()) {
            existingContent = Files.toString(file, UTF_8);
            if (content.equals(existingContent)) {
                // it's nice to preserve the correct modification stamp on the file to track when it
                // was last really changed
                return;
            }
        }
        if (!logStartupMessageInstead) {
            Files.write(content, file, UTF_8);
        } else if (!normalize(content).equals(normalize(existingContent))) {
            startupLogger.info("tried to update {} during startup but the agent is running with"
                    + " config.readOnly=true, you can prevent this message from re-occurring by"
                    + " manually updating the file with these contents:\n{}", file.getName(),
                    content);
        }
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
        Iterator<Map.Entry<String, JsonNode>> i = objectNode.fields();
        while (i.hasNext()) {
            Map.Entry<String, JsonNode> entry = i.next();
            map.put(entry.getKey(), entry.getValue());
        }
        ObjectNode orderedObjectNode = mapper.createObjectNode();
        for (Map.Entry<String, JsonNode> entry : new ExplicitOrdering(keyOrder)
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

    private static String normalize(String content) {
        return content.replace("\r\n", "\n").trim();
    }

    private static class ExplicitOrdering extends Ordering<Map.Entry<String, JsonNode>> {

        private final List<String> ordering;

        private ExplicitOrdering(List<String> ordering) {
            this.ordering = ordering;
        }

        @Override
        public int compare(Map.Entry<String, JsonNode> left, Map.Entry<String, JsonNode> right) {
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
