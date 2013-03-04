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
public class GeneralConfig {

    private boolean enabled;
    private int storeThresholdMillis;
    private int stuckThresholdSeconds;
    private int maxSpans;
    private int snapshotExpirationHours;
    private int rollingSizeMb;
    private boolean warnOnSpanOutsideTrace;
    @Nullable
    private String version;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getStoreThresholdMillis() {
        return storeThresholdMillis;
    }

    public void setStoreThresholdMillis(int storeThresholdMillis) {
        this.storeThresholdMillis = storeThresholdMillis;
    }

    public int getStuckThresholdSeconds() {
        return stuckThresholdSeconds;
    }

    public void setStuckThresholdSeconds(int stuckThresholdSeconds) {
        this.stuckThresholdSeconds = stuckThresholdSeconds;
    }

    public int getMaxSpans() {
        return maxSpans;
    }

    public void setMaxSpans(int maxSpans) {
        this.maxSpans = maxSpans;
    }

    public int getSnapshotExpirationHours() {
        return snapshotExpirationHours;
    }

    public void setSnapshotExpirationHours(int snapshotExpirationHours) {
        this.snapshotExpirationHours = snapshotExpirationHours;
    }

    public int getRollingSizeMb() {
        return rollingSizeMb;
    }

    public void setRollingSizeMb(int rollingSizeMb) {
        this.rollingSizeMb = rollingSizeMb;
    }

    public boolean isWarnOnSpanOutsideTrace() {
        return warnOnSpanOutsideTrace;
    }

    public void setWarnOnSpanOutsideTrace(boolean warnOnSpanOutsideTrace) {
        this.warnOnSpanOutsideTrace = warnOnSpanOutsideTrace;
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
        if (obj instanceof GeneralConfig) {
            GeneralConfig that = (GeneralConfig) obj;
            // intentionally leaving off version since it represents the prior version hash when
            // sending to the server, and represents the current version hash when receiving from
            // the server
            return Objects.equal(enabled, that.enabled)
                    && Objects.equal(storeThresholdMillis, that.storeThresholdMillis)
                    && Objects.equal(stuckThresholdSeconds, that.stuckThresholdSeconds)
                    && Objects.equal(maxSpans, that.maxSpans)
                    && Objects.equal(snapshotExpirationHours, that.snapshotExpirationHours)
                    && Objects.equal(rollingSizeMb, that.rollingSizeMb)
                    && Objects.equal(warnOnSpanOutsideTrace, that.warnOnSpanOutsideTrace);
        }
        return false;
    }

    @Override
    public int hashCode() {
        // intentionally leaving off version since it represents the prior version hash when
        // sending to the server, and represents the current version hash when receiving from the
        // server
        return Objects.hashCode(enabled, storeThresholdMillis, stuckThresholdSeconds,
                maxSpans, snapshotExpirationHours, rollingSizeMb, warnOnSpanOutsideTrace);
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("enabled", enabled)
                .add("storeThresholdMillis", storeThresholdMillis)
                .add("stuckThresholdSeconds", stuckThresholdSeconds)
                .add("maxSpans", maxSpans)
                .add("snapshotExpirationHours", snapshotExpirationHours)
                .add("rollingSizeMb", rollingSizeMb)
                .add("warnOnSpanOutsideTrace", warnOnSpanOutsideTrace)
                .add("version", version)
                .toString();
    }
}
