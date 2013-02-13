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
 * Immutable structure to hold the coarse-grained profiling config.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class CoarseProfilingConfig {

    // serialize nulls so that all properties will be listed in config.json (for humans)
    private static final Gson gson = GsonFactory.newBuilder().serializeNulls().create();

    private final boolean enabled;

    // minimum is imposed because of StackCollector#CHECK_INTERVAL_MILLIS
    // -1 means no stack traces are gathered, should be minimum 100 milliseconds
    private final int initialDelayMillis;
    private final int intervalMillis;
    private final int totalSeconds;

    static CoarseProfilingConfig fromJson(@ReadOnly JsonObject configObject)
            throws JsonSyntaxException {
        return gson.fromJson(configObject, CoarseProfilingConfig.Builder.class).build();
    }

    static CoarseProfilingConfig getDefault() {
        return new Builder().build();
    }

    public static Builder builder(CoarseProfilingConfig base) {
        return new Builder(base);
    }

    private CoarseProfilingConfig(boolean enabled, int initialDelayMillis, int intervalMillis,
            int totalSeconds) {
        this.enabled = enabled;
        this.initialDelayMillis = initialDelayMillis;
        this.intervalMillis = intervalMillis;
        this.totalSeconds = totalSeconds;
    }

    public JsonObject toJson() {
        return gson.toJsonTree(this).getAsJsonObject();
    }

    public JsonObject toJsonWithVersionHash() {
        JsonObject configObject = toJson();
        configObject.addProperty("versionHash", getVersionHash());
        return configObject;
    }

    public String getVersionHash() {
        return Hashing.md5().hashString(toJson().toString()).toString();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getInitialDelayMillis() {
        return initialDelayMillis;
    }

    public int getIntervalMillis() {
        return intervalMillis;
    }

    public int getTotalSeconds() {
        return totalSeconds;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("enabled", enabled)
                .add("initialDelayMillis", initialDelayMillis)
                .add("intervalMillis", intervalMillis)
                .add("totalSeconds", totalSeconds)
                .add("versionHash", getVersionHash())
                .toString();
    }

    public static class Builder {

        private boolean enabled = true;
        private int initialDelayMillis = 1000;
        private int intervalMillis = 500;
        private int totalSeconds = 300;

        private Builder() {}
        private Builder(CoarseProfilingConfig base) {
            enabled = base.enabled;
            initialDelayMillis = base.initialDelayMillis;
            intervalMillis = base.intervalMillis;
            totalSeconds = base.totalSeconds;
        }
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }
        public Builder initialDelayMillis(int initialDelayMillis) {
            this.initialDelayMillis = initialDelayMillis;
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
        public Builder overlay(@ReadOnly JsonObject configObject) {
            JsonElement enabledElement = configObject.get("enabled");
            if (enabledElement != null) {
                enabled(enabledElement.getAsBoolean());
            }
            JsonElement initialDelayMillisElement = configObject.get("initialDelayMillis");
            if (initialDelayMillisElement != null) {
                initialDelayMillis(initialDelayMillisElement.getAsInt());
            }
            JsonElement intervalMillisElement = configObject.get("intervalMillis");
            if (intervalMillisElement != null) {
                intervalMillis(intervalMillisElement.getAsInt());
            }
            JsonElement totalSecondsElement = configObject.get("totalSeconds");
            if (totalSecondsElement != null) {
                totalSeconds(totalSecondsElement.getAsInt());
            }
            return this;
        }
        public CoarseProfilingConfig build() {
            return new CoarseProfilingConfig(enabled, initialDelayMillis,
                    intervalMillis, totalSeconds);
        }
    }
}
