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
package org.glowroot.container.config;

import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.Objects;

import static org.glowroot.container.common.ObjectMappers.checkRequiredProperty;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class CoarseProfilingConfig {

    private boolean enabled;
    private int initialDelayMillis;
    private int intervalMillis;
    private int totalSeconds;

    private final String version;

    public CoarseProfilingConfig(String version) {
        this.version = version;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getInitialDelayMillis() {
        return initialDelayMillis;
    }

    public void setInitialDelayMillis(int initialDelayMillis) {
        this.initialDelayMillis = initialDelayMillis;
    }

    public int getIntervalMillis() {
        return intervalMillis;
    }

    public void setIntervalMillis(int intervalMillis) {
        this.intervalMillis = intervalMillis;
    }

    public int getTotalSeconds() {
        return totalSeconds;
    }

    public void setTotalSeconds(int totalSeconds) {
        this.totalSeconds = totalSeconds;
    }

    public String getVersion() {
        return version;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof CoarseProfilingConfig) {
            CoarseProfilingConfig that = (CoarseProfilingConfig) obj;
            // intentionally leaving off version since it represents the prior version hash when
            // sending to the server, and represents the current version hash when receiving from
            // the server
            return Objects.equal(enabled, that.enabled)
                    && Objects.equal(initialDelayMillis, that.initialDelayMillis)
                    && Objects.equal(intervalMillis, that.intervalMillis)
                    && Objects.equal(totalSeconds, that.totalSeconds);
        }
        return false;
    }

    @Override
    public int hashCode() {
        // intentionally leaving off version since it represents the prior version hash when
        // sending to the server, and represents the current version hash when receiving from the
        // server
        return Objects.hashCode(enabled, initialDelayMillis, intervalMillis, totalSeconds);
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("enabled", enabled)
                .add("initialDelayMillis", initialDelayMillis)
                .add("intervalMillis", intervalMillis)
                .add("totalSeconds", totalSeconds)
                .add("version", version)
                .toString();
    }

    @JsonCreator
    static CoarseProfilingConfig readValue(@JsonProperty("enabled") @Nullable Boolean enabled,
            @JsonProperty("initialDelayMillis") @Nullable Integer initialDelayMillis,
            @JsonProperty("intervalMillis") @Nullable Integer intervalMillis,
            @JsonProperty("totalSeconds") @Nullable Integer totalSeconds,
            @JsonProperty("version") @Nullable String version) throws JsonMappingException {
        checkRequiredProperty(enabled, "enabled");
        checkRequiredProperty(initialDelayMillis, "initialDelayMillis");
        checkRequiredProperty(intervalMillis, "intervalMillis");
        checkRequiredProperty(totalSeconds, "totalSeconds");
        checkRequiredProperty(version, "version");
        CoarseProfilingConfig config = new CoarseProfilingConfig(version);
        config.setEnabled(enabled);
        config.setInitialDelayMillis(initialDelayMillis);
        config.setIntervalMillis(intervalMillis);
        config.setTotalSeconds(totalSeconds);
        return config;
    }
}
