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

import com.fasterxml.jackson.annotation.JsonCreator;
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
public class OutlierProfilingConfig {

    private boolean enabled;
    private int initialDelayMillis;
    private int intervalMillis;
    private int maxSeconds;

    private final String version;

    public OutlierProfilingConfig(String version) {
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

    public int getMaxSeconds() {
        return maxSeconds;
    }

    public void setMaxSeconds(int maxSeconds) {
        this.maxSeconds = maxSeconds;
    }

    public String getVersion() {
        return version;
    }

    @Override
    @Pure
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof OutlierProfilingConfig) {
            OutlierProfilingConfig that = (OutlierProfilingConfig) obj;
            // intentionally leaving off version since it represents the prior version hash when
            // sending to the server, and represents the current version hash when receiving from
            // the server
            return Objects.equal(enabled, that.enabled)
                    && Objects.equal(initialDelayMillis, that.initialDelayMillis)
                    && Objects.equal(intervalMillis, that.intervalMillis)
                    && Objects.equal(maxSeconds, that.maxSeconds);
        }
        return false;
    }

    @Override
    @Pure
    public int hashCode() {
        // intentionally leaving off version since it represents the prior version hash when
        // sending to the server, and represents the current version hash when receiving from the
        // server
        return Objects.hashCode(enabled, initialDelayMillis, intervalMillis, maxSeconds);
    }

    @Override
    @Pure
    public String toString() {
        return Objects.toStringHelper(this)
                .add("enabled", enabled)
                .add("initialDelayMillis", initialDelayMillis)
                .add("intervalMillis", intervalMillis)
                .add("maxSeconds", maxSeconds)
                .add("version", version)
                .toString();
    }

    @JsonCreator
    static OutlierProfilingConfig readValue(@JsonProperty("enabled") @Nullable Boolean enabled,
            @JsonProperty("initialDelayMillis") @Nullable Integer initialDelayMillis,
            @JsonProperty("intervalMillis") @Nullable Integer intervalMillis,
            @JsonProperty("maxSeconds") @Nullable Integer maxSeconds,
            @JsonProperty("version") @Nullable String version) throws JsonMappingException {
        checkRequiredProperty(enabled, "enabled");
        checkRequiredProperty(initialDelayMillis, "initialDelayMillis");
        checkRequiredProperty(intervalMillis, "intervalMillis");
        checkRequiredProperty(maxSeconds, "maxSeconds");
        checkRequiredProperty(version, "version");
        OutlierProfilingConfig config = new OutlierProfilingConfig(version);
        config.setEnabled(enabled);
        config.setInitialDelayMillis(initialDelayMillis);
        config.setIntervalMillis(intervalMillis);
        config.setMaxSeconds(maxSeconds);
        return config;
    }
}
