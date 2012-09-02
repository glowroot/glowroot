/**
 * Copyright 2012 the original author or authors.
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
package org.informantproject.core.config;

import javax.annotation.concurrent.Immutable;

import com.google.gson.Gson;

/**
 * Immutable structure to hold the coarse-grained profiling config.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class CoarseProfilingConfig {

    private static final Gson gson = new Gson();

    private final boolean enabled;

    // minimum is imposed because of StackCollector#CHECK_INTERVAL_MILLIS
    // -1 means no stack traces are gathered, should be minimum 100 milliseconds
    private final int initialDelayMillis;
    private final int intervalMillis;
    private final int totalSeconds;

    static CoarseProfilingConfig getDefaultInstance() {
        return new Builder().build();
    }

    static CoarseProfilingConfig fromJson(String json) {
        return gson.fromJson(json, CoarseProfilingConfig.Builder.class).build();
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

    public String toJson() {
        return gson.toJson(this);
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
        public CoarseProfilingConfig build() {
            return new CoarseProfilingConfig(enabled, initialDelayMillis,
                    intervalMillis, totalSeconds);
        }
    }
}
