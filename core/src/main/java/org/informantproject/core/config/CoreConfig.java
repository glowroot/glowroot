/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.core.config;

import javax.annotation.concurrent.Immutable;

import org.informantproject.core.util.UnitTests.OnlyUsedByTests;

import com.google.common.base.Objects;
import com.google.common.base.Objects.ToStringHelper;
import com.google.gson.Gson;

/**
 * Immutable structure to hold the current core config.
 * 
 * Default values should be conservative.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class CoreConfig {

    public static final int SPAN_LIMIT_DISABLED = -1;
    public static final int PERSISTENCE_THRESHOLD_DISABLED = -1;

    private static final Gson gson = new Gson();

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
    private final int persistenceThresholdMillis;

    // minimum is imposed because of StuckTraceCollector#CHECK_INTERVAL_MILLIS
    // -1 means no stuck messages are gathered, should be minimum 100 milliseconds
    private final int stuckThresholdSeconds;

    // TODO this doesn't really make sense for Filters/servlets? or maybe just not top-level?
    // though even those might be interesting occasionally
    // TODO also re-think the name
    // essentially disabled for now, this needs to be changed to a per-plugin property
    private final int spanStackTraceThresholdMillis;

    // used to limit memory requirement, also used to help limit log file size,
    // 0 means don't capture any traces, -1 means no limit
    private final int maxEntries;

    // size of fixed-length rolling database for storing trace details (spans and merged stack
    // traces)
    private final int rollingSizeMb;

    private final boolean warnOnEntryOutsideTrace;

    private final int metricPeriodMillis;

    static CoreConfig getDefaultInstance() {
        return new Builder().build();
    }

    static CoreConfig fromJson(String json) {
        return gson.fromJson(json, CoreConfig.Builder.class).build();
    }

    @OnlyUsedByTests
    static Builder builder() {
        return new Builder();
    }

    public static Builder builder(CoreConfig base) {
        return new Builder(base);
    }

    private CoreConfig(boolean enabled, int persistenceThresholdMillis, int stuckThresholdSeconds,
            int spanStackTraceThresholdMillis, int maxEntries, int rollingSizeMb,
            boolean warnOnEntryOutsideTrace, int metricPeriodMillis) {

        this.enabled = enabled;
        this.persistenceThresholdMillis = persistenceThresholdMillis;
        this.stuckThresholdSeconds = stuckThresholdSeconds;
        this.spanStackTraceThresholdMillis = spanStackTraceThresholdMillis;
        this.maxEntries = maxEntries;
        this.rollingSizeMb = rollingSizeMb;
        this.warnOnEntryOutsideTrace = warnOnEntryOutsideTrace;
        this.metricPeriodMillis = metricPeriodMillis;
    }

    public String toJson() {
        return gson.toJson(this);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getPersistenceThresholdMillis() {
        return persistenceThresholdMillis;
    }

    public int getStuckThresholdSeconds() {
        return stuckThresholdSeconds;
    }

    public int getSpanStackTraceThresholdMillis() {
        return spanStackTraceThresholdMillis;
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    public int getRollingSizeMb() {
        return rollingSizeMb;
    }

    public boolean isWarnOnEntryOutsideTrace() {
        return warnOnEntryOutsideTrace;
    }

    public int getMetricPeriodMillis() {
        return metricPeriodMillis;
    }

    @Override
    public String toString() {
        ToStringHelper toStringHelper = Objects.toStringHelper(this)
                .add("enabed", enabled)
                .add("persistenceThresholdMillis", persistenceThresholdMillis)
                .add("stuckThresholdSeconds", stuckThresholdSeconds)
                .add("spanStackTraceThresholdMillis", spanStackTraceThresholdMillis)
                .add("maxEntries", maxEntries)
                .add("rollingSizeMb", rollingSizeMb)
                .add("warnOnEntryOutsideTrace", warnOnEntryOutsideTrace)
                .add("metricPeriodMillis", metricPeriodMillis);
        return toStringHelper.toString();
    }

    public static class Builder {

        private boolean enabled = true;
        private int persistenceThresholdMillis = 3000;
        private int stuckThresholdSeconds = 180;
        private int spanStackTraceThresholdMillis = Integer.MAX_VALUE;
        private int maxEntries = 5000;
        private int rollingSizeMb = 1000;
        private boolean warnOnEntryOutsideTrace = false;
        private int metricPeriodMillis = 15000;

        private Builder() {}
        private Builder(CoreConfig base) {
            enabled = base.enabled;
            persistenceThresholdMillis = base.persistenceThresholdMillis;
            stuckThresholdSeconds = base.stuckThresholdSeconds;
            spanStackTraceThresholdMillis = base.spanStackTraceThresholdMillis;
            maxEntries = base.maxEntries;
            rollingSizeMb = base.rollingSizeMb;
            warnOnEntryOutsideTrace = base.warnOnEntryOutsideTrace;
            metricPeriodMillis = base.metricPeriodMillis;
        }
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }
        public Builder persistenceThresholdMillis(int persistenceThresholdMillis) {
            this.persistenceThresholdMillis = persistenceThresholdMillis;
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
        public Builder maxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
            return this;
        }
        public Builder rollingSizeMb(int rollingSizeMb) {
            this.rollingSizeMb = rollingSizeMb;
            return this;
        }
        public Builder warnOnEntryOutsideTrace(boolean warnOnEntryOutsideTrace) {
            this.warnOnEntryOutsideTrace = warnOnEntryOutsideTrace;
            return this;
        }
        public Builder metricPeriodMillis(int metricPeriodMillis) {
            this.metricPeriodMillis = metricPeriodMillis;
            return this;
        }
        public CoreConfig build() {
            return new CoreConfig(enabled, persistenceThresholdMillis, stuckThresholdSeconds,
                    spanStackTraceThresholdMillis, maxEntries, rollingSizeMb,
                    warnOnEntryOutsideTrace, metricPeriodMillis);
        }
    }
}
