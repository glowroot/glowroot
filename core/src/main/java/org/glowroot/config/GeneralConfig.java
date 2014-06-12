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
import com.google.common.base.Objects;
import org.checkerframework.dataflow.qual.Pure;

import org.glowroot.config.JsonViews.UiView;
import org.glowroot.markers.Immutable;
import org.glowroot.markers.UsedByJsonBinding;

/**
 * Immutable structure to hold the general config.
 * 
 * Default values should be conservative.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class GeneralConfig {

    // if tracing is disabled mid-trace there should be no issue
    // active traces will not accumulate additional spans
    // but they will be logged / emailed if they exceed the defined thresholds
    //
    // if tracing is enabled mid-trace there should be no issue
    // active traces that were not captured at their start will
    // continue not to accumulate spans
    // and they will not be logged / emailed even if they exceed the defined
    // thresholds
    private final boolean enabled;
    // 0 means log all traces, -1 means log no traces
    // (though stuck threshold can still be used in this case)
    private final int storeThresholdMillis;
    // minimum is imposed because of StuckTraceCollector#CHECK_INTERVAL_MILLIS
    // -1 means no stuck messages are gathered, should be minimum 100 milliseconds
    private final int stuckThresholdSeconds;
    // used to limit memory requirement, also used to help limit trace capture size,
    // 0 means don't capture any spans, -1 means no limit
    private final int maxSpans;

    private final String version;

    static GeneralConfig getDefault() {
        final boolean enabled = true;
        final int storeThresholdMillis = 3000;
        final int stuckThresholdSeconds = 180;
        final int maxSpans = 2000;
        return new GeneralConfig(enabled, storeThresholdMillis, stuckThresholdSeconds, maxSpans);
    }

    public static Overlay overlay(GeneralConfig base) {
        return new Overlay(base);
    }

    @VisibleForTesting
    public GeneralConfig(boolean enabled, int storeThresholdMillis, int stuckThresholdSeconds,
            int maxSpans) {
        this.enabled = enabled;
        this.storeThresholdMillis = storeThresholdMillis;
        this.stuckThresholdSeconds = stuckThresholdSeconds;
        this.maxSpans = maxSpans;
        this.version = VersionHashes.sha1(enabled, storeThresholdMillis, stuckThresholdSeconds,
                maxSpans);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getStoreThresholdMillis() {
        return storeThresholdMillis;
    }

    public int getStuckThresholdSeconds() {
        return stuckThresholdSeconds;
    }

    public int getMaxSpans() {
        return maxSpans;
    }

    @JsonView(UiView.class)
    public String getVersion() {
        return version;
    }

    @Override
    @Pure
    public String toString() {
        return Objects.toStringHelper(this)
                .add("enabed", enabled)
                .add("storeThresholdMillis", storeThresholdMillis)
                .add("stuckThresholdSeconds", stuckThresholdSeconds)
                .add("maxSpans", maxSpans)
                .add("version", version)
                .toString();
    }

    // for overlaying values on top of another config using ObjectMapper.readerForUpdating()
    @UsedByJsonBinding
    public static class Overlay {

        private boolean enabled;
        private int storeThresholdMillis;
        private int stuckThresholdSeconds;
        private int maxSpans;

        private Overlay(GeneralConfig base) {
            enabled = base.enabled;
            storeThresholdMillis = base.storeThresholdMillis;
            stuckThresholdSeconds = base.stuckThresholdSeconds;
            maxSpans = base.maxSpans;
        }
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        public void setStoreThresholdMillis(int storeThresholdMillis) {
            this.storeThresholdMillis = storeThresholdMillis;
        }
        public void setStuckThresholdSeconds(int stuckThresholdSeconds) {
            this.stuckThresholdSeconds = stuckThresholdSeconds;
        }
        public void setMaxSpans(int maxSpans) {
            this.maxSpans = maxSpans;
        }
        public GeneralConfig build() {
            return new GeneralConfig(enabled, storeThresholdMillis, stuckThresholdSeconds,
                    maxSpans);
        }
    }
}
