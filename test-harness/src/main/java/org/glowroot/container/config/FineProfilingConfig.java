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
public class FineProfilingConfig {

    private double tracePercentage;
    private int intervalMillis;
    private int totalSeconds;
    private int storeThresholdMillis;

    private final String version;

    public FineProfilingConfig(String version) {
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

    public int getTotalSeconds() {
        return totalSeconds;
    }

    public void setTotalSeconds(int totalSeconds) {
        this.totalSeconds = totalSeconds;
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
    @Pure
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof FineProfilingConfig) {
            FineProfilingConfig that = (FineProfilingConfig) obj;
            // intentionally leaving off version since it represents the prior version hash when
            // sending to the server, and represents the current version hash when receiving from
            // the server
            return Objects.equal(tracePercentage, that.tracePercentage)
                    && Objects.equal(intervalMillis, that.intervalMillis)
                    && Objects.equal(totalSeconds, that.totalSeconds)
                    && Objects.equal(storeThresholdMillis, that.storeThresholdMillis);
        }
        return false;
    }

    @Override
    @Pure
    public int hashCode() {
        // intentionally leaving off version since it represents the prior version hash when
        // sending to the server, and represents the current version hash when receiving from the
        // server
        return Objects.hashCode(tracePercentage, intervalMillis, totalSeconds,
                storeThresholdMillis);
    }

    @Override
    @Pure
    public String toString() {
        return Objects.toStringHelper(this)
                .add("tracePercentage", tracePercentage)
                .add("intervalMillis", intervalMillis)
                .add("totalSeconds", totalSeconds)
                .add("storeThresholdMillis", storeThresholdMillis)
                .add("version", version)
                .toString();
    }

    @JsonCreator
    static FineProfilingConfig readValue(
            @JsonProperty("tracePercentage") @Nullable Double tracePercentage,
            @JsonProperty("intervalMillis") @Nullable Integer intervalMillis,
            @JsonProperty("totalSeconds") @Nullable Integer totalSeconds,
            @JsonProperty("storeThresholdMillis") @Nullable Integer storeThresholdMillis,
            @JsonProperty("version") @Nullable String version) throws JsonMappingException {
        checkRequiredProperty(tracePercentage, "tracePercentage");
        checkRequiredProperty(intervalMillis, "intervalMillis");
        checkRequiredProperty(totalSeconds, "totalSeconds");
        checkRequiredProperty(storeThresholdMillis, "storeThresholdMillis");
        checkRequiredProperty(version, "version");
        FineProfilingConfig config = new FineProfilingConfig(version);
        config.setTracePercentage(tracePercentage);
        config.setIntervalMillis(intervalMillis);
        config.setTotalSeconds(totalSeconds);
        config.setStoreThresholdMillis(storeThresholdMillis);
        return config;
    }
}
