/**
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.config;

import checkers.igj.quals.Immutable;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.google.common.base.Objects;
import com.google.common.hash.Hashing;

/**
 * Immutable structure to hold the fine-grained profiling config.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
@JsonDeserialize(builder = FineProfilingConfig.Builder.class)
public class FineProfilingConfig {

    private final boolean enabled;

    // percentage of traces to apply fine profiling, between 0.0 and 100.0
    private final double tracePercentage;
    private final int intervalMillis;
    private final int totalSeconds;
    // store threshold of -1 means use core config store threshold
    // for fine-grained profiled traces, the real threshold is the minimum of this and the core
    // threshold
    private final int storeThresholdMillis;
    private final String version;

    static FineProfilingConfig getDefault() {
        return new Builder().build();
    }

    public static Builder builder(FineProfilingConfig base) {
        return new Builder(base);
    }

    private FineProfilingConfig(boolean enabled, double tracePercentage, int intervalMillis,
            int totalSeconds, int storeThresholdMillis, String version) {
        this.enabled = enabled;
        this.tracePercentage = tracePercentage;
        this.intervalMillis = intervalMillis;
        this.totalSeconds = totalSeconds;
        this.storeThresholdMillis = storeThresholdMillis;
        this.version = version;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public double getTracePercentage() {
        return tracePercentage;
    }

    public int getIntervalMillis() {
        return intervalMillis;
    }

    public int getTotalSeconds() {
        return totalSeconds;
    }

    public int getStoreThresholdMillis() {
        return storeThresholdMillis;
    }

    @JsonView(WithVersionJsonView.class)
    public String getVersion() {
        return version;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("enabled", enabled)
                .add("tracePercentage", tracePercentage)
                .add("intervalMillis", intervalMillis)
                .add("totalSeconds", totalSeconds)
                .add("storeThresholdMillis", storeThresholdMillis)
                .add("version", version)
                .toString();
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static class Builder {

        private boolean enabled = true;
        private double tracePercentage = 0;
        private int intervalMillis = 50;
        private int totalSeconds = 10;
        private int storeThresholdMillis = -1;

        private Builder() {}

        private Builder(FineProfilingConfig base) {
            enabled = base.enabled;
            tracePercentage = base.tracePercentage;
            intervalMillis = base.intervalMillis;
            totalSeconds = base.totalSeconds;
            storeThresholdMillis = base.storeThresholdMillis;
        }

        // JsonProperty annotations are needed in order to use ObjectMapper.readerForUpdating()
        // for overlaying values on top of a base config
        @JsonProperty
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        @JsonProperty
        public Builder tracePercentage(double tracePercentage) {
            this.tracePercentage = tracePercentage;
            return this;
        }

        @JsonProperty
        public Builder intervalMillis(int intervalMillis) {
            this.intervalMillis = intervalMillis;
            return this;
        }

        @JsonProperty
        public Builder totalSeconds(int totalSeconds) {
            this.totalSeconds = totalSeconds;
            return this;
        }

        @JsonProperty
        public Builder storeThresholdMillis(int storeThresholdMillis) {
            this.storeThresholdMillis = storeThresholdMillis;
            return this;
        }

        public FineProfilingConfig build() {
            String version = buildVersion();
            return new FineProfilingConfig(enabled, tracePercentage, intervalMillis, totalSeconds,
                    storeThresholdMillis, version);
        }

        private String buildVersion() {
            return Hashing.sha1().newHasher()
                    .putBoolean(enabled)
                    .putDouble(tracePercentage)
                    .putInt(intervalMillis)
                    .putInt(totalSeconds)
                    .putInt(storeThresholdMillis)
                    .hash().toString();
        }
    }
}
