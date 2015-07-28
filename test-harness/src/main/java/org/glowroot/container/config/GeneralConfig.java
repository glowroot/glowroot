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

import java.util.List;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.Lists;

import static org.glowroot.container.common.ObjectMappers.checkRequiredProperty;

public class GeneralConfig {

    private int slowTraceThresholdMillis;
    private int profilingIntervalMillis;
    private String defaultDisplayedTransactionType = "";
    private List<Double> defaultDisplayedPercentiles = Lists.newArrayList();

    private final String version;

    private GeneralConfig(String version) {
        this.version = version;
    }

    public int getSlowTraceThresholdMillis() {
        return slowTraceThresholdMillis;
    }

    public void setSlowTraceThresholdMillis(int slowTraceThresholdMillis) {
        this.slowTraceThresholdMillis = slowTraceThresholdMillis;
    }

    public int getProfilingIntervalMillis() {
        return profilingIntervalMillis;
    }

    public void setProfilingIntervalMillis(int profilingIntervalMillis) {
        this.profilingIntervalMillis = profilingIntervalMillis;
    }

    public String getDefaultDisplayedTransactionType() {
        return defaultDisplayedTransactionType;
    }

    public void setDefaultDisplayedTransactionType(String defaultDisplayedTransactionType) {
        this.defaultDisplayedTransactionType = defaultDisplayedTransactionType;
    }

    public List<Double> getDefaultDisplayedPercentiles() {
        return defaultDisplayedPercentiles;
    }

    public void setDefaultDisplayedPercentiles(List<Double> defaultDisplayedPercentiles) {
        this.defaultDisplayedPercentiles = defaultDisplayedPercentiles;
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
            return Objects.equal(slowTraceThresholdMillis, that.slowTraceThresholdMillis)
                    && Objects.equal(profilingIntervalMillis, that.profilingIntervalMillis)
                    && Objects.equal(defaultDisplayedTransactionType,
                            that.defaultDisplayedTransactionType)
                    && Objects.equal(defaultDisplayedPercentiles, that.defaultDisplayedPercentiles);
        }
        return false;
    }

    @Override
    public int hashCode() {
        // intentionally leaving off version since it represents the prior version hash when
        // sending to the server, and represents the current version hash when receiving from the
        // server
        return Objects.hashCode(slowTraceThresholdMillis, profilingIntervalMillis,
                defaultDisplayedTransactionType, defaultDisplayedPercentiles);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("slowTraceThresholdMillis", slowTraceThresholdMillis)
                .add("profilingIntervalMillis", profilingIntervalMillis)
                .add("defaultDisplayedTransactionType", defaultDisplayedTransactionType)
                .add("defaultDisplayedPercentiles", defaultDisplayedPercentiles)
                .add("version", version)
                .toString();
    }

    @JsonCreator
    static GeneralConfig readValue(
            @JsonProperty("slowTraceThresholdMillis") @Nullable Integer slowTraceThresholdMillis,
            @JsonProperty("profilingIntervalMillis") @Nullable Integer profilingIntervalMillis,
            @JsonProperty("defaultDisplayedTransactionType") @Nullable String defaultDisplayedTransactionType,
            @JsonProperty("defaultDisplayedPercentiles") @Nullable List<Double> defaultDisplayedPercentiles,
            @JsonProperty("version") @Nullable String version) throws JsonMappingException {
        checkRequiredProperty(slowTraceThresholdMillis, "slowTraceThresholdMillis");
        checkRequiredProperty(profilingIntervalMillis, "profilingIntervalMillis");
        checkRequiredProperty(defaultDisplayedTransactionType, "defaultDisplayedTransactionType");
        checkRequiredProperty(defaultDisplayedPercentiles, "defaultDisplayedPercentiles");
        checkRequiredProperty(version, "version");
        GeneralConfig config = new GeneralConfig(version);
        config.setSlowTraceThresholdMillis(slowTraceThresholdMillis);
        config.setProfilingIntervalMillis(profilingIntervalMillis);
        config.setDefaultDisplayedTransactionType(defaultDisplayedTransactionType);
        config.setDefaultDisplayedPercentiles(defaultDisplayedPercentiles);
        return config;
    }
}
