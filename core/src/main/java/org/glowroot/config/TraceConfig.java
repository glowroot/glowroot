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
package org.glowroot.config;

import com.fasterxml.jackson.annotation.JsonView;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;

import org.glowroot.config.JsonViews.UiView;
import org.glowroot.markers.Immutable;
import org.glowroot.markers.UsedByJsonBinding;

/**
 * Immutable structure to hold the trace config.
 * 
 * Default values should be conservative.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class TraceConfig {

    // if tracing is disabled mid-trace there should be no issue
    // active traces will not accumulate additional entries
    // but they will be logged / emailed if they exceed the defined thresholds
    //
    // if tracing is enabled mid-trace there should be no issue
    // active traces that were not captured at their start will
    // continue not to accumulate entries
    // and they will not be logged / emailed even if they exceed the defined
    // thresholds
    private final boolean enabled;
    // 0 means log all traces, -1 means log no traces
    private final int storeThresholdMillis;

    private final boolean outlierProfilingEnabled;
    // minimum is imposed because of OutlierProfileWatcher#PERIOD_MILLIS
    // -1 means no stack traces are gathered, should be minimum 100 milliseconds
    private final int outlierProfilingInitialDelayMillis;
    private final int outlierProfilingIntervalMillis;

    private final String version;

    static TraceConfig getDefault() {
        final boolean enabled = true;
        final int storeThresholdMillis = 3000;
        final boolean outlierProfilingEnabled = true;
        final int outlierProfilingInitialDelayMillis = 3000;
        final int outlierProfilingIntervalMillis = 1000;
        return new TraceConfig(enabled, storeThresholdMillis, outlierProfilingEnabled,
                outlierProfilingInitialDelayMillis, outlierProfilingIntervalMillis);
    }

    public static Overlay overlay(TraceConfig base) {
        return new Overlay(base);
    }

    @VisibleForTesting
    public TraceConfig(boolean enabled, int storeThresholdMillis, boolean outlierProfilingEnabled,
            int outlierProfilingInitialDelayMillis, int outlierProfilingIntervalMillis) {
        this.enabled = enabled;
        this.storeThresholdMillis = storeThresholdMillis;
        this.outlierProfilingEnabled = outlierProfilingEnabled;
        this.outlierProfilingInitialDelayMillis = outlierProfilingInitialDelayMillis;
        this.outlierProfilingIntervalMillis = outlierProfilingIntervalMillis;
        this.version = VersionHashes.sha1(enabled, storeThresholdMillis, outlierProfilingEnabled,
                outlierProfilingInitialDelayMillis, outlierProfilingIntervalMillis);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getStoreThresholdMillis() {
        return storeThresholdMillis;
    }

    public boolean isOutlierProfilingEnabled() {
        return outlierProfilingEnabled;
    }

    public int getOutlierProfilingInitialDelayMillis() {
        return outlierProfilingInitialDelayMillis;
    }

    public int getOutlierProfilingIntervalMillis() {
        return outlierProfilingIntervalMillis;
    }

    @JsonView(UiView.class)
    public String getVersion() {
        return version;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("enabed", enabled)
                .add("storeThresholdMillis", storeThresholdMillis)
                .add("outlierProfilingEnabled", outlierProfilingEnabled)
                .add("outlierProfilingInitialDelayMillis", outlierProfilingInitialDelayMillis)
                .add("outlierProfilingIntervalMillis", outlierProfilingIntervalMillis)
                .add("version", version)
                .toString();
    }

    // for overlaying values on top of another config using ObjectMapper.readerForUpdating()
    @UsedByJsonBinding
    public static class Overlay {

        private boolean enabled;
        private int storeThresholdMillis;
        private boolean outlierProfilingEnabled;
        private int outlierProfilingInitialDelayMillis;
        private int outlierProfilingIntervalMillis;

        private Overlay(TraceConfig base) {
            enabled = base.enabled;
            storeThresholdMillis = base.storeThresholdMillis;
            outlierProfilingEnabled = base.outlierProfilingEnabled;
            outlierProfilingInitialDelayMillis = base.outlierProfilingInitialDelayMillis;
            outlierProfilingIntervalMillis = base.outlierProfilingIntervalMillis;
        }
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        public void setStoreThresholdMillis(int storeThresholdMillis) {
            this.storeThresholdMillis = storeThresholdMillis;
        }
        public void setOutlierProfilingEnabled(boolean outlierProfilingEnabled) {
            this.outlierProfilingEnabled = outlierProfilingEnabled;
        }
        public void setOutlierProfilingInitialDelayMillis(int outlierProfilingInitialDelayMillis) {
            this.outlierProfilingInitialDelayMillis = outlierProfilingInitialDelayMillis;
        }
        public void setOutlierProfilingIntervalMillis(int outlierProfilingIntervalMillis) {
            this.outlierProfilingIntervalMillis = outlierProfilingIntervalMillis;
        }
        public TraceConfig build() {
            return new TraceConfig(enabled, storeThresholdMillis, outlierProfilingEnabled,
                    outlierProfilingInitialDelayMillis, outlierProfilingIntervalMillis);
        }
    }
}
