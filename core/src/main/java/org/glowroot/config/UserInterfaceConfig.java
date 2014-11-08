/*
 * Copyright 2013-2014 the original author or authors.
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
package org.glowroot.config;

import java.security.GeneralSecurityException;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonView;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.config.JsonViews.FileView;
import org.glowroot.config.JsonViews.UiView;
import org.glowroot.markers.Immutable;
import org.glowroot.markers.UsedByJsonBinding;

@Immutable
public class UserInterfaceConfig {

    private final String defaultTransactionType;
    private final int port;
    private final String passwordHash;
    // timeout 0 means sessions do not time out (except on jvm restart)
    private final int sessionTimeoutMinutes;

    private final String version;

    static UserInterfaceConfig getDefault(List<PluginDescriptor> pluginDescriptors) {
        String defaultTransactionType = "";
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            ImmutableList<String> transactionTypes = pluginDescriptor.getTransactionTypes();
            if (!transactionTypes.isEmpty()) {
                defaultTransactionType = transactionTypes.get(0);
                break;
            }
        }
        final int port = 4000;
        final String passwordHash = "";
        final int sessionTimeoutMinutes = 30;
        return new UserInterfaceConfig(defaultTransactionType, port, passwordHash,
                sessionTimeoutMinutes);
    }

    public static Overlay overlay(UserInterfaceConfig base) {
        return new Overlay(base);
    }

    static FileOverlay fileOverlay(UserInterfaceConfig base) {
        return new FileOverlay(base);
    }

    private UserInterfaceConfig(String defaultTransactionType, int port, String passwordHash,
            int sessionTimeoutMinutes) {
        this.defaultTransactionType = defaultTransactionType;
        this.port = port;
        this.passwordHash = passwordHash;
        this.sessionTimeoutMinutes = sessionTimeoutMinutes;
        this.version = VersionHashes.sha1(defaultTransactionType, port, passwordHash,
                sessionTimeoutMinutes);
    }

    public String getDefaultTransactionType() {
        return defaultTransactionType;
    }

    public int getPort() {
        return port;
    }

    public int getSessionTimeoutMinutes() {
        return sessionTimeoutMinutes;
    }

    @JsonView(UiView.class)
    public boolean isPasswordEnabled() {
        return !passwordHash.isEmpty();
    }

    @JsonView(FileView.class)
    public String getPasswordHash() {
        return passwordHash;
    }

    public boolean validatePassword(String password) throws GeneralSecurityException {
        if (passwordHash.isEmpty()) {
            // need special case for empty password
            return password.isEmpty();
        } else {
            return PasswordHash.validatePassword(password, passwordHash);
        }
    }

    @JsonView(UiView.class)
    public String getVersion() {
        return version;
    }

    @Override
    public String toString() {
        // don't expose passwordHash
        return MoreObjects.toStringHelper(this)
                .add("defaultTransactionType", defaultTransactionType)
                .add("port", port)
                .add("passwordEnabled", isPasswordEnabled())
                .add("sessionTimeoutMinutes", sessionTimeoutMinutes)
                .add("version", version)
                .toString();
    }

    // for overlaying values on top of another config using ObjectMapper.readerForUpdating()
    @UsedByJsonBinding
    static class FileOverlay {

        private String defaultTransactionType;
        private int port;
        private String passwordHash;
        private int sessionTimeoutMinutes;

        private FileOverlay(UserInterfaceConfig base) {
            defaultTransactionType = base.defaultTransactionType;
            port = base.port;
            passwordHash = base.passwordHash;
            sessionTimeoutMinutes = base.sessionTimeoutMinutes;
        }
        void setDefaultTransactionType(String defaultTransactionType) {
            this.defaultTransactionType = defaultTransactionType;
        }
        void setPort(int port) {
            this.port = port;
        }
        void setPasswordHash(String passwordHash) {
            this.passwordHash = passwordHash;
        }
        void setSessionTimeoutMinutes(int sessionTimeoutMinutes) {
            this.sessionTimeoutMinutes = sessionTimeoutMinutes;
        }
        UserInterfaceConfig build() {
            return new UserInterfaceConfig(defaultTransactionType, port, passwordHash,
                    sessionTimeoutMinutes);
        }
    }

    // for overlaying values on top of another config using ObjectMapper.readerForUpdating()
    @UsedByJsonBinding
    public static class Overlay {

        private final String originalPasswordHash;

        private String defaultTransactionType;
        private int port;
        @Nullable
        private String currentPassword;
        @Nullable
        private String newPassword;
        private int sessionTimeoutMinutes;

        private Overlay(UserInterfaceConfig base) {
            originalPasswordHash = base.passwordHash;
            defaultTransactionType = base.defaultTransactionType;
            port = base.port;
            sessionTimeoutMinutes = base.sessionTimeoutMinutes;
        }
        public void setDefaultTransactionType(String defaultTransactionType) {
            this.defaultTransactionType = defaultTransactionType;
        }
        public void setPort(int port) {
            this.port = port;
        }
        public void setCurrentPassword(@Nullable String currentPassword) {
            this.currentPassword = currentPassword;
        }
        public void setNewPassword(@Nullable String newPassword) {
            this.newPassword = newPassword;
        }
        public void setSessionTimeoutMinutes(int sessionTimeoutMinutes) {
            this.sessionTimeoutMinutes = sessionTimeoutMinutes;
        }
        public UserInterfaceConfig build() throws GeneralSecurityException,
                CurrentPasswordIncorrectException {
            String passwordHash;
            String currentPasswordLocal = currentPassword;
            String newPasswordLocal = newPassword;
            if (currentPasswordLocal != null && newPasswordLocal != null) {
                passwordHash = verifyAndGenerateNewPasswordHash(currentPasswordLocal,
                        newPasswordLocal, originalPasswordHash);
            } else {
                // no change
                passwordHash = originalPasswordHash;
            }
            return new UserInterfaceConfig(defaultTransactionType, port, passwordHash,
                    sessionTimeoutMinutes);
        }
        private static String verifyAndGenerateNewPasswordHash(String currentPassword,
                String newPassword, String originalPasswordHash) throws GeneralSecurityException,
                CurrentPasswordIncorrectException {
            if (currentPassword.isEmpty() && !newPassword.isEmpty()) {
                // enabling password
                if (!originalPasswordHash.isEmpty()) {
                    // UI validation prevents this from happening
                    throw new IllegalStateException("Password is already enabled");
                }
                return PasswordHash.createHash(newPassword);
            } else if (!currentPassword.isEmpty() && newPassword.isEmpty()) {
                // disabling password
                if (!PasswordHash.validatePassword(currentPassword, originalPasswordHash)) {
                    throw new CurrentPasswordIncorrectException();
                }
                return "";
            } else if (currentPassword.isEmpty() && newPassword.isEmpty()) {
                // UI validation prevents this from happening
                throw new IllegalStateException("Current and new password are both empty");
            } else {
                // changing password
                if (!PasswordHash.validatePassword(currentPassword, originalPasswordHash)) {
                    throw new CurrentPasswordIncorrectException();
                }
                return PasswordHash.createHash(newPassword);
            }
        }
    }

    @SuppressWarnings("serial")
    public static class CurrentPasswordIncorrectException extends Exception {}
}
