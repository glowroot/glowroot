/*
 * Copyright 2012-2014 the original author or authors.
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
package org.glowroot.config;

import com.fasterxml.jackson.annotation.JsonView;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import org.checkerframework.dataflow.qual.Pure;

import org.glowroot.config.JsonViews.UiView;
import org.glowroot.markers.Immutable;
import org.glowroot.markers.UsedByJsonBinding;

/**
 * Immutable structure to hold the outlier profiling config.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class OutlierProfilingConfig {

    private final boolean enabled;
    // minimum is imposed because of StackCollector#CHECK_INTERVAL_MILLIS
    // -1 means no stack traces are gathered, should be minimum 100 milliseconds
    private final int initialDelayMillis;
    private final int intervalMillis;
    private final int maxSeconds;

    private final String version;

    static OutlierProfilingConfig getDefault() {
        final boolean enabled = true;
        final int initialDelayMillis = 30000;
        final int intervalMillis = 1000;
        final int maxSeconds = 300;
        return new OutlierProfilingConfig(enabled, initialDelayMillis, intervalMillis, maxSeconds);
    }

    public static Overlay overlay(OutlierProfilingConfig base) {
        return new Overlay(base);
    }

    @VisibleForTesting
    public OutlierProfilingConfig(boolean enabled, int initialDelayMillis, int intervalMillis,
            int maxSeconds) {
        this.enabled = enabled;
        this.initialDelayMillis = initialDelayMillis;
        this.intervalMillis = intervalMillis;
        this.maxSeconds = maxSeconds;
        version = VersionHashes.sha1(enabled, initialDelayMillis, intervalMillis, maxSeconds);
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

    public int getMaxSeconds() {
        return maxSeconds;
    }

    @JsonView(UiView.class)
    public String getVersion() {
        return version;
    }

    @Override
    @Pure
    public String toString() {
        return Objects.toStringHelper(this)
                .add("enabled", enabled)
                .add("initialDelayMillis", initialDelayMillis)
                .add("intervalMillis", intervalMillis)
                .add("maxSeconds", maxSeconds)
                .add("version", version)
                .toString();
    }

    // for overlaying values on top of another config using ObjectMapper.readerForUpdating()
    @UsedByJsonBinding
    public static class Overlay {

        private boolean enabled;
        private int initialDelayMillis;
        private int intervalMillis;
        private int maxSeconds;

        private Overlay(OutlierProfilingConfig base) {
            enabled = base.enabled;
            initialDelayMillis = base.initialDelayMillis;
            intervalMillis = base.intervalMillis;
            maxSeconds = base.maxSeconds;
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
        public void setMaxSeconds(int maxSeconds) {
            this.maxSeconds = maxSeconds;
        }
        public OutlierProfilingConfig build() {
            return new OutlierProfilingConfig(enabled, initialDelayMillis, intervalMillis,
                    maxSeconds);
        }
    }
}
