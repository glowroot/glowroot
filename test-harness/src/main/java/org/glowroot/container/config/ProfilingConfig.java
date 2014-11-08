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
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;

import static org.glowroot.container.common.ObjectMappers.checkRequiredProperty;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ProfilingConfig {

    private boolean enabled;
    private int intervalMillis;

    private final String version;

    public ProfilingConfig(String version) {
        this.version = version;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getIntervalMillis() {
        return intervalMillis;
    }

    public void setIntervalMillis(int intervalMillis) {
        this.intervalMillis = intervalMillis;
    }

    public String getVersion() {
        return version;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof ProfilingConfig) {
            ProfilingConfig that = (ProfilingConfig) obj;
            // intentionally leaving off version since it represents the prior version hash when
            // sending to the server, and represents the current version hash when receiving from
            // the server
            return Objects.equal(enabled, that.enabled)
                    && Objects.equal(intervalMillis, that.intervalMillis);
        }
        return false;
    }

    @Override
    public int hashCode() {
        // intentionally leaving off version since it represents the prior version hash when
        // sending to the server, and represents the current version hash when receiving from the
        // server
        return Objects.hashCode(enabled, intervalMillis);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("enabled", enabled)
                .add("intervalMillis", intervalMillis)
                .add("version", version)
                .toString();
    }

    @JsonCreator
    static ProfilingConfig readValue(
            @JsonProperty("enabled") @Nullable Boolean enabled,
            @JsonProperty("intervalMillis") @Nullable Integer intervalMillis,
            @JsonProperty("version") @Nullable String version) throws JsonMappingException {
        checkRequiredProperty(enabled, "enabled");
        checkRequiredProperty(intervalMillis, "intervalMillis");
        checkRequiredProperty(version, "version");
        ProfilingConfig config = new ProfilingConfig(version);
        config.setEnabled(enabled);
        config.setIntervalMillis(intervalMillis);
        return config;
    }
}
