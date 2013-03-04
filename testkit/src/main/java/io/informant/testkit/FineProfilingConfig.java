/**
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.testkit;

import checkers.nullness.quals.Nullable;

import com.google.common.base.Objects;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class FineProfilingConfig {

    private boolean enabled;
    private double tracePercentage;
    private int intervalMillis;
    private int totalSeconds;
    private int storeThresholdMillis;
    @Nullable
    private String version;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getTracePercentage() {
        return tracePercentage;
    }

    public void setTracePercentage(double tracePercentage) {
        this.tracePercentage = tracePercentage;
    }

    public int getIntervalMillis() {
        return intervalMillis;
    }

    public void setIntervalMillis(int intervalMillis) {
        this.intervalMillis = intervalMillis;
    }

    public int getTotalSeconds() {
        return totalSeconds;
    }

    public void setTotalSeconds(int totalSeconds) {
        this.totalSeconds = totalSeconds;
    }

    public int getStoreThresholdMillis() {
        return storeThresholdMillis;
    }

    public void setStoreThresholdMillis(int storeThresholdMillis) {
        this.storeThresholdMillis = storeThresholdMillis;
    }

    @Nullable
    public String getVersion() {
        return version;
    }

    void setVersion(@Nullable String version) {
        this.version = version;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof FineProfilingConfig) {
            FineProfilingConfig that = (FineProfilingConfig) obj;
            // intentionally leaving off version since it represents the prior version hash when
            // sending to the server, and represents the current version hash when receiving from
            // the server
            return Objects.equal(enabled, that.enabled)
                    && Objects.equal(tracePercentage, that.tracePercentage)
                    && Objects.equal(intervalMillis, that.intervalMillis)
                    && Objects.equal(totalSeconds, that.totalSeconds)
                    && Objects.equal(storeThresholdMillis, that.storeThresholdMillis);
        }
        return false;
    }

    @Override
    public int hashCode() {
        // intentionally leaving off version since it represents the prior version hash when
        // sending to the server, and represents the current version hash when receiving from the
        // server
        return Objects.hashCode(enabled, tracePercentage, intervalMillis, totalSeconds,
                storeThresholdMillis);
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("enabled", enabled)
                .add("tracePercentage", tracePercentage)
                .add("intervalMillis", intervalMillis)
                .add("totalSeconds", totalSeconds)
                .add("storeThresholdMillis", storeThresholdMillis)
                .add("version", version)
                .toString();
    }
}
