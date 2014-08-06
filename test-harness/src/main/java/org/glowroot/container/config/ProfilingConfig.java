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

    private double tracePercentage;
    private int intervalMillis;
    private int maxSeconds;
    private int storeThresholdMillis;

    private final String version;

    public ProfilingConfig(String version) {
        this.version = version;
    }

    public double getTracePercentage() {
        return tracePercentage;
    }

    public void setTracePercentage(double tracePercentage) {
        this.tracePercentage = tracePercentage;
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

    public int getStoreThresholdMillis() {
        return storeThresholdMillis;
    }

    public void setStoreThresholdMillis(int storeThresholdMillis) {
        this.storeThresholdMillis = storeThresholdMillis;
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
            return Objects.equal(tracePercentage, that.tracePercentage)
                    && Objects.equal(intervalMillis, that.intervalMillis)
                    && Objects.equal(maxSeconds, that.maxSeconds)
                    && Objects.equal(storeThresholdMillis, that.storeThresholdMillis);
        }
        return false;
    }

    @Override
    public int hashCode() {
        // intentionally leaving off version since it represents the prior version hash when
        // sending to the server, and represents the current version hash when receiving from the
        // server
        return Objects.hashCode(tracePercentage, intervalMillis, maxSeconds, storeThresholdMillis);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("tracePercentage", tracePercentage)
                .add("intervalMillis", intervalMillis)
                .add("maxSeconds", maxSeconds)
                .add("storeThresholdMillis", storeThresholdMillis)
                .add("version", version)
                .toString();
    }

    @JsonCreator
    static ProfilingConfig readValue(
            @JsonProperty("tracePercentage") @Nullable Double tracePercentage,
            @JsonProperty("intervalMillis") @Nullable Integer intervalMillis,
            @JsonProperty("maxSeconds") @Nullable Integer maxSeconds,
            @JsonProperty("storeThresholdMillis") @Nullable Integer storeThresholdMillis,
            @JsonProperty("version") @Nullable String version) throws JsonMappingException {
        checkRequiredProperty(tracePercentage, "tracePercentage");
        checkRequiredProperty(intervalMillis, "intervalMillis");
        checkRequiredProperty(maxSeconds, "maxSeconds");
        checkRequiredProperty(storeThresholdMillis, "storeThresholdMillis");
        checkRequiredProperty(version, "version");
        ProfilingConfig config = new ProfilingConfig(version);
        config.setTracePercentage(tracePercentage);
        config.setIntervalMillis(intervalMillis);
        config.setMaxSeconds(maxSeconds);
        config.setStoreThresholdMillis(storeThresholdMillis);
        return config;
    }
}
