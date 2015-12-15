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
package org.glowroot.agent.config;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ContainerNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Charsets;
import com.google.common.base.StandardSystemProperty;
import com.google.common.io.CharStreams;
import com.google.common.io.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.OnlyUsedByTests;

// TODO if config.json file has unrecognized top-level node (something other than "transactions",
// "userRecording", "advanced", etc) then log warning and remove that node
class ConfigFile {

    private static final Logger logger = LoggerFactory.getLogger(ConfigFile.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private static final String NEWLINE;

    static {
        String newline = StandardSystemProperty.LINE_SEPARATOR.value();
        if (newline == null) {
            NEWLINE = "\n";
        } else {
            NEWLINE = newline;
        }
    }

    private final File file;
    private final ObjectNode rootObjectNode;

    ConfigFile(File file) {
        this.file = file;
        if (!file.exists()) {
            rootObjectNode = mapper.createObjectNode();
            return;
        }
        String content;
        try {
            content = Files.toString(file, Charsets.UTF_8);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            rootObjectNode = mapper.createObjectNode();
            return;
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
        this.rootObjectNode = rootObjectNode == null ? mapper.createObjectNode() : rootObjectNode;
    }

    @Nullable
    <T> T getNode(String key, Class<T> clazz, ObjectMapper mapper) {
        JsonNode node = rootObjectNode.get(key);
        if (node == null) {
            return null;
        }
        try {
            return mapper.treeToValue(node, clazz);
        } catch (JsonProcessingException e) {
            logger.error("error parsing config node '{}': ", key, e);
            return null;
        }
    }

    <T extends /*@NonNull*/ Object> /*@Nullable*/ T getNode(String key,
            TypeReference<T> typeReference, ObjectMapper mapper) {
        JsonNode node = rootObjectNode.get(key);
        if (node == null) {
            return null;
        }
        try {
            return mapper.readValue(mapper.treeAsTokens(node), typeReference);
        } catch (IOException e) {
            logger.error("error parsing config node '{}': ", key, e);
            return null;
        }
    }

    void write(String key, Object config, ObjectMapper mapper) throws IOException {
        rootObjectNode.replace(key, mapper.valueToTree(config));
        writeToFileIfNeeded();
    }

    void write(Map<String, Object> config, ObjectMapper mapper) throws IOException {
        for (Entry<String, Object> entry : config.entrySet()) {
            rootObjectNode.replace(entry.getKey(), mapper.valueToTree(entry.getValue()));
        }
        writeToFileIfNeeded();
    }

    private void writeToFileIfNeeded() throws IOException {
        String content = writeConfigAsString();
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

    @OnlyUsedByTests
    void delete() throws IOException {
        if (!file.delete()) {
            throw new IOException("Could not delete file: " + file.getCanonicalPath());
        }
    }

    private String writeConfigAsString() throws IOException {
        ObjectNode rootObjectNode = this.rootObjectNode.deepCopy();
        Iterator<Entry<String, JsonNode>> i = rootObjectNode.fields();
        while (i.hasNext()) {
            Entry<String, JsonNode> entry = i.next();
            JsonNode value = entry.getValue();
            if (value instanceof ContainerNode && ((ContainerNode<?>) value).size() == 0) {
                // remove empty nodes, e.g. unused "smtp" and "alerts" nodes
                i.remove();
            }
        }
        CustomPrettyPrinter prettyPrinter = new CustomPrettyPrinter();
        prettyPrinter.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb))
                .setPrettyPrinter(prettyPrinter);
        jg.writeTree(rootObjectNode);
        jg.close();
        // newline is not required, just a personal preference
        return sb.toString() + NEWLINE;
    }

    @SuppressWarnings("serial")
    private static class CustomPrettyPrinter extends DefaultPrettyPrinter {

        @Override
        public void writeObjectFieldValueSeparator(JsonGenerator jg) throws IOException {
            jg.writeRaw(": ");
        }
    }
}
