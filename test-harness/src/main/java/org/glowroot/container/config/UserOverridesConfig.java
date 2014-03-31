/*
 * Copyright 2011-2014 the original author or authors.
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

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.Objects;

import static org.glowroot.container.common.ObjectMappers.checkRequiredProperty;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class UserOverridesConfig {

    @Nullable
    private String user;
    private int storeThresholdMillis;
    private boolean fineProfiling;

    private final String version;

    public UserOverridesConfig(String version) {
        this.version = version;
    }

    @Nullable
    public String getUser() {
        return user;
    }

    public void setUser(@Nullable String user) {
        this.user = user;
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

    /*@Pure*/
    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof UserOverridesConfig) {
            UserOverridesConfig that = (UserOverridesConfig) obj;
            // intentionally leaving off version since it represents the prior version hash when
            // sending to the server, and represents the current version hash when receiving from
            // the server
            return Objects.equal(user, that.user)
                    && Objects.equal(storeThresholdMillis, that.storeThresholdMillis)
                    && Objects.equal(fineProfiling, that.fineProfiling);
        }
        return false;
    }

    /*@Pure*/
    @Override
    public int hashCode() {
        // intentionally leaving off version since it represents the prior version hash when
        // sending to the server, and represents the current version hash when receiving from the
        // server
        return Objects.hashCode(user, storeThresholdMillis, fineProfiling);
    }

    /*@Pure*/
    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("user", user)
                .add("storeThresholdMillis", storeThresholdMillis)
                .add("fineProfiling", fineProfiling)
                .add("version", version)
                .toString();
    }

    @JsonCreator
    static UserOverridesConfig readValue(@JsonProperty("user") @Nullable String user,
            @JsonProperty("storeThresholdMillis") @Nullable Integer storeThresholdMillis,
            @JsonProperty("fineProfiling") @Nullable Boolean fineProfiling,
            @JsonProperty("version") @Nullable String version) throws JsonMappingException {
        checkRequiredProperty(storeThresholdMillis, "storeThresholdMillis");
        checkRequiredProperty(fineProfiling, "fineProfiling");
        checkRequiredProperty(version, "version");
        UserOverridesConfig config = new UserOverridesConfig(version);
        config.setUser(user);
        config.setStoreThresholdMillis(storeThresholdMillis);
        config.setFineProfiling(fineProfiling);
        return config;
    }
}
