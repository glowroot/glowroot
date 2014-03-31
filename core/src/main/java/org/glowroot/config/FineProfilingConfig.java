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

import javax.annotation.concurrent.Immutable;

import com.fasterxml.jackson.annotation.JsonView;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;

import org.glowroot.config.JsonViews.UiView;
import org.glowroot.markers.UsedByJsonBinding;

/**
 * Immutable structure to hold the fine-grained profiling config.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class FineProfilingConfig {

    // percentage of traces to apply fine profiling, between 0.0 and 100.0
    private final double tracePercentage;
    private final int intervalMillis;
    private final int totalSeconds;
    // store threshold of -1 means use general config store threshold
    // for fine-grained profiled traces, the real threshold is the minimum of this and the general
    // threshold
    private final int storeThresholdMillis;

    private final String version;

    static FineProfilingConfig getDefault() {
        final double tracePercentage = 0;
        final int intervalMillis = 50;
        final int totalSeconds = 30;
        final int storeThresholdMillis = -1;
        return new FineProfilingConfig(tracePercentage, intervalMillis, totalSeconds,
                storeThresholdMillis);
    }

    public static Overlay overlay(FineProfilingConfig base) {
        return new Overlay(base);
    }

    @VisibleForTesting
    public FineProfilingConfig(double tracePercentage, int intervalMillis,
            int totalSeconds, int storeThresholdMillis) {
        this.tracePercentage = tracePercentage;
        this.intervalMillis = intervalMillis;
        this.totalSeconds = totalSeconds;
        this.storeThresholdMillis = storeThresholdMillis;
        version = VersionHashes.sha1(tracePercentage, intervalMillis, totalSeconds,
                storeThresholdMillis);
    }

    public double getTracePercentage() {
        return tracePercentage;
    }

    public int getIntervalMillis() {
        return intervalMillis;
    }

    public int getTotalSeconds() {
        return totalSeconds;
    }

    public int getStoreThresholdMillis() {
        return storeThresholdMillis;
    }

    @JsonView(UiView.class)
    public String getVersion() {
        return version;
    }

    /*@Pure*/
    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("tracePercentage", tracePercentage)
                .add("intervalMillis", intervalMillis)
                .add("totalSeconds", totalSeconds)
                .add("storeThresholdMillis", storeThresholdMillis)
                .add("version", version)
                .toString();
    }

    // for overlaying values on top of another config using ObjectMapper.readerForUpdating()
    @UsedByJsonBinding
    public static class Overlay {

        private double tracePercentage;
        private int intervalMillis;
        private int totalSeconds;
        private int storeThresholdMillis;

        private Overlay(FineProfilingConfig base) {
            tracePercentage = base.tracePercentage;
            intervalMillis = base.intervalMillis;
            totalSeconds = base.totalSeconds;
            storeThresholdMillis = base.storeThresholdMillis;
        }
        public void setTracePercentage(double tracePercentage) {
            this.tracePercentage = tracePercentage;
        }
        public void setIntervalMillis(int intervalMillis) {
            this.intervalMillis = intervalMillis;
        }
        public void setTotalSeconds(int totalSeconds) {
            this.totalSeconds = totalSeconds;
        }
        public void setStoreThresholdMillis(int storeThresholdMillis) {
            this.storeThresholdMillis = storeThresholdMillis;
        }
        public FineProfilingConfig build() {
            return new FineProfilingConfig(tracePercentage, intervalMillis, totalSeconds,
                    storeThresholdMillis);
        }
    }
}
