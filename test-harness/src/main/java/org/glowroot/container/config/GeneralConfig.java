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
package org.glowroot.container.config;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

import static org.glowroot.container.common.ObjectMappers.checkRequiredProperty;

public class GeneralConfig {

    private boolean enabled;
    private int traceStoreThresholdMillis;
    private int profilingIntervalMillis;
    private String defaultTransactionType = "";

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

    public int getTraceStoreThresholdMillis() {
        return traceStoreThresholdMillis;
    }

    public void setTraceStoreThresholdMillis(int traceStoreThresholdMillis) {
        this.traceStoreThresholdMillis = traceStoreThresholdMillis;
    }

    public int getProfilingIntervalMillis() {
        return profilingIntervalMillis;
    }

    public void setProfilingIntervalMillis(int profilingIntervalMillis) {
        this.profilingIntervalMillis = profilingIntervalMillis;
    }

    public String getDefaultTransactionType() {
        return defaultTransactionType;
    }

    public void setDefaultTransactionType(String defaultTransactionType) {
        this.defaultTransactionType = defaultTransactionType;
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
                    && Objects.equal(traceStoreThresholdMillis, that.traceStoreThresholdMillis)
                    && Objects.equal(profilingIntervalMillis, that.profilingIntervalMillis)
                    && Objects.equal(defaultTransactionType, that.defaultTransactionType);
        }
        return false;
    }

    @Override
    public int hashCode() {
        // intentionally leaving off version since it represents the prior version hash when
        // sending to the server, and represents the current version hash when receiving from the
        // server
        return Objects.hashCode(enabled, traceStoreThresholdMillis, profilingIntervalMillis,
                defaultTransactionType);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("enabled", enabled)
                .add("traceStoreThresholdMillis", traceStoreThresholdMillis)
                .add("profilingIntervalMillis", profilingIntervalMillis)
                .add("defaultTransactionType", defaultTransactionType)
                .add("version", version)
                .toString();
    }

    @JsonCreator
    static GeneralConfig readValue(
            @JsonProperty("enabled") @Nullable Boolean enabled,
            @JsonProperty("traceStoreThresholdMillis") @Nullable Integer traceStoreThresholdMillis,
            @JsonProperty("profilingIntervalMillis") @Nullable Integer profilingIntervalMillis,
            @JsonProperty("defaultTransactionType") @Nullable String defaultTransactionType,
            @JsonProperty("version") @Nullable String version) throws JsonMappingException {
        checkRequiredProperty(enabled, "enabled");
        checkRequiredProperty(traceStoreThresholdMillis, "traceStoreThresholdMillis");
        checkRequiredProperty(profilingIntervalMillis, "profilingIntervalMillis");
        checkRequiredProperty(defaultTransactionType, "defaultTransactionType");
        checkRequiredProperty(version, "version");
        GeneralConfig config = new GeneralConfig(version);
        config.setEnabled(enabled);
        config.setTraceStoreThresholdMillis(traceStoreThresholdMillis);
        config.setProfilingIntervalMillis(profilingIntervalMillis);
        config.setDefaultTransactionType(defaultTransactionType);
        return config;
    }
}
