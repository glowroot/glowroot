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
package io.informant.container.config;

import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.Objects;

import static io.informant.container.common.ObjectMappers.checkRequiredProperty;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class UserInterfaceConfig {

    private boolean passwordEnabled;
    private int sessionTimeoutMinutes;

    // used for submitting a password change
    private String currentPassword;
    // used for submitting a password change
    private String newPassword;

    private final String version;

    public UserInterfaceConfig(String version) {
        this.version = version;
    }

    public int getSessionTimeoutMinutes() {
        return sessionTimeoutMinutes;
    }

    public void setSessionTimeoutMinutes(int sessionTimeoutMinutes) {
        this.sessionTimeoutMinutes = sessionTimeoutMinutes;
    }

    public boolean isPasswordEnabled() {
        return passwordEnabled;
    }

    public void setPasswordEnabled(boolean passwordEnabled) {
        this.passwordEnabled = passwordEnabled;
    }

    public String getCurrentPassword() {
        return currentPassword;
    }

    public void setCurrentPassword(String currentPassword) {
        this.currentPassword = currentPassword;
    }

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
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof UserInterfaceConfig) {
            UserInterfaceConfig that = (UserInterfaceConfig) obj;
            // intentionally leaving off version since it represents the prior version hash when
            // sending to the server, and represents the current version hash when receiving from
            // the server
            return Objects.equal(passwordEnabled, that.passwordEnabled)
                    && Objects.equal(sessionTimeoutMinutes, that.sessionTimeoutMinutes);
        }
        return false;
    }

    @Override
    public int hashCode() {
        // intentionally leaving off version since it represents the prior version hash when
        // sending to the server, and represents the current version hash when receiving from the
        // server
        return Objects.hashCode(passwordEnabled, sessionTimeoutMinutes);
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("passwordEnabled", passwordEnabled)
                .add("sessionTimeoutMinutes", sessionTimeoutMinutes)
                .add("version", version)
                .toString();
    }

    @JsonCreator
    static UserInterfaceConfig readValue(
            @JsonProperty("passwordEnabled") @Nullable Boolean passwordEnabled,
            @JsonProperty("sessionTimeoutMinutes") @Nullable Integer sessionTimeoutMinutes,
            @JsonProperty("version") @Nullable String version) throws JsonMappingException {
        checkRequiredProperty(passwordEnabled, "passwordEnabled");
        checkRequiredProperty(sessionTimeoutMinutes, "sessionTimeoutMinutes");
        checkRequiredProperty(version, "version");
        UserInterfaceConfig config = new UserInterfaceConfig(version);
        config.setPasswordEnabled(passwordEnabled);
        config.setSessionTimeoutMinutes(sessionTimeoutMinutes);
        return config;
    }
}
