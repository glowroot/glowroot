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
package io.informant.core.config;

import io.informant.core.util.GsonFactory;
import checkers.igj.quals.Immutable;
import checkers.igj.quals.ReadOnly;

import com.google.common.base.Objects;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

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

    // don't store anything, essentially store threshold is infinite
    public static final int STORE_THRESHOLD_DISABLED = -1;
    // don't expire anything, essentially snapshot expiration is infinite
    public static final int SNAPSHOT_EXPIRATION_DISABLED = -1;

    // serialize nulls so that all properties will be listed in config.json (for humans)
    private static final Gson gson = GsonFactory.newBuilder().serializeNulls().create();

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

    // TODO convert from millis to seconds, support 0.1, etc
    // 0 means log all traces, -1 means log no traces
    // (though stuck threshold can still be used in this case)
    private final int storeThresholdMillis;

    // minimum is imposed because of StuckTraceCollector#CHECK_INTERVAL_MILLIS
    // -1 means no stuck messages are gathered, should be minimum 100 milliseconds
    private final int stuckThresholdSeconds;

    // TODO this doesn't really make sense for Filters/servlets? or maybe just not top-level?
    // though even those might be interesting occasionally
    // TODO also re-think the name
    // essentially disabled for now, this needs to be changed to a per-plugin property
    private final int spanStackTraceThresholdMillis;

    // used to limit memory requirement, also used to help limit trace capture size,
    // 0 means don't capture any spans, -1 means no limit
    private final int maxSpans;

    private final int snapshotExpirationHours;

    // size of fixed-length rolling database for storing trace details (spans and merged stack
    // traces)
    private final int rollingSizeMb;

    private final boolean warnOnSpanOutsideTrace;

    static GeneralConfig fromJson(@ReadOnly JsonObject configObject) throws JsonSyntaxException {
        return gson.fromJson(configObject, GeneralConfig.Builder.class).build();
    }

    static GeneralConfig getDefault() {
        return new Builder().build();
    }

    public static Builder builder(GeneralConfig base) {
        return new Builder(base);
    }

    private GeneralConfig(boolean enabled, int storeThresholdMillis, int stuckThresholdSeconds,
            int spanStackTraceThresholdMillis, int maxSpans, int snapshotExpirationHours,
            int rollingSizeMb, boolean warnOnSpanOutsideTrace) {

        this.enabled = enabled;
        this.storeThresholdMillis = storeThresholdMillis;
        this.stuckThresholdSeconds = stuckThresholdSeconds;
        this.spanStackTraceThresholdMillis = spanStackTraceThresholdMillis;
        this.maxSpans = maxSpans;
        this.snapshotExpirationHours = snapshotExpirationHours;
        this.rollingSizeMb = rollingSizeMb;
        this.warnOnSpanOutsideTrace = warnOnSpanOutsideTrace;
    }

    public JsonObject toJson() {
        return gson.toJsonTree(this).getAsJsonObject();
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

    public int getSpanStackTraceThresholdMillis() {
        return spanStackTraceThresholdMillis;
    }

    public int getMaxSpans() {
        return maxSpans;
    }

    public int getSnapshotExpirationHours() {
        return snapshotExpirationHours;
    }

    public int getRollingSizeMb() {
        return rollingSizeMb;
    }

    public boolean isWarnOnSpanOutsideTrace() {
        return warnOnSpanOutsideTrace;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("enabed", enabled)
                .add("storeThresholdMillis", storeThresholdMillis)
                .add("stuckThresholdSeconds", stuckThresholdSeconds)
                .add("spanStackTraceThresholdMillis", spanStackTraceThresholdMillis)
                .add("maxSpans", maxSpans)
                .add("snapshotExpirationHours", snapshotExpirationHours)
                .add("rollingSizeMb", rollingSizeMb)
                .add("warnOnSpanOutsideTrace", warnOnSpanOutsideTrace)
                .toString();
    }

    public static class Builder {

        private boolean enabled = true;
        private int storeThresholdMillis = 3000;
        private int stuckThresholdSeconds = 180;
        private int spanStackTraceThresholdMillis = Integer.MAX_VALUE;
        private int maxSpans = 5000;
        private int snapshotExpirationHours = 24 * 7;
        private int rollingSizeMb = 1000;
        private boolean warnOnSpanOutsideTrace = false;

        private Builder() {}
        private Builder(GeneralConfig base) {
            enabled = base.enabled;
            storeThresholdMillis = base.storeThresholdMillis;
            stuckThresholdSeconds = base.stuckThresholdSeconds;
            spanStackTraceThresholdMillis = base.spanStackTraceThresholdMillis;
            maxSpans = base.maxSpans;
            snapshotExpirationHours = base.snapshotExpirationHours;
            rollingSizeMb = base.rollingSizeMb;
            warnOnSpanOutsideTrace = base.warnOnSpanOutsideTrace;
        }
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }
        public Builder storeThresholdMillis(int storeThresholdMillis) {
            this.storeThresholdMillis = storeThresholdMillis;
            return this;
        }
        public Builder stuckThresholdSeconds(int stuckThresholdSeconds) {
            this.stuckThresholdSeconds = stuckThresholdSeconds;
            return this;
        }
        public Builder spanStackTraceThresholdMillis(int spanStackTraceThresholdMillis) {
            this.spanStackTraceThresholdMillis = spanStackTraceThresholdMillis;
            return this;
        }
        public Builder maxSpans(int maxSpans) {
            this.maxSpans = maxSpans;
            return this;
        }
        public Builder snapshotExpirationHours(int snapshotExpirationHours) {
            this.snapshotExpirationHours = snapshotExpirationHours;
            return this;
        }
        public Builder rollingSizeMb(int rollingSizeMb) {
            this.rollingSizeMb = rollingSizeMb;
            return this;
        }
        public Builder warnOnSpanOutsideTrace(boolean warnOnSpanOutsideTrace) {
            this.warnOnSpanOutsideTrace = warnOnSpanOutsideTrace;
            return this;
        }
        public Builder overlay(@ReadOnly JsonObject configObject) {
            JsonElement enabledElement = configObject.get("enabled");
            if (enabledElement != null) {
                enabled(enabledElement.getAsBoolean());
            }
            JsonElement storeThresholdMillisElement = configObject.get("storeThresholdMillis");
            if (storeThresholdMillisElement != null) {
                storeThresholdMillis(storeThresholdMillisElement.getAsInt());
            }
            JsonElement stuckThresholdSecondsElement = configObject.get("stuckThresholdSeconds");
            if (stuckThresholdSecondsElement != null) {
                stuckThresholdSeconds(stuckThresholdSecondsElement.getAsInt());
            }
            JsonElement spanStackTraceThresholdMillisElement = configObject
                    .get("spanStackTraceThresholdMillis");
            if (spanStackTraceThresholdMillisElement != null) {
                spanStackTraceThresholdMillis(spanStackTraceThresholdMillisElement.getAsInt());
            }
            JsonElement maxSpansElement = configObject.get("maxSpans");
            if (maxSpansElement != null) {
                maxSpans(maxSpansElement.getAsInt());
            }
            JsonElement snapshotExpirationHoursElement = configObject
                    .get("snapshotExpirationHours");
            if (snapshotExpirationHoursElement != null) {
                snapshotExpirationHours(snapshotExpirationHoursElement.getAsInt());
            }
            JsonElement rollingSizeMbElement = configObject.get("rollingSizeMb");
            if (rollingSizeMbElement != null) {
                rollingSizeMb(rollingSizeMbElement.getAsInt());
            }
            JsonElement warnOnSpanOutsideTraceElement = configObject.get("warnOnSpanOutsideTrace");
            if (warnOnSpanOutsideTraceElement != null) {
                warnOnSpanOutsideTrace(warnOnSpanOutsideTraceElement.getAsBoolean());
            }
            return this;
        }
        public GeneralConfig build() {
            return new GeneralConfig(enabled, storeThresholdMillis, stuckThresholdSeconds,
                    spanStackTraceThresholdMillis, maxSpans, snapshotExpirationHours,
                    rollingSizeMb, warnOnSpanOutsideTrace);
        }
    }
}
