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
package org.glowroot.config;

import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;

import checkers.igj.quals.Immutable;
import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.annotation.JsonView;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;

import org.glowroot.config.JsonViews.FileView;
import org.glowroot.config.JsonViews.UiView;
import org.glowroot.markers.UsedByJsonBinding;

/**
 * Immutable structure to hold the user interface config.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class UserInterfaceConfig {

    private final int port;
    // timeout 0 means sessions do not time out (except on jvm restart)
    private final int sessionTimeoutMinutes;
    private final String passwordHash;

    private final String version;

    static UserInterfaceConfig getDefault() {
        final int port = 4000;
        final int sessionTimeoutMinutes = 30;
        final String passwordHash = "";
        return new UserInterfaceConfig(port, sessionTimeoutMinutes, passwordHash);
    }

    public static Overlay overlay(UserInterfaceConfig base) {
        return new Overlay(base);
    }

    static FileOverlay fileOverlay(UserInterfaceConfig base) {
        return new FileOverlay(base);
    }

    @VisibleForTesting
    public UserInterfaceConfig(int port, int sessionTimeoutMinutes, String passwordHash) {
        this.port = port;
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

    @JsonView(UiView.class)
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
    @UsedByJsonBinding
    static class FileOverlay {

        private int port;
        private int sessionTimeoutMinutes;
        private String passwordHash;

        private FileOverlay(UserInterfaceConfig base) {
            port = base.port;
            sessionTimeoutMinutes = base.sessionTimeoutMinutes;
            passwordHash = base.passwordHash;
        }
        void setPort(int port) {
            this.port = port;
        }
        void setSessionTimeoutMinutes(int sessionTimeoutMinutes) {
            this.sessionTimeoutMinutes = sessionTimeoutMinutes;
        }
        void setPasswordHash(String passwordHash) {
            this.passwordHash = passwordHash;
        }
        UserInterfaceConfig build() {
            return new UserInterfaceConfig(port, sessionTimeoutMinutes, passwordHash);
        }
    }

    // for overlaying values on top of another config using ObjectMapper.readerForUpdating()
    @UsedByJsonBinding
    public static class Overlay {

        private final String originalPasswordHash;

        private int port;
        private int sessionTimeoutMinutes;

        @Nullable
        private String currentPassword;
        @Nullable
        private String newPassword;

        private Overlay(UserInterfaceConfig base) {
            port = base.port;
            originalPasswordHash = base.passwordHash;
            sessionTimeoutMinutes = base.sessionTimeoutMinutes;
        }
        public void setPort(int port) {
            this.port = port;
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
            if (currentPassword != null && newPassword != null) {
                passwordHash = verifyAndGenerateNewPasswordHash(currentPassword, newPassword,
                        originalPasswordHash);
            } else {
                // no change
                passwordHash = originalPasswordHash;
            }
            return new UserInterfaceConfig(port, sessionTimeoutMinutes, passwordHash);
        }
        private static String verifyAndGenerateNewPasswordHash(String currentPassword,
                @Nullable String newPassword, String originalPasswordHash)
                throws NoSuchAlgorithmException, InvalidKeySpecException,
                CurrentPasswordIncorrectException {

            if (currentPassword.equals("") && !newPassword.equals("")) {
                // enabling password
                return PasswordHash.createHash(newPassword);
            } else if (!currentPassword.equals("") && newPassword.equals("")) {
                // disabling password
                if (!PasswordHash.validatePassword(currentPassword, originalPasswordHash)) {
                    throw new CurrentPasswordIncorrectException();
                }
                return "";
            } else if (currentPassword.equals("") && newPassword.equals("")) {
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
