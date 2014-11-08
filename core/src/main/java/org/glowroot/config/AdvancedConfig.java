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
import com.google.common.base.MoreObjects;

import org.glowroot.config.JsonViews.UiView;
import org.glowroot.markers.Immutable;
import org.glowroot.markers.UsedByJsonBinding;

@Immutable
public class AdvancedConfig {

    private final boolean metricWrapperMethods;
    // should be minimum 1000 because of ImmediateTraceStoreWatcher#PERIOD_MILLIS
    private final int immediatePartialStoreThresholdSeconds;
    // used to limit memory requirement, also used to help limit trace capture size,
    private final int maxTraceEntriesPerTransaction;
    // used to limit memory requirement, also used to help limit trace capture size,
    private final int maxStackTraceSamplesPerTransaction;
    private final boolean captureThreadInfo;
    private final boolean captureGcInfo;
    private final int mbeanGaugeNotFoundDelaySeconds;
    private final int internalQueryTimeoutSeconds;

    private final String version;

    static AdvancedConfig getDefault() {
        // default values should be conservative
        final boolean metricWrapperMethods = true;
        final int immediatePartialStoreThresholdSeconds = 60;
        final int maxTraceEntriesPerTransaction = 2000;
        final int maxStackTraceSamplesPerTransaction = 10000;
        final boolean captureThreadInfo = true;
        final boolean captureGcInfo = true;
        final int mbeanGaugeNotFoundDelaySeconds = 60;
        final int internalQueryTimeoutSeconds = 60;
        return new AdvancedConfig(metricWrapperMethods, immediatePartialStoreThresholdSeconds,
                maxTraceEntriesPerTransaction, maxStackTraceSamplesPerTransaction,
                captureThreadInfo, captureGcInfo, mbeanGaugeNotFoundDelaySeconds,
                internalQueryTimeoutSeconds);
    }

    public static Overlay overlay(AdvancedConfig base) {
        return new Overlay(base);
    }

    private AdvancedConfig(boolean metricWrapperMethods, int immediatePartialStoreThresholdSeconds,
            int maxTraceEntriesPerTransaction, int maxStackTraceSamplesPerTransaction,
            boolean captureThreadInfo, boolean captureGcInfo, int mbeanGaugeNotFoundDelaySeconds,
            int internalQueryTimeoutSeconds) {
        this.metricWrapperMethods = metricWrapperMethods;
        this.immediatePartialStoreThresholdSeconds = immediatePartialStoreThresholdSeconds;
        this.maxTraceEntriesPerTransaction = maxTraceEntriesPerTransaction;
        this.maxStackTraceSamplesPerTransaction = maxStackTraceSamplesPerTransaction;
        this.captureThreadInfo = captureThreadInfo;
        this.captureGcInfo = captureGcInfo;
        this.mbeanGaugeNotFoundDelaySeconds = mbeanGaugeNotFoundDelaySeconds;
        this.internalQueryTimeoutSeconds = internalQueryTimeoutSeconds;
        this.version = VersionHashes.sha1(metricWrapperMethods,
                immediatePartialStoreThresholdSeconds, maxTraceEntriesPerTransaction,
                maxStackTraceSamplesPerTransaction, captureThreadInfo, captureGcInfo,
                mbeanGaugeNotFoundDelaySeconds, internalQueryTimeoutSeconds);
    }

    public boolean isMetricWrapperMethods() {
        return metricWrapperMethods;
    }

    public int getImmediatePartialStoreThresholdSeconds() {
        return immediatePartialStoreThresholdSeconds;
    }

    public int getMaxTraceEntriesPerTransaction() {
        return maxTraceEntriesPerTransaction;
    }

    public int getMaxStackTraceSamplesPerTransaction() {
        return maxStackTraceSamplesPerTransaction;
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

    public int getInternalQueryTimeoutSeconds() {
        return internalQueryTimeoutSeconds;
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
                .add("maxTraceEntriesPerTransaction", maxTraceEntriesPerTransaction)
                .add("maxStackTraceSamplesPerTransaction", maxStackTraceSamplesPerTransaction)
                .add("captureThreadInfo", captureThreadInfo)
                .add("captureGcInfo", captureGcInfo)
                .add("mbeanGaugeNotFoundDelaySeconds", mbeanGaugeNotFoundDelaySeconds)
                .add("internalQueryTimeoutSeconds", internalQueryTimeoutSeconds)
                .add("version", version)
                .toString();
    }

    // for overlaying values on top of another config using ObjectMapper.readerForUpdating()
    @UsedByJsonBinding
    public static class Overlay {

        private boolean metricWrapperMethods;
        private int immediatePartialStoreThresholdSeconds;
        private int maxTraceEntriesPerTransaction;
        private int maxStackTraceSamplesPerTransaction;
        private boolean captureThreadInfo;
        private boolean captureGcInfo;
        private int mbeanGaugeNotFoundDelaySeconds;
        private int internalQueryTimeoutSeconds;

        private Overlay(AdvancedConfig base) {
            metricWrapperMethods = base.metricWrapperMethods;
            immediatePartialStoreThresholdSeconds = base.immediatePartialStoreThresholdSeconds;
            maxTraceEntriesPerTransaction = base.maxTraceEntriesPerTransaction;
            maxStackTraceSamplesPerTransaction = base.maxStackTraceSamplesPerTransaction;
            captureThreadInfo = base.captureThreadInfo;
            captureGcInfo = base.captureGcInfo;
            mbeanGaugeNotFoundDelaySeconds = base.mbeanGaugeNotFoundDelaySeconds;
            internalQueryTimeoutSeconds = base.internalQueryTimeoutSeconds;
        }
        public void setMetricWrapperMethods(boolean metricWrapperMethods) {
            this.metricWrapperMethods = metricWrapperMethods;
        }
        public void setImmediatePartialStoreThresholdSeconds(
                int immediatePartialStoreThresholdSeconds) {
            this.immediatePartialStoreThresholdSeconds = immediatePartialStoreThresholdSeconds;
        }
        public void setMaxTraceEntriesPerTransaction(int maxTraceEntriesPerTransaction) {
            this.maxTraceEntriesPerTransaction = maxTraceEntriesPerTransaction;
        }
        public void setMaxStackTraceSamplesPerTransaction(int maxStackTraceSamplesPerTransaction) {
            this.maxStackTraceSamplesPerTransaction = maxStackTraceSamplesPerTransaction;
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
        public void setInternalQueryTimeoutSeconds(int internalQueryTimeoutSeconds) {
            this.internalQueryTimeoutSeconds = internalQueryTimeoutSeconds;
        }
        public AdvancedConfig build() {
            return new AdvancedConfig(metricWrapperMethods, immediatePartialStoreThresholdSeconds,
                    maxTraceEntriesPerTransaction, maxStackTraceSamplesPerTransaction,
                    captureThreadInfo, captureGcInfo, mbeanGaugeNotFoundDelaySeconds,
                    internalQueryTimeoutSeconds);
        }
    }
}
