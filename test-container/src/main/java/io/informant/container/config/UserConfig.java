/**
 * Copyright 2011-2013 the original author or authors.
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

import static io.informant.container.common.ObjectMappers.checkRequiredProperty;

import checkers.nullness.quals.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.Objects;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class UserConfig {

    private boolean enabled;
    @Nullable
    private String userId;
    private int storeThresholdMillis;
    private boolean fineProfiling;

    private final String version;

    public UserConfig(String version) {
        this.version = version;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Nullable
    public String getUserId() {
        return userId;
    }

    public void setUserId(@Nullable String userId) {
        this.userId = userId;
    }

    public int getStoreThresholdMillis() {
        return storeThresholdMillis;
    }

    public void setStoreThresholdMillis(int storeThresholdMillis) {
        this.storeThresholdMillis = storeThresholdMillis;
    }

    public boolean isFineProfiling() {
        return fineProfiling;
    }

    public void setFineProfiling(boolean fineProfiling) {
        this.fineProfiling = fineProfiling;
    }

    public String getVersion() {
        return version;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof UserConfig) {
            UserConfig that = (UserConfig) obj;
            // intentionally leaving off version since it represents the prior version hash when
            // sending to the server, and represents the current version hash when receiving from
            // the server
            return Objects.equal(enabled, that.enabled)
                    && Objects.equal(userId, that.userId)
                    && Objects.equal(storeThresholdMillis, that.storeThresholdMillis)
                    && Objects.equal(fineProfiling, that.fineProfiling);
        }
        return false;
    }

    @Override
    public int hashCode() {
        // intentionally leaving off version since it represents the prior version hash when
        // sending to the server, and represents the current version hash when receiving from the
        // server
        return Objects.hashCode(enabled, userId, storeThresholdMillis, fineProfiling);
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("enabled", enabled)
                .add("userId", userId)
                .add("storeThresholdMillis", storeThresholdMillis)
                .add("fineProfiling", fineProfiling)
                .add("version", version)
                .toString();
    }

    @JsonCreator
    static UserConfig readValue(@JsonProperty("enabled") @Nullable Boolean enabled,
            @JsonProperty("userId") @Nullable String userId,
            @JsonProperty("storeThresholdMillis") @Nullable Integer storeThresholdMillis,
            @JsonProperty("fineProfiling") @Nullable Boolean fineProfiling,
            @JsonProperty("version") @Nullable String version) throws JsonMappingException {
        checkRequiredProperty(enabled, "enabled");
        checkRequiredProperty(storeThresholdMillis, "storeThresholdMillis");
        checkRequiredProperty(fineProfiling, "fineProfiling");
        checkRequiredProperty(version, "version");
        UserConfig config = new UserConfig(version);
        config.setEnabled(enabled);
        config.setUserId(userId);
        config.setStoreThresholdMillis(storeThresholdMillis);
        config.setFineProfiling(fineProfiling);
        return config;
    }
}
