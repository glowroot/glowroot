/*
 * Copyright 2017-2018 the original author or authors.
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
package org.glowroot.agent.embedded.config;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.config.ConfigFileUtil;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common2.config.PermissionParser;

// TODO if admin.json file has unrecognized top-level node then log warning and remove that node
class AdminConfigFile {

    private static final Logger logger = LoggerFactory.getLogger(AdminConfigFile.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private static final List<String> keyOrder = ImmutableList.of("general", "users", "roles",
            "web", "storage", "smtp", "httpProxy", "ldap", "pagerDuty", "healthchecksIo");

    private final File file;
    private final ObjectNode rootObjectNode;

    AdminConfigFile(File file) {
        this.file = file;
        if (file.exists()) {
            rootObjectNode = ConfigFileUtil.getRootObjectNode(file);
            upgradeRolesIfNeeded(rootObjectNode);
            upgradeSmtpIfNeeded(rootObjectNode);
        } else {
            rootObjectNode = mapper.createObjectNode();
        }
    }

    @Nullable
    <T> T getConfig(String key, Class<T> clazz) {
        JsonNode node = rootObjectNode.get(key);
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

    <T extends /*@NonNull*/ Object> /*@Nullable*/ T getConfig(String key,
            TypeReference<T> typeReference) {
        JsonNode node = rootObjectNode.get(key);
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
}
