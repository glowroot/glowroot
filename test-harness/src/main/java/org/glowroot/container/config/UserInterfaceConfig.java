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
package org.glowroot.container.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;

import static org.glowroot.container.common.ObjectMappers.checkRequiredProperty;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class UserInterfaceConfig {

    private int port;
    private final boolean passwordEnabled;
    private int sessionTimeoutMinutes;

    // used for submitting a password change
    @Nullable
    private String currentPassword;
    // used for submitting a password change
    @Nullable
    private String newPassword;

    private final String version;

    public UserInterfaceConfig(boolean passwordEnabled, String version) {
        this.passwordEnabled = passwordEnabled;
        this.version = version;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getSessionTimeoutMinutes() {
        return sessionTimeoutMinutes;
    }

    public void setSessionTimeoutMinutes(int sessionTimeoutMinutes) {
        this.sessionTimeoutMinutes = sessionTimeoutMinutes;
    }

    @JsonIgnore
    public boolean isPasswordEnabled() {
        return passwordEnabled;
    }

    @Nullable
    public String getCurrentPassword() {
        return currentPassword;
    }

    public void setCurrentPassword(String currentPassword) {
        this.currentPassword = currentPassword;
    }

    @Nullable
    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }

    public String getVersion() {
        return version;
    }

    @Override
    @Pure
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof UserInterfaceConfig) {
            UserInterfaceConfig that = (UserInterfaceConfig) obj;
            // intentionally leaving off version since it represents the prior version hash when
            // sending to the server, and represents the current version hash when receiving from
            // the server
            //
            // also intentionally leaving off currentPassword and newPassword since those are just
            // used as a temporary messaging mechanism
            return Objects.equal(port, that.port)
                    && Objects.equal(passwordEnabled, that.passwordEnabled)
                    && Objects.equal(sessionTimeoutMinutes, that.sessionTimeoutMinutes);
        }
        return false;
    }

    @Override
    @Pure
    public int hashCode() {
        // intentionally leaving off version since it represents the prior version hash when
        // sending to the server, and represents the current version hash when receiving from the
        // server
        //
        // also intentionally leaving off currentPassword and newPassword since those are just used
        // as a temporary messaging mechanism
        return Objects.hashCode(port, passwordEnabled, sessionTimeoutMinutes);
    }

    @Override
    @Pure
    public String toString() {
        // leaving off currentPassword and newPassword since those are plain text passwords
        return Objects.toStringHelper(this)
                .add("port", port)
                .add("passwordEnabled", passwordEnabled)
                .add("sessionTimeoutMinutes", sessionTimeoutMinutes)
                .add("version", version)
                .toString();
    }

    @JsonCreator
    static UserInterfaceConfig readValue(
            @JsonProperty("port") @Nullable Integer port,
            @JsonProperty("passwordEnabled") @Nullable Boolean passwordEnabled,
            @JsonProperty("sessionTimeoutMinutes") @Nullable Integer sessionTimeoutMinutes,
            @JsonProperty("version") @Nullable String version) throws JsonMappingException {
        checkRequiredProperty(port, "port");
        checkRequiredProperty(passwordEnabled, "passwordEnabled");
        checkRequiredProperty(sessionTimeoutMinutes, "sessionTimeoutMinutes");
        checkRequiredProperty(version, "version");
        UserInterfaceConfig config = new UserInterfaceConfig(passwordEnabled, version);
        config.setPort(port);
        config.setSessionTimeoutMinutes(sessionTimeoutMinutes);
        return config;
    }
}
