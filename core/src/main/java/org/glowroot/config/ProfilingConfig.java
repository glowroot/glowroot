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
import com.google.common.base.MoreObjects;

import org.glowroot.config.JsonViews.UiView;
import org.glowroot.markers.Immutable;
import org.glowroot.markers.UsedByJsonBinding;

/**
 * Immutable structure to hold the profiling config.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class ProfilingConfig {

    public static final int USE_GENERAL_STORE_THRESHOLD = -1;

    private final boolean enabled;
    // percentage of transactions to apply profiling, between 0.0 and 100.0
    private final double transactionPercentage;
    private final int intervalMillis;
    // trace store threshold of -1 means use default trace store threshold for profiled
    // transactions (the real threshold is the minimum of this and the default threshold)
    private final int traceStoreThresholdOverrideMillis;

    private final String version;

    static ProfilingConfig getDefault() {
        final boolean enabled = true;
        final double transactionPercentage = 2;
        final int intervalMillis = 50;
        final int traceStoreThresholdMillis = USE_GENERAL_STORE_THRESHOLD;
        return new ProfilingConfig(enabled, transactionPercentage, intervalMillis,
                traceStoreThresholdMillis);
    }

    public static Overlay overlay(ProfilingConfig base) {
        return new Overlay(base);
    }

    @VisibleForTesting
    public ProfilingConfig(boolean enabled, double transactionPercentage, int intervalMillis,
            int traceStoreThresholdOverrideMillis) {
        this.enabled = enabled;
        this.transactionPercentage = transactionPercentage;
        this.intervalMillis = intervalMillis;
        this.traceStoreThresholdOverrideMillis = traceStoreThresholdOverrideMillis;
        version = VersionHashes.sha1(transactionPercentage, intervalMillis,
                traceStoreThresholdOverrideMillis);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public double getTransactionPercentage() {
        return transactionPercentage;
    }

    public int getIntervalMillis() {
        return intervalMillis;
    }

    public int getTraceStoreThresholdOverrideMillis() {
        return traceStoreThresholdOverrideMillis;
    }

    @JsonView(UiView.class)
    public String getVersion() {
        return version;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("enabled", enabled)
                .add("transactionPercentage", transactionPercentage)
                .add("intervalMillis", intervalMillis)
                .add("traceStoreThresholdOverrideMillis", traceStoreThresholdOverrideMillis)
                .add("version", version)
                .toString();
    }

    // for overlaying values on top of another config using ObjectMapper.readerForUpdating()
    @UsedByJsonBinding
    public static class Overlay {

        private boolean enabled;
        private double transactionPercentage;
        private int intervalMillis;
        private int traceStoreThresholdOverrideMillis;

        private Overlay(ProfilingConfig base) {
            enabled = base.enabled;
            transactionPercentage = base.transactionPercentage;
            intervalMillis = base.intervalMillis;
            traceStoreThresholdOverrideMillis = base.traceStoreThresholdOverrideMillis;
        }
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        public void setTransactionPercentage(double transactionPercentage) {
            this.transactionPercentage = transactionPercentage;
        }
        public void setIntervalMillis(int intervalMillis) {
            this.intervalMillis = intervalMillis;
        }
        public void setTraceStoreThresholdOverrideMillis(int traceStoreThresholdOverrideMillis) {
            this.traceStoreThresholdOverrideMillis = traceStoreThresholdOverrideMillis;
        }
        public ProfilingConfig build() {
            return new ProfilingConfig(enabled, transactionPercentage, intervalMillis,
                    traceStoreThresholdOverrideMillis);
        }
    }
}
