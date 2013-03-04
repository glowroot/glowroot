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
 * Immutable structure to hold the coarse-grained profiling config.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
@JsonDeserialize(builder = CoarseProfilingConfig.Builder.class)
public class CoarseProfilingConfig {

    private final boolean enabled;

    // minimum is imposed because of StackCollector#CHECK_INTERVAL_MILLIS
    // -1 means no stack traces are gathered, should be minimum 100 milliseconds
    private final int initialDelayMillis;
    private final int intervalMillis;
    private final int totalSeconds;
    private final String version;

    static CoarseProfilingConfig getDefault() {
        return new Builder().build();
    }

    public static Builder builder(CoarseProfilingConfig base) {
        return new Builder(base);
    }

    private CoarseProfilingConfig(boolean enabled, int initialDelayMillis, int intervalMillis,
            int totalSeconds, String version) {
        this.enabled = enabled;
        this.initialDelayMillis = initialDelayMillis;
        this.intervalMillis = intervalMillis;
        this.totalSeconds = totalSeconds;
        this.version = version;
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

    @JsonView(WithVersionJsonView.class)
    public String getVersion() {
        return version;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("enabled", enabled)
                .add("initialDelayMillis", initialDelayMillis)
                .add("intervalMillis", intervalMillis)
                .add("totalSeconds", totalSeconds)
                .add("version", version)
                .toString();
    }

    @JsonPOJOBuilder(withPrefix = "")
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

        // JsonProperty annotations are needed in order to use ObjectMapper.readerForUpdating()
        // for overlaying values on top of a base config
        @JsonProperty
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        @JsonProperty
        public Builder initialDelayMillis(int initialDelayMillis) {
            this.initialDelayMillis = initialDelayMillis;
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

        public CoarseProfilingConfig build() {
            String version = buildVersion();
            return new CoarseProfilingConfig(enabled, initialDelayMillis, intervalMillis,
                    totalSeconds, version);
        }

        private String buildVersion() {
            return Hashing.sha1().newHasher()
                    .putBoolean(enabled)
                    .putInt(initialDelayMillis)
                    .putInt(intervalMillis)
                    .putInt(totalSeconds)
                    .hash().toString();
        }
    }
}
