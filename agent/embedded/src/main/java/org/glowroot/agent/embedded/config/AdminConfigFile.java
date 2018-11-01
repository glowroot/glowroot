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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.agent.config.ConfigFileUtil;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common2.config.PermissionParser;

// TODO if admin.json file has unrecognized top-level node then log warning and remove that node
class AdminConfigFile {

    private static final ObjectMapper mapper = ObjectMappers.create();

    private static final List<String> keyOrder = ImmutableList.of("general", "users", "roles",
            "web", "storage", "smtp", "httpProxy", "ldap", "pagerDuty", "slack", "healthchecksIo");

    private final File file;
    private final ObjectNode rootObjectNode;
    private final boolean readOnly;

    AdminConfigFile(List<File> confDirs, boolean readOnly) {
        file = new File(confDirs.get(0), "admin.json");
        if (file.exists()) {
            rootObjectNode = readRootObjectNode(file);
        } else {
            File defaultFile = getAdminConfigDefaultFile(confDirs);
            if (defaultFile == null) {
                rootObjectNode = mapper.createObjectNode();
            } else {
                rootObjectNode = readRootObjectNode(defaultFile);
            }
        }
        this.readOnly = readOnly;
    }

    <T> /*@Nullable*/ T getConfig(String key, Class<T> clazz) {
        return ConfigFileUtil.getConfig(rootObjectNode, key, clazz);
    }

    <T> /*@Nullable*/ T getConfig(String key, TypeReference<T> typeReference) {
        return ConfigFileUtil.getConfig(rootObjectNode, key, typeReference);
    }

    void writeConfig(String key, Object config) throws IOException {
        if (readOnly) {
            throw new IllegalStateException("Running with config.readOnly=true so config updates"
                    + " are not allowed");
        }
        rootObjectNode.replace(key, mapper.valueToTree(config));
        ConfigFileUtil.writeToFileIfNeeded(file, rootObjectNode, keyOrder, false);
    }

    void writeConfigsOnStartup(Map<String, Object> configs) throws IOException {
        for (Map.Entry<String, Object> entry : configs.entrySet()) {
            rootObjectNode.replace(entry.getKey(), mapper.valueToTree(entry.getValue()));
        }
        ConfigFileUtil.writeToFileIfNeeded(file, rootObjectNode, keyOrder, readOnly);
    }

    private static ObjectNode readRootObjectNode(File file) {
        ObjectNode rootObjectNode = ConfigFileUtil.getRootObjectNode(file);
        upgradeRolesIfNeeded(rootObjectNode);
        upgradeSmtpIfNeeded(rootObjectNode);
        upgradeHttpProxyIfNeeded(rootObjectNode);
        upgradeLdapIfNeeded(rootObjectNode);
        return rootObjectNode;
    }

    private static @Nullable File getAdminConfigDefaultFile(List<File> confDirs) {
        for (File confDir : confDirs) {
            File defaultFile = new File(confDir, "admin-default.json");
            if (defaultFile.exists()) {
                return defaultFile;
            }
        }
        return null;
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
            update(permissions, "agent:alert", "agent:incident");
            // upgrade from 0.10.12 to 0.11.0
            update(permissions, "agent:transaction:profile", "agent:transaction:threadProfile");
            update(permissions, "agent:config:edit:gauge", "agent:config:edit:gauges");
            update(permissions, "agent:config:edit:syntheticMonitor",
                    "agent:config:edit:syntheticMonitors");
            update(permissions, "agent:config:edit:alert", "agent:config:edit:alerts");
            update(permissions, "agent:config:edit:plugin", "agent:config:edit:plugins");
            update(permissions, "agent:config:edit:ui", "agent:config:edit:uiDefaults");
            permissionsArrayNode.removeAll();
            for (String permission : permissions) {
                permissionsArrayNode.add(new TextNode(permission));
            }
        }
    }

    private static void update(List<String> permissions, String from, String to) {
        int index = permissions.indexOf(from);
        if (index != -1) {
            permissions.set(index, to);
        }
    }

    private static void upgradeSmtpIfNeeded(ObjectNode adminRootObjectNode) {
        JsonNode smtpNode = adminRootObjectNode.get("smtp");
        if (smtpNode == null || !smtpNode.isObject()) {
            return;
        }
        ObjectNode smtpObjectNode = (ObjectNode) smtpNode;
        JsonNode sslNode = smtpObjectNode.remove("ssl");
        if (sslNode != null && sslNode.isBoolean() && sslNode.asBoolean()) {
            // upgrade from 0.9.19 to 0.9.20
            smtpObjectNode.put("connectionSecurity", "ssl-tls");
        }
        JsonNode passwordNode = smtpObjectNode.remove("password");
        if (passwordNode != null) {
            // upgrade from 0.11.1 to 0.12.0
            smtpObjectNode.set("encryptedPassword", passwordNode);
        }
    }

    private static void upgradeHttpProxyIfNeeded(ObjectNode adminRootObjectNode) {
        JsonNode httpProxyNode = adminRootObjectNode.get("httpProxy");
        if (httpProxyNode == null || !httpProxyNode.isObject()) {
            return;
        }
        ObjectNode httpProxyObjectNode = (ObjectNode) httpProxyNode;
        JsonNode passwordNode = httpProxyObjectNode.remove("password");
        if (passwordNode != null) {
            // upgrade from 0.11.1 to 0.12.0
            httpProxyObjectNode.set("encryptedPassword", passwordNode);
        }
    }

    private static void upgradeLdapIfNeeded(ObjectNode adminRootObjectNode) {
        JsonNode ldapNode = adminRootObjectNode.get("ldap");
        if (ldapNode == null || !ldapNode.isObject()) {
            return;
        }
        ObjectNode ldapObjectNode = (ObjectNode) ldapNode;
        JsonNode passwordNode = ldapObjectNode.remove("password");
        if (passwordNode != null) {
            // upgrade from 0.11.1 to 0.12.0
            ldapObjectNode.set("encryptedPassword", passwordNode);
        }
    }
}
