/*
 * Copyright 2011-2015 the original author or authors.
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
package org.glowroot.agent.harness.config;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Objects;

public class UserRecordingConfig {

    private boolean enabled;
    private @Nullable String user;
    private int profileIntervalMillis;

    private final String version;

    @JsonCreator
    private UserRecordingConfig(@JsonProperty("version") String version) {
        this.version = version;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public @Nullable String getUser() {
        return user;
    }

    public void setUser(@Nullable String user) {
        this.user = user;
    }

    public int getProfileIntervalMillis() {
        return profileIntervalMillis;
    }

    public void setProfileIntervalMillis(int profileIntervalMillis) {
        this.profileIntervalMillis = profileIntervalMillis;
    }

    public String getVersion() {
        return version;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof UserRecordingConfig) {
            UserRecordingConfig that = (UserRecordingConfig) obj;
            // intentionally leaving off version since it represents the prior version hash when
            // sending to the server, and represents the current version hash when receiving from
            // the server
            return Objects.equal(enabled, that.enabled)
                    && Objects.equal(user, that.user)
                    && Objects.equal(profileIntervalMillis, that.profileIntervalMillis);
        }
        return false;
    }

    @Override
    public int hashCode() {
        // intentionally leaving off version since it represents the prior version hash when
        // sending to the server, and represents the current version hash when receiving from the
        // server
        return Objects.hashCode(enabled, user, profileIntervalMillis);
    }
}
