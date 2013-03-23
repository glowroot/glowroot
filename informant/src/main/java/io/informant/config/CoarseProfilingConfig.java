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

import com.fasterxml.jackson.annotation.JsonView;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;

/**
 * Immutable structure to hold the coarse-grained profiling config.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class CoarseProfilingConfig {

    private final boolean enabled;
    // minimum is imposed because of StackCollector#CHECK_INTERVAL_MILLIS
    // -1 means no stack traces are gathered, should be minimum 100 milliseconds
    private final int initialDelayMillis;
    private final int intervalMillis;
    private final int totalSeconds;

    private final String version;

    static CoarseProfilingConfig getDefault() {
        final boolean enabled = true;
        final int initialDelayMillis = 1000;
        final int intervalMillis = 500;
        final int totalSeconds = 300;
        return new CoarseProfilingConfig(enabled, initialDelayMillis, intervalMillis, totalSeconds);
    }

    public static Overlay overlay(CoarseProfilingConfig base) {
        return new Overlay(base);
    }

    @VisibleForTesting
    public CoarseProfilingConfig(boolean enabled, int initialDelayMillis, int intervalMillis,
            int totalSeconds) {
        this.enabled = enabled;
        this.initialDelayMillis = initialDelayMillis;
        this.intervalMillis = intervalMillis;
        this.totalSeconds = totalSeconds;
        version = VersionHashes.sha1(enabled, initialDelayMillis, intervalMillis, totalSeconds);
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

    // for overlaying values on top of another config using ObjectMapper.readerForUpdating()
    public static class Overlay {

        private boolean enabled;
        private int initialDelayMillis;
        private int intervalMillis;
        private int totalSeconds;

        private Overlay(CoarseProfilingConfig base) {
            enabled = base.enabled;
            initialDelayMillis = base.initialDelayMillis;
            intervalMillis = base.intervalMillis;
            totalSeconds = base.totalSeconds;
        }
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        public void setInitialDelayMillis(int initialDelayMillis) {
            this.initialDelayMillis = initialDelayMillis;
        }
        public void setIntervalMillis(int intervalMillis) {
            this.intervalMillis = intervalMillis;
        }
        public void setTotalSeconds(int totalSeconds) {
            this.totalSeconds = totalSeconds;
        }
        public CoarseProfilingConfig build() {
            return new CoarseProfilingConfig(enabled, initialDelayMillis, intervalMillis,
                    totalSeconds);
        }
    }
}
