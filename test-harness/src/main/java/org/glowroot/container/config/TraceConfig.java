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

public class TraceConfig {

    private boolean enabled;
    private int storeThresholdMillis;
    private boolean outlierProfilingEnabled;
    private int outlierProfilingInitialDelayMillis;
    private int outlierProfilingIntervalMillis;

    private final String version;

    public TraceConfig(String version) {
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

    public boolean isOutlierProfilingEnabled() {
        return outlierProfilingEnabled;
    }

    public void setOutlierProfilingEnabled(boolean outlierProfilingEnabled) {
        this.outlierProfilingEnabled = outlierProfilingEnabled;
    }

    public int getOutlierProfilingInitialDelayMillis() {
        return outlierProfilingInitialDelayMillis;
    }

    public void setOutlierProfilingInitialDelayMillis(int outlierProfilingInitialDelayMillis) {
        this.outlierProfilingInitialDelayMillis = outlierProfilingInitialDelayMillis;
    }

    public int getOutlierProfilingIntervalMillis() {
        return outlierProfilingIntervalMillis;
    }

    public void setOutlierProfilingIntervalMillis(int outlierProfilingIntervalMillis) {
        this.outlierProfilingIntervalMillis = outlierProfilingIntervalMillis;
    }

    public String getVersion() {
        return version;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof TraceConfig) {
            TraceConfig that = (TraceConfig) obj;
            // intentionally leaving off version since it represents the prior version hash when
            // sending to the server, and represents the current version hash when receiving from
            // the server
            return Objects.equal(enabled, that.enabled)
                    && Objects.equal(storeThresholdMillis, that.storeThresholdMillis)
                    && Objects.equal(outlierProfilingEnabled, that.outlierProfilingEnabled)
                    && Objects.equal(outlierProfilingInitialDelayMillis,
                            that.outlierProfilingInitialDelayMillis)
                    && Objects.equal(outlierProfilingIntervalMillis,
                            that.outlierProfilingIntervalMillis);
        }
        return false;
    }

    @Override
    public int hashCode() {
        // intentionally leaving off version since it represents the prior version hash when
        // sending to the server, and represents the current version hash when receiving from the
        // server
        return Objects.hashCode(enabled, storeThresholdMillis, outlierProfilingEnabled,
                outlierProfilingInitialDelayMillis, outlierProfilingIntervalMillis);
    }
    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("enabled", enabled)
                .add("storeThresholdMillis", storeThresholdMillis)
                .add("outlierProfilingEnabled", outlierProfilingEnabled)
                .add("outlierProfilingInitialDelayMillis", outlierProfilingInitialDelayMillis)
                .add("outlierProfilingIntervalMillis", outlierProfilingIntervalMillis)
                .add("version", version)
                .toString();
    }

    @JsonCreator
    static TraceConfig readValue(
            @JsonProperty("enabled") @Nullable Boolean enabled,
            @JsonProperty("storeThresholdMillis") @Nullable Integer storeThresholdMillis,
            @JsonProperty("outlierProfilingEnabled") @Nullable Boolean outlierProfilingEnabled,
            @JsonProperty("outlierProfilingInitialDelayMillis") @Nullable Integer outlierProfilingInitialDelayMillis,
            @JsonProperty("outlierProfilingIntervalMillis") @Nullable Integer outlierProfilingIntervalMillis,
            @JsonProperty("version") @Nullable String version) throws JsonMappingException {
        checkRequiredProperty(enabled, "enabled");
        checkRequiredProperty(storeThresholdMillis, "storeThresholdMillis");
        checkRequiredProperty(outlierProfilingEnabled, "outlierProfilingEnabled");
        checkRequiredProperty(outlierProfilingInitialDelayMillis,
                "outlierProfilingInitialDelayMillis");
        checkRequiredProperty(outlierProfilingIntervalMillis, "outlierProfilingIntervalMillis");
        checkRequiredProperty(version, "version");
        TraceConfig config = new TraceConfig(version);
        config.setEnabled(enabled);
        config.setStoreThresholdMillis(storeThresholdMillis);
        config.setOutlierProfilingEnabled(outlierProfilingEnabled);
        config.setOutlierProfilingInitialDelayMillis(outlierProfilingInitialDelayMillis);
        config.setOutlierProfilingIntervalMillis(outlierProfilingIntervalMillis);
        return config;
    }
}
