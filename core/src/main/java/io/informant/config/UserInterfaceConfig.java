/*
 * Copyright 2013 the original author or authors.
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
package io.informant.config;

import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;

import checkers.igj.quals.Immutable;
import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.annotation.JsonView;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.base.Strings;

import io.informant.config.JsonViews.FileView;
import io.informant.config.JsonViews.UiView;

/**
 * Immutable structure to hold the user interface config.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class UserInterfaceConfig {

    private final int port;
    private final boolean passwordEnabled;
    // timeout 0 means sessions do not time out (except on jvm restart)
    private final int sessionTimeoutMinutes;
    private final String passwordHash;

    private final String version;

    static UserInterfaceConfig getDefault() {
        final int port = 4000;
        final boolean passwordEnabled = false;
        final int sessionTimeoutMinutes = 30;
        final String passwordHash = "";
        return new UserInterfaceConfig(port, passwordEnabled, sessionTimeoutMinutes, passwordHash);
    }

    public static FileOverlay fileOverlay(UserInterfaceConfig base) {
        return new FileOverlay(base);
    }

    public static Overlay overlay(UserInterfaceConfig base) {
        return new Overlay(base);
    }

    @VisibleForTesting
    public UserInterfaceConfig(int port, boolean passwordEnabled, int sessionTimeoutMinutes,
            String passwordHash) {
        this.port = port;
        this.passwordEnabled = passwordEnabled;
        this.sessionTimeoutMinutes = sessionTimeoutMinutes;
        this.passwordHash = passwordHash;
        this.version = VersionHashes.sha1(sessionTimeoutMinutes, passwordHash);
    }

    public int getPort() {
        return port;
    }

    public int getSessionTimeoutMinutes() {
        return sessionTimeoutMinutes;
    }

    public boolean isPasswordEnabled() {
        return !passwordHash.equals("");
    }

    @JsonView(FileView.class)
    private String getPasswordHash() {
        return passwordHash;
    }

    public boolean validatePassword(String password)
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        if (passwordHash.equals("")) {
            // need special case for empty password
            return password.equals("");
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
        return Objects.toStringHelper(this)
                .add("sessionTimeoutMinutes", sessionTimeoutMinutes)
                .add("passwordEnabled", isPasswordEnabled())
                .add("version", version)
                .toString();
    }

    // for overlaying values on top of another config using ObjectMapper.readerForUpdating()
    public static class FileOverlay {

        private int port;
        private boolean passwordEnabled;
        private int sessionTimeoutMinutes;
        private String passwordHash;

        private FileOverlay(UserInterfaceConfig base) {
            port = base.port;
            passwordEnabled = base.passwordEnabled;
            sessionTimeoutMinutes = base.sessionTimeoutMinutes;
            passwordHash = base.passwordHash;
        }
        public void setPort(int port) {
            this.port = port;
        }
        public void setPasswordEnabled(boolean passwordEnabled) {
            this.passwordEnabled = passwordEnabled;
        }
        public void setSessionTimeoutMinutes(int sessionTimeoutMinutes) {
            this.sessionTimeoutMinutes = sessionTimeoutMinutes;
        }
        public void setPasswordHash(String passwordHash) {
            this.passwordHash = passwordHash;
        }
        public UserInterfaceConfig build() {
            return new UserInterfaceConfig(port, passwordEnabled, sessionTimeoutMinutes,
                    passwordHash);
        }
    }

    // for overlaying values on top of another config using ObjectMapper.readerForUpdating()
    public static class Overlay {

        private final boolean originalPasswordEnabled;
        private final String originalPasswordHash;

        private int port;
        private boolean passwordEnabled;
        private int sessionTimeoutMinutes;

        @Nullable
        private String currentPassword;
        @Nullable
        private String newPassword;

        private Overlay(UserInterfaceConfig base) {
            port = base.port;
            originalPasswordEnabled = base.passwordEnabled;
            originalPasswordHash = base.passwordHash;
            passwordEnabled = base.passwordEnabled;
            sessionTimeoutMinutes = base.sessionTimeoutMinutes;
        }
        public void setPort(int port) {
            this.port = port;
        }
        public void setPasswordEnabled(boolean passwordEnabled) {
            this.passwordEnabled = passwordEnabled;
        }
        public void setSessionTimeoutMinutes(int sessionTimeoutMinutes) {
            this.sessionTimeoutMinutes = sessionTimeoutMinutes;
        }
        public void setCurrentPassword(String currentPassword) {
            this.currentPassword = currentPassword;
        }
        public void setNewPassword(String newPassword) {
            this.newPassword = newPassword;
        }
        public UserInterfaceConfig build() throws NoSuchAlgorithmException,
                InvalidKeySpecException, CurrentPasswordIncorrectException {
            String passwordHash;
            if (!originalPasswordEnabled && passwordEnabled) {
                // enabling password
                if (Strings.isNullOrEmpty(newPassword)) {
                    // UI validation prevents this from happening
                    throw new IllegalStateException("When enabling password, newPassword property"
                            + " is required");
                }
                passwordHash = PasswordHash.createHash(newPassword);
            } else if (originalPasswordEnabled && !passwordEnabled) {
                // disabling password
                if (Strings.isNullOrEmpty(currentPassword)) {
                    // UI validation prevents this from happening
                    throw new IllegalStateException("When disabling password, currentPassword"
                            + " property is required");
                }
                if (!PasswordHash.validatePassword(currentPassword, originalPasswordHash)) {
                    throw new CurrentPasswordIncorrectException();
                }
                passwordHash = "";
            } else if (passwordEnabled && newPassword != null) {
                // changing password
                if (Strings.isNullOrEmpty(currentPassword) || Strings.isNullOrEmpty(newPassword)) {
                    // UI validation prevents this from happening
                    throw new IllegalStateException("When changing the password, both"
                            + " currentPassword and newPassword properties are required");
                }
                if (!PasswordHash.validatePassword(currentPassword, originalPasswordHash)) {
                    throw new CurrentPasswordIncorrectException();
                }
                passwordHash = PasswordHash.createHash(newPassword);
            } else {
                // no change
                passwordHash = originalPasswordHash;
            }
            return new UserInterfaceConfig(port, passwordEnabled, sessionTimeoutMinutes,
                    passwordHash);
        }
    }

    @SuppressWarnings("serial")
    public static class CurrentPasswordIncorrectException extends Exception {}
}
