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
package io.informant.testkit;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.google.common.base.Objects;
import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class Config {

    private CoreConfig coreConfig;
    private CoarseProfilingConfig coarseProfilingConfig;
    private FineProfilingConfig fineProfilingConfig;
    private UserTracingConfig userTracingConfig;
    private Map<String, PluginConfig> pluginConfigs;

    public CoreConfig getCoreConfig() {
        return coreConfig;
    }

    public CoarseProfilingConfig getCoarseProfilingConfig() {
        return coarseProfilingConfig;
    }

    public FineProfilingConfig getFineProfilingConfig() {
        return fineProfilingConfig;
    }

    public UserTracingConfig getUserTracingConfig() {
        return userTracingConfig;
    }

    public Map<String, PluginConfig> getPluginConfigs() {
        return pluginConfigs;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof Config) {
            Config that = (Config) obj;
            return Objects.equal(coreConfig, that.coreConfig)
                    && Objects.equal(coarseProfilingConfig, that.coarseProfilingConfig)
                    && Objects.equal(fineProfilingConfig, that.fineProfilingConfig)
                    && Objects.equal(userTracingConfig, that.userTracingConfig)
                    && Objects.equal(pluginConfigs, that.pluginConfigs);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(coreConfig, coarseProfilingConfig, fineProfilingConfig,
                userTracingConfig, pluginConfigs);
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("coreConfig", coreConfig)
                .add("coarseProfilingConfig", coarseProfilingConfig)
                .add("fineProfilingConfig", fineProfilingConfig)
                .add("userTracingConfig", userTracingConfig)
                .add("pluginConfigs", pluginConfigs)
                .toString();
    }

    public static class CoreConfig {

        private boolean enabled;
        private int storeThresholdMillis;
        private int stuckThresholdSeconds;
        private int spanStackTraceThresholdMillis;
        private int maxSpans;
        private int rollingSizeMb;
        private boolean warnOnSpanOutsideTrace;
        private int metricPeriodMillis;

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
        public int getSpanStackTraceThresholdMillis() {
            return spanStackTraceThresholdMillis;
        }
        public void setSpanStackTraceThresholdMillis(int spanStackTraceThresholdMillis) {
            this.spanStackTraceThresholdMillis = spanStackTraceThresholdMillis;
        }
        public int getMaxSpans() {
            return maxSpans;
        }
        public void setMaxSpans(int maxSpans) {
            this.maxSpans = maxSpans;
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
        public int getMetricPeriodMillis() {
            return metricPeriodMillis;
        }
        public void setMetricPeriodMillis(int metricPeriodMillis) {
            this.metricPeriodMillis = metricPeriodMillis;
        }
        @Override
        public boolean equals(@Nullable Object obj) {
            if (obj instanceof CoreConfig) {
                CoreConfig that = (CoreConfig) obj;
                return Objects.equal(enabled, that.enabled)
                        && Objects.equal(storeThresholdMillis,
                                that.storeThresholdMillis)
                        && Objects.equal(stuckThresholdSeconds, that.stuckThresholdSeconds)
                        && Objects.equal(spanStackTraceThresholdMillis,
                                that.spanStackTraceThresholdMillis)
                        && Objects.equal(maxSpans, that.maxSpans)
                        && Objects.equal(rollingSizeMb, that.rollingSizeMb)
                        && Objects.equal(warnOnSpanOutsideTrace, that.warnOnSpanOutsideTrace)
                        && Objects.equal(metricPeriodMillis, that.metricPeriodMillis);
            }
            return false;
        }
        @Override
        public int hashCode() {
            return Objects.hashCode(enabled, storeThresholdMillis, stuckThresholdSeconds,
                    spanStackTraceThresholdMillis, maxSpans, rollingSizeMb, warnOnSpanOutsideTrace,
                    metricPeriodMillis);
        }
        @Override
        public String toString() {
            return Objects.toStringHelper(this)
                    .add("enabled", enabled)
                    .add("storeThresholdMillis", storeThresholdMillis)
                    .add("stuckThresholdSeconds", stuckThresholdSeconds)
                    .add("spanStackTraceThresholdMillis", spanStackTraceThresholdMillis)
                    .add("maxSpans", maxSpans)
                    .add("rollingSizeMb", rollingSizeMb)
                    .add("warnOnSpanOutsideTrace", warnOnSpanOutsideTrace)
                    .add("metricPeriodMillis", metricPeriodMillis)
                    .toString();
        }
    }

    public static class CoarseProfilingConfig {

        private boolean enabled;
        private int initialDelayMillis;
        private int intervalMillis;
        private int totalSeconds;

        public boolean isEnabled() {
            return enabled;
        }
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        public int getInitialDelayMillis() {
            return initialDelayMillis;
        }
        public void setInitialDelayMillis(int initialDelayMillis) {
            this.initialDelayMillis = initialDelayMillis;
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
        @Override
        public boolean equals(@Nullable Object obj) {
            if (obj instanceof CoarseProfilingConfig) {
                CoarseProfilingConfig that = (CoarseProfilingConfig) obj;
                return Objects.equal(enabled, that.enabled)
                        && Objects.equal(initialDelayMillis, that.initialDelayMillis)
                        && Objects.equal(intervalMillis, that.intervalMillis)
                        && Objects.equal(totalSeconds, that.totalSeconds);
            }
            return false;
        }
        @Override
        public int hashCode() {
            return Objects.hashCode(enabled, initialDelayMillis, intervalMillis, totalSeconds);
        }
        @Override
        public String toString() {
            return Objects.toStringHelper(this)
                    .add("enabled", enabled)
                    .add("initialDelayMillis", initialDelayMillis)
                    .add("intervalMillis", intervalMillis)
                    .add("totalSeconds", totalSeconds)
                    .toString();
        }
    }

    public static class FineProfilingConfig {

        private boolean enabled;
        private double tracePercentage;
        private int intervalMillis;
        private int totalSeconds;
        private int storeThresholdMillis;

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
        @Override
        public boolean equals(@Nullable Object obj) {
            if (obj instanceof FineProfilingConfig) {
                FineProfilingConfig that = (FineProfilingConfig) obj;
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
                    .toString();
        }
    }

    public static class UserTracingConfig {

        private boolean enabled;
        private String userId;
        private int storeThresholdMillis;
        private boolean fineProfiling;

        public boolean isEnabled() {
            return enabled;
        }
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        public String getUserId() {
            return userId;
        }
        public void setUserId(String userId) {
            this.userId = userId;
        }
        public int getStoreThresholdMillis() {
            return storeThresholdMillis;
        }
        public void setStoreThresholdMillis(int storeThresholdMillis) {
            this.storeThresholdMillis = storeThresholdMillis;
        }
        public boolean isFineProfiling() {
            return fineProfiling;
        }
        public void setFineProfiling(boolean fineProfiling) {
            this.fineProfiling = fineProfiling;
        }
        @Override
        public boolean equals(@Nullable Object obj) {
            if (obj instanceof UserTracingConfig) {
                UserTracingConfig that = (UserTracingConfig) obj;
                return Objects.equal(enabled, that.enabled)
                        && Objects.equal(userId, that.userId)
                        && Objects.equal(storeThresholdMillis,
                                that.storeThresholdMillis)
                        && Objects.equal(fineProfiling, that.fineProfiling);
            }
            return false;
        }
        @Override
        public int hashCode() {
            return Objects.hashCode(enabled, userId, storeThresholdMillis, fineProfiling);
        }
        @Override
        public String toString() {
            return Objects.toStringHelper(this)
                    .add("enabled", enabled)
                    .add("userId", userId)
                    .add("storeThresholdMillis", storeThresholdMillis)
                    .add("fineProfiling", fineProfiling)
                    .toString();
        }
    }

    public static class PluginConfig {

        private boolean enabled;
        // map values are @Nullable
        private final Map<String, Object> properties = Maps.newHashMap();

        String toJson() {
            Gson gson = new GsonBuilder().serializeNulls().create();
            JsonObject jsonObject = gson.toJsonTree(properties).getAsJsonObject();
            jsonObject.addProperty("enabled", enabled);
            return gson.toJson(jsonObject);
        }
        public boolean isEnabled() {
            return enabled;
        }
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        @Nullable
        public Object getProperty(String name) {
            return properties.get(name);
        }
        public void setProperty(String name, @Nullable Object value) {
            properties.put(name, value);
        }
        @Override
        public boolean equals(@Nullable Object obj) {
            if (obj instanceof PluginConfig) {
                PluginConfig that = (PluginConfig) obj;
                return Objects.equal(enabled, that.enabled)
                        && Objects.equal(properties, that.properties);
            }
            return false;
        }
        @Override
        public int hashCode() {
            return Objects.hashCode(enabled, properties);
        }
        @Override
        public String toString() {
            return Objects.toStringHelper(this)
                    .add("enabled", enabled)
                    .add("properties", properties)
                    .toString();
        }
    }

    static class PluginConfigJsonDeserializer implements JsonDeserializer<PluginConfig> {
        public PluginConfig deserialize(JsonElement json, Type typeOfT,
                JsonDeserializationContext context) {

            PluginConfig pluginConfig = new PluginConfig();
            for (Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
                if (entry.getKey().equals("enabled")) {
                    pluginConfig.enabled = entry.getValue().getAsBoolean();
                } else if (entry.getValue().isJsonNull()) {
                    pluginConfig.setProperty(entry.getKey(), null);
                } else {
                    pluginConfig.setProperty(entry.getKey(),
                            context.deserialize(entry.getValue(), Object.class));
                }
            }
            return pluginConfig;
        }
    }
}
