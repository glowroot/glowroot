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
package org.informantproject.core.configuration;

import com.google.common.base.Objects;
import com.google.common.base.Objects.ToStringHelper;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Immutable structure to hold the current core configuration.
 * 
 * Default values should be conservative.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ImmutableCoreConfiguration {

    public static final int SPAN_LIMIT_DISABLED = -1;
    public static final int THRESHOLD_DISABLED = -1;

    // if tracing is disabled mid-trace there should be no issue
    // active traces will not accumulate additional spans
    // but they will be logged / emailed if they exceed the defined thresholds
    //
    // if tracing is enabled mid-trace there should be no issue
    // active traces that were not captured at their start will
    // continue not to accumulate spans
    // and they will not be logged / emailed even if they exceed the defined
    // thresholds
    private boolean enabled = true;

    // TODO convert from millis to seconds, support 0.1, etc
    // 0 means log all traces, -1 means log no traces
    // (though stuck threshold can still be used in this case)
    private int thresholdMillis = 3000;

    // minimum is imposed because of StuckTraceCollector#CHECK_INTERVAL_MILLIS
    // -1 means no stuck messages are gathered, should be minimum 100 milliseconds
    private int stuckThresholdSeconds = 180;

    // minimum is imposed because of StackCollector#CHECK_INTERVAL_MILLIS
    // -1 means no stack traces are gathered, should be minimum 100 milliseconds
    private int profilerInitialDelayMillis = 1000;

    private int profilerIntervalMillis = 100;

    // TODO this doesn't really make sense for Filters/servlets? or maybe just not top-level?
    // though even those might be interesting occasionally
    // TODO also re-think the name
    // essentially disabled for now, this needs to be changed to a per-plugin property
    private int spanStackTraceThresholdMillis = Integer.MAX_VALUE;

    // used to limit memory requirement, also used to help limit log file size,
    // 0 means don't capture any traces, -1 means no limit
    private int maxEntries = 5000;

    // size of fixed-length rolling database for storing trace details (spans and merged stack
    // traces)
    private int rollingSizeMb = 1000;

    private boolean warnOnEntryOutsideTrace = false;

    private int metricPeriodMillis = 15000;

    public boolean isEnabled() {
        return enabled;
    }

    public int getThresholdMillis() {
        return thresholdMillis;
    }

    public int getStuckThresholdSeconds() {
        return stuckThresholdSeconds;
    }

    public int getProfilerInitialDelayMillis() {
        return profilerInitialDelayMillis;
    }

    public int getProfilerIntervalMillis() {
        return profilerIntervalMillis;
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

    public String getPropertiesJson() {
        JsonObject propertiesJson = new JsonObject();
        propertiesJson.addProperty("thresholdMillis", thresholdMillis);
        propertiesJson.addProperty("stuckThresholdSeconds", stuckThresholdSeconds);
        propertiesJson.addProperty("profilerInitialDelayMillis", profilerInitialDelayMillis);
        propertiesJson.addProperty("profilerIntervalMillis", profilerIntervalMillis);
        propertiesJson.addProperty("spanStackTraceThresholdMillis", spanStackTraceThresholdMillis);
        propertiesJson.addProperty("maxEntries", maxEntries);
        propertiesJson.addProperty("rollingSizeMb", rollingSizeMb);
        propertiesJson.addProperty("warnOnEntryOutsideTrace", warnOnEntryOutsideTrace);
        propertiesJson.addProperty("metricPeriodMillis", metricPeriodMillis);
        return new Gson().toJson(propertiesJson);
    }

    @Override
    public String toString() {
        ToStringHelper toStringHelper = Objects.toStringHelper(this)
                .add("enabed", enabled)
                .add("thresholdMillis", thresholdMillis)
                .add("stuckThresholdSeconds", stuckThresholdSeconds)
                .add("profilerInitialDelayMillis", profilerInitialDelayMillis)
                .add("profilerIntervalMillis", profilerIntervalMillis)
                .add("spanStackTraceThresholdMillis", spanStackTraceThresholdMillis)
                .add("maxEntries", maxEntries)
                .add("rollingSizeMb", rollingSizeMb)
                .add("warnOnEntryOutsideTrace", warnOnEntryOutsideTrace)
                .add("metricPeriodMillis", metricPeriodMillis);
        return toStringHelper.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ImmutableCoreConfiguration)) {
            return false;
        }
        ImmutableCoreConfiguration other = (ImmutableCoreConfiguration) o;
        return Objects.equal(enabled, other.isEnabled())
                && Objects.equal(thresholdMillis, other.getThresholdMillis())
                && Objects.equal(stuckThresholdSeconds, other.getStuckThresholdSeconds())
                && Objects.equal(profilerInitialDelayMillis, other
                        .getProfilerInitialDelayMillis())
                && Objects.equal(profilerIntervalMillis, other.getProfilerIntervalMillis())
                && Objects.equal(spanStackTraceThresholdMillis, other
                        .getSpanStackTraceThresholdMillis())
                && Objects.equal(maxEntries, other.getMaxEntries())
                && Objects.equal(rollingSizeMb, other.getRollingSizeMb())
                && Objects.equal(warnOnEntryOutsideTrace, other.isWarnOnEntryOutsideTrace())
                && Objects.equal(metricPeriodMillis, other.getMetricPeriodMillis());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(enabled, thresholdMillis, stuckThresholdSeconds,
                profilerInitialDelayMillis, profilerIntervalMillis,
                spanStackTraceThresholdMillis, maxEntries, rollingSizeMb,
                warnOnEntryOutsideTrace, metricPeriodMillis);
    }

    static ImmutableCoreConfiguration create(boolean enabled, String propertiesJson) {
        return new Gson().fromJson(propertiesJson, CoreConfigurationBuilder.class)
                .setEnabled(enabled)
                .build();
    }

    public static class CoreConfigurationBuilder {

        private boolean enabled;
        private int thresholdMillis;
        private int stuckThresholdSeconds;
        private int profilerInitialDelayMillis;
        private int profilerIntervalMillis;
        private int spanStackTraceThresholdMillis;
        private int maxEntries;
        private int rollingSizeMb;
        private boolean warnOnEntryOutsideTrace;
        private int metricPeriodMillis;

        public CoreConfigurationBuilder() {
            this(new ImmutableCoreConfiguration());
        }

        public CoreConfigurationBuilder(ImmutableCoreConfiguration base) {
            enabled = base.enabled;
            thresholdMillis = base.thresholdMillis;
            stuckThresholdSeconds = base.stuckThresholdSeconds;
            profilerInitialDelayMillis = base.profilerInitialDelayMillis;
            profilerIntervalMillis = base.profilerIntervalMillis;
            spanStackTraceThresholdMillis = base.spanStackTraceThresholdMillis;
            maxEntries = base.maxEntries;
            rollingSizeMb = base.rollingSizeMb;
            warnOnEntryOutsideTrace = base.warnOnEntryOutsideTrace;
            metricPeriodMillis = base.metricPeriodMillis;
        }

        public ImmutableCoreConfiguration build() {
            ImmutableCoreConfiguration configuration = new ImmutableCoreConfiguration();
            configuration.enabled = enabled;
            configuration.thresholdMillis = thresholdMillis;
            configuration.stuckThresholdSeconds = stuckThresholdSeconds;
            configuration.profilerInitialDelayMillis = profilerInitialDelayMillis;
            configuration.profilerIntervalMillis = profilerIntervalMillis;
            configuration.spanStackTraceThresholdMillis = spanStackTraceThresholdMillis;
            configuration.maxEntries = maxEntries;
            configuration.rollingSizeMb = rollingSizeMb;
            configuration.warnOnEntryOutsideTrace = warnOnEntryOutsideTrace;
            configuration.metricPeriodMillis = metricPeriodMillis;
            return configuration;
        }

        public void setFromJson(JsonObject jsonObject) {
            if (jsonObject.get("thresholdMillis") != null) {
                setThresholdMillis(jsonObject.get("thresholdMillis").getAsInt());
            }
            if (jsonObject.get("stuckThresholdSeconds") != null) {
                setStuckThresholdSeconds(jsonObject.get("stuckThresholdSeconds").getAsInt());
            }
            if (jsonObject.get("profilerInitialDelayMillis") != null) {
                setProfilerInitialDelayMillis(jsonObject.get("profilerInitialDelayMillis")
                        .getAsInt());
            }
            if (jsonObject.get("profilerIntervalMillis") != null) {
                setProfilerIntervalMillis(jsonObject.get("profilerIntervalMillis").getAsInt());
            }
            if (jsonObject.get("spanStackTraceThresholdMillis") != null) {
                setSpanStackTraceThresholdMillis(jsonObject.get("spanStackTraceThresholdMillis")
                        .getAsInt());
            }
            if (jsonObject.get("maxEntries") != null) {
                setMaxEntries(jsonObject.get("maxEntries").getAsInt());
            }
            if (jsonObject.get("rollingSizeMb") != null) {
                setRollingSizeMb(jsonObject.get("rollingSizeMb").getAsInt());
            }
            if (jsonObject.get("warnOnEntryOutsideTrace") != null) {
                setWarnOnEntryOutsideTrace(jsonObject.get("warnOnEntryOutsideTrace")
                        .getAsBoolean());
            }
            if (jsonObject.get("metricPeriodMillis") != null) {
                setMetricPeriodMillis(jsonObject.get("metricPeriodMillis").getAsInt());
            }
        }

        public CoreConfigurationBuilder setEnabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public CoreConfigurationBuilder setThresholdMillis(int thresholdMillis) {
            this.thresholdMillis = thresholdMillis;
            return this;
        }

        public CoreConfigurationBuilder setStuckThresholdSeconds(int stuckThresholdSeconds) {
            this.stuckThresholdSeconds = stuckThresholdSeconds;
            return this;
        }

        public CoreConfigurationBuilder setProfilerInitialDelayMillis(
                int profilerInitialDelayMillis) {

            this.profilerInitialDelayMillis = profilerInitialDelayMillis;
            return this;
        }

        public CoreConfigurationBuilder setProfilerIntervalMillis(int profilerIntervalMillis) {
            this.profilerIntervalMillis = profilerIntervalMillis;
            return this;
        }

        public CoreConfigurationBuilder setSpanStackTraceThresholdMillis(
                int spanStackTraceThresholdMillis) {

            this.spanStackTraceThresholdMillis = spanStackTraceThresholdMillis;
            return this;
        }

        public CoreConfigurationBuilder setMaxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
            return this;
        }

        public CoreConfigurationBuilder setRollingSizeMb(int rollingSizeMb) {
            this.rollingSizeMb = rollingSizeMb;
            return this;
        }

        public CoreConfigurationBuilder setWarnOnEntryOutsideTrace(
                boolean warnOnEntryOutsideTrace) {

            this.warnOnEntryOutsideTrace = warnOnEntryOutsideTrace;
            return this;
        }

        public CoreConfigurationBuilder setMetricPeriodMillis(int metricPeriodMillis) {
            this.metricPeriodMillis = metricPeriodMillis;
            return this;
        }
    }
}
