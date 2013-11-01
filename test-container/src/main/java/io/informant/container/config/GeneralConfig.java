/*
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
public class GeneralConfig {

    private boolean enabled;
    private int storeThresholdMillis;
    private int stuckThresholdSeconds;
    private int maxSpans;

    private final String version;

    public GeneralConfig(String version) {
        this.version = version;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getStoreThresholdMillis() {
        return storeThresholdMillis;
    }

    public void setStoreThresholdMillis(int storeThresholdMillis) {
        this.storeThresholdMillis = storeThresholdMillis;
    }

    public int getStuckThresholdSeconds() {
        return stuckThresholdSeconds;
    }

    public void setStuckThresholdSeconds(int stuckThresholdSeconds) {
        this.stuckThresholdSeconds = stuckThresholdSeconds;
    }

    public int getMaxSpans() {
        return maxSpans;
    }

    public void setMaxSpans(int maxSpans) {
        this.maxSpans = maxSpans;
    }

    public String getVersion() {
        return version;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof GeneralConfig) {
            GeneralConfig that = (GeneralConfig) obj;
            // intentionally leaving off version since it represents the prior version hash when
            // sending to the server, and represents the current version hash when receiving from
            // the server
            return Objects.equal(enabled, that.enabled)
                    && Objects.equal(storeThresholdMillis, that.storeThresholdMillis)
                    && Objects.equal(stuckThresholdSeconds, that.stuckThresholdSeconds)
                    && Objects.equal(maxSpans, that.maxSpans);
        }
        return false;
    }

    @Override
    public int hashCode() {
        // intentionally leaving off version since it represents the prior version hash when
        // sending to the server, and represents the current version hash when receiving from the
        // server
        return Objects.hashCode(enabled, storeThresholdMillis, stuckThresholdSeconds, maxSpans);
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("enabled", enabled)
                .add("storeThresholdMillis", storeThresholdMillis)
                .add("stuckThresholdSeconds", stuckThresholdSeconds)
                .add("maxSpans", maxSpans)
                .add("version", version)
                .toString();
    }

    @JsonCreator
    static GeneralConfig readValue(
            @JsonProperty("enabled") @Nullable Boolean enabled,
            @JsonProperty("storeThresholdMillis") @Nullable Integer storeThresholdMillis,
            @JsonProperty("stuckThresholdSeconds") @Nullable Integer stuckThresholdSeconds,
            @JsonProperty("maxSpans") @Nullable Integer maxSpans,
            @JsonProperty("version") @Nullable String version) throws JsonMappingException {
        checkRequiredProperty(enabled, "enabled");
        checkRequiredProperty(storeThresholdMillis, "storeThresholdMillis");
        checkRequiredProperty(stuckThresholdSeconds, "stuckThresholdSeconds");
        checkRequiredProperty(maxSpans, "maxSpans");
        checkRequiredProperty(version, "version");
        GeneralConfig config = new GeneralConfig(version);
        config.setEnabled(enabled);
        config.setStoreThresholdMillis(storeThresholdMillis);
        config.setStuckThresholdSeconds(stuckThresholdSeconds);
        config.setMaxSpans(maxSpans);
        return config;
    }
}
