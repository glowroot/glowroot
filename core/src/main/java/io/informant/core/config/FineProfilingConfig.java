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
package io.informant.core.config;

import io.informant.core.util.GsonFactory;
import checkers.igj.quals.Immutable;
import checkers.igj.quals.ReadOnly;

import com.google.common.base.Objects;
import com.google.common.hash.Hashing;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

/**
 * Immutable structure to hold the fine-grained profiling config.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class FineProfilingConfig {

    // serialize nulls so that all properties will be listed in config.json (for humans)
    private static final Gson gson = GsonFactory.newBuilder().serializeNulls().create();

    private final boolean enabled;

    // percentage of traces to apply fine profiling, between 0.0 and 100.0
    private final double tracePercentage;
    private final int intervalMillis;
    private final int totalSeconds;
    // store threshold of -1 means use core config store threshold
    // for fine-grained profiled traces, the real threshold is the minimum of this and the core
    // threshold
    private final int storeThresholdMillis;

    static FineProfilingConfig fromJson(@ReadOnly JsonObject configObject)
            throws JsonSyntaxException {
        return gson.fromJson(configObject, FineProfilingConfig.Builder.class).build();
    }

    static FineProfilingConfig getDefault() {
        return new Builder().build();
    }

    public static Builder builder(FineProfilingConfig base) {
        return new Builder(base);
    }

    private FineProfilingConfig(boolean enabled, double tracePercentage, int intervalMillis,
            int totalSeconds, int storeThresholdMillis) {
        this.enabled = enabled;
        this.tracePercentage = tracePercentage;
        this.intervalMillis = intervalMillis;
        this.totalSeconds = totalSeconds;
        this.storeThresholdMillis = storeThresholdMillis;
    }

    public JsonObject toJson() {
        JsonObject configObject = toJsonWithoutVersionHash();
        configObject.addProperty("versionHash", getVersionHash());
        return configObject;
    }

    public JsonObject toJsonWithoutVersionHash() {
        return gson.toJsonTree(this).getAsJsonObject();
    }

    public String getVersionHash() {
        return Hashing.md5().hashString(toJsonWithoutVersionHash().toString()).toString();
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

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("enabled", enabled)
                .add("tracePercentage", tracePercentage)
                .add("intervalMillis", intervalMillis)
                .add("totalSeconds", totalSeconds)
                .add("storeThresholdMillis", storeThresholdMillis)
                .add("versionHash", getVersionHash())
                .toString();
    }

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
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }
        public Builder tracePercentage(double tracePercentage) {
            this.tracePercentage = tracePercentage;
            return this;
        }
        public Builder intervalMillis(int intervalMillis) {
            this.intervalMillis = intervalMillis;
            return this;
        }
        public Builder totalSeconds(int totalSeconds) {
            this.totalSeconds = totalSeconds;
            return this;
        }
        public Builder storeThresholdMillis(int storeThresholdMillis) {
            this.storeThresholdMillis = storeThresholdMillis;
            return this;
        }
        public Builder overlay(@ReadOnly JsonObject configObject) {
            JsonElement enabledElement = configObject.get("enabled");
            if (enabledElement != null) {
                enabled(enabledElement.getAsBoolean());
            }
            JsonElement tracePercentageElement = configObject.get("tracePercentage");
            if (tracePercentageElement != null) {
                tracePercentage(tracePercentageElement.getAsDouble());
            }
            JsonElement intervalMillisElement = configObject.get("intervalMillis");
            if (intervalMillisElement != null) {
                intervalMillis(intervalMillisElement.getAsInt());
            }
            JsonElement totalSecondsElement = configObject.get("totalSeconds");
            if (totalSecondsElement != null) {
                totalSeconds(totalSecondsElement.getAsInt());
            }
            JsonElement storeThresholdMillisElement = configObject.get("storeThresholdMillis");
            if (storeThresholdMillisElement != null) {
                storeThresholdMillis(storeThresholdMillisElement.getAsInt());
            }
            return this;
        }
        public FineProfilingConfig build() {
            return new FineProfilingConfig(enabled, tracePercentage, intervalMillis, totalSeconds,
                    storeThresholdMillis);
        }
    }
}
