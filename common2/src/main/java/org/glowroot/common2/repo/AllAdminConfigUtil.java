/*
 * Copyright 2019 the original author or authors.
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
package org.glowroot.common2.repo;

import java.security.GeneralSecurityException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.common2.repo.util.Encryption;
import org.glowroot.common2.repo.util.LazySecretKey;

import static com.google.common.base.Preconditions.checkNotNull;

public class AllAdminConfigUtil {

    public static void updatePasswords(JsonNode rootNode, ConfigRepository configRepository)
            throws Exception {
        LazySecretKey lazySecretKey = configRepository.getLazySecretKey();
        updateEncryptedPassword((ObjectNode) rootNode.get("smtp"),
                configRepository.getSmtpConfig().encryptedPassword(), lazySecretKey);
        updateEncryptedPassword((ObjectNode) rootNode.get("httpProxy"),
                configRepository.getHttpProxyConfig().encryptedPassword(), lazySecretKey);
        updateEncryptedPassword((ObjectNode) rootNode.get("ldap"),
                configRepository.getLdapConfig().encryptedPassword(), lazySecretKey);
        JsonNode usersNode = rootNode.get("users");
        if (usersNode instanceof ArrayNode) {
            for (JsonNode user : usersNode) {
                updateHashedPassword((ObjectNode) user);
            }
        }
    }

    public static void removePasswords(ObjectNode rootNode) {
        ((ObjectNode) checkNotNull(rootNode.get("smtp"))).remove("encryptedPassword");
        ((ObjectNode) checkNotNull(rootNode.get("httpProxy"))).remove("encryptedPassword");
        ((ObjectNode) checkNotNull(rootNode.get("ldap"))).remove("encryptedPassword");
    }

    private static void updateEncryptedPassword(@Nullable ObjectNode objectNode,
            String existingEncryptedPassword, LazySecretKey lazySecretKey) throws Exception {
        if (objectNode == null) {
            return;
        }
        // FIXME remove()??
        JsonNode passwordNode = objectNode.get("password");
        if (passwordNode == null) {
            JsonNode encryptedPasswordNode = objectNode.get("encryptedPassword");
            if (encryptedPasswordNode == null) {
                objectNode.put("encryptedPassword", existingEncryptedPassword);
            }
        } else {
            objectNode.put("encryptedPassword", Encryption.encrypt(passwordNode.asText(),
                    lazySecretKey));
        }
    }

    private static void updateHashedPassword(ObjectNode objectNode)
            throws GeneralSecurityException {
        JsonNode passwordNode = objectNode.remove("password");
        if (passwordNode == null) {
            return;
        }
        if (objectNode.path("ldap").asBoolean()) {
            throw new IllegalStateException("Password not allowed when ldap is true");
        }
        if (objectNode.path("username").asText().equalsIgnoreCase("anonymous")) {
            throw new IllegalStateException("Password not allowed for anonymous user");
        }
        objectNode.put("passwordHash", PasswordHash.createHash(passwordNode.asText()));
    }
}
