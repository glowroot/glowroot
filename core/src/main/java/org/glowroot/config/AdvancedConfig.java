/*
 * Copyright 2013-2014 the original author or authors.
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
 * Immutable structure to hold the advanced config.
 * 
 * Default values should be conservative.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class AdvancedConfig {

    private final boolean metricWrapperMethods;
    // minimum is imposed because of PartialTraceStorageWatcher#PERIOD_MILLIS
    // -1 means no partial traces are gathered, should be minimum 100 milliseconds
    private final int immediatePartialStoreThresholdSeconds;
    // used to limit memory requirement, also used to help limit trace capture size,
    // 0 means don't capture any entries, -1 means no limit
    private final int maxEntriesPerTrace;
    private final boolean captureThreadInfo;
    private final boolean captureGcInfo;
    private final int mbeanGaugeNotFoundDelaySeconds;

    private final String version;

    static AdvancedConfig getDefault() {
        final boolean metricWrapperMethods = true;
        final int immediatePartialStoreThresholdSeconds = 60;
        final int maxEntriesPerTrace = 2000;
        final boolean captureThreadInfo = true;
        final boolean captureGcInfo = true;
        final int mbeanGaugeNotFoundDelaySeconds = 60;
        return new AdvancedConfig(metricWrapperMethods, immediatePartialStoreThresholdSeconds,
                maxEntriesPerTrace, captureThreadInfo, captureGcInfo,
                mbeanGaugeNotFoundDelaySeconds);
    }

    public static Overlay overlay(AdvancedConfig base) {
        return new Overlay(base);
    }

    @VisibleForTesting
    public AdvancedConfig(boolean metricWrapperMethods, int immediatePartialStoreThresholdSeconds,
            int maxEntriesPerTrace, boolean captureThreadInfo, boolean captureGcInfo,
            int mbeanGaugeNotFoundDelaySeconds) {
        this.metricWrapperMethods = metricWrapperMethods;
        this.immediatePartialStoreThresholdSeconds = immediatePartialStoreThresholdSeconds;
        this.maxEntriesPerTrace = maxEntriesPerTrace;
        this.captureThreadInfo = captureThreadInfo;
        this.captureGcInfo = captureGcInfo;
        this.mbeanGaugeNotFoundDelaySeconds = mbeanGaugeNotFoundDelaySeconds;
        this.version = VersionHashes.sha1(metricWrapperMethods,
                immediatePartialStoreThresholdSeconds, maxEntriesPerTrace, captureThreadInfo,
                captureGcInfo, mbeanGaugeNotFoundDelaySeconds);
    }

    public boolean isMetricWrapperMethods() {
        return metricWrapperMethods;
    }

    public int getImmediatePartialStoreThresholdSeconds() {
        return immediatePartialStoreThresholdSeconds;
    }

    public int getMaxEntriesPerTrace() {
        return maxEntriesPerTrace;
    }

    public boolean isCaptureThreadInfo() {
        return captureThreadInfo;
    }

    public boolean isCaptureGcInfo() {
        return captureGcInfo;
    }

    public int getMBeanGaugeNotFoundDelaySeconds() {
        return mbeanGaugeNotFoundDelaySeconds;
    }

    @JsonView(UiView.class)
    public String getVersion() {
        return version;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("metricWrapperMethods", metricWrapperMethods)
                .add("immediatePartialStoreThresholdSeconds", immediatePartialStoreThresholdSeconds)
                .add("maxEntriesPerTrace", maxEntriesPerTrace)
                .add("captureThreadInfo", captureThreadInfo)
                .add("captureGcInfo", captureGcInfo)
                .add("mbeanGaugeNotFoundDelaySeconds", mbeanGaugeNotFoundDelaySeconds)
                .add("version", version)
                .toString();
    }

    // for overlaying values on top of another config using ObjectMapper.readerForUpdating()
    @UsedByJsonBinding
    public static class Overlay {

        private boolean metricWrapperMethods;
        private int immediatePartialStoreThresholdSeconds;
        private int maxEntriesPerTrace;
        private boolean captureThreadInfo;
        private boolean captureGcInfo;
        private int mbeanGaugeNotFoundDelaySeconds;

        private Overlay(AdvancedConfig base) {
            metricWrapperMethods = base.metricWrapperMethods;
            immediatePartialStoreThresholdSeconds = base.immediatePartialStoreThresholdSeconds;
            maxEntriesPerTrace = base.maxEntriesPerTrace;
            captureThreadInfo = base.captureThreadInfo;
            captureGcInfo = base.captureGcInfo;
            mbeanGaugeNotFoundDelaySeconds = base.mbeanGaugeNotFoundDelaySeconds;
        }
        public void setMetricWrapperMethods(boolean metricWrapperMethods) {
            this.metricWrapperMethods = metricWrapperMethods;
        }
        public void setImmediatePartialStoreThresholdSeconds(
                int immediatePartialStoreThresholdSeconds) {
            this.immediatePartialStoreThresholdSeconds = immediatePartialStoreThresholdSeconds;
        }
        public void setMaxEntriesPerTrace(int maxEntriesPerTrace) {
            this.maxEntriesPerTrace = maxEntriesPerTrace;
        }
        public void setCaptureThreadInfo(boolean captureThreadInfo) {
            this.captureThreadInfo = captureThreadInfo;
        }
        public void setCaptureGcInfo(boolean captureGcInfo) {
            this.captureGcInfo = captureGcInfo;
        }
        public void setMBeanGaugeNotFoundDelaySeconds(int mbeanGaugeNotFoundDelaySeconds) {
            this.mbeanGaugeNotFoundDelaySeconds = mbeanGaugeNotFoundDelaySeconds;
        }
        public AdvancedConfig build() {
            return new AdvancedConfig(metricWrapperMethods, immediatePartialStoreThresholdSeconds,
                    maxEntriesPerTrace, captureThreadInfo, captureGcInfo,
                    mbeanGaugeNotFoundDelaySeconds);
        }
    }
}
