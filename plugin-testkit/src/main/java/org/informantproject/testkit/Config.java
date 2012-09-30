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
package org.informantproject.testkit;

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

    public static class CoreConfig {

        private boolean enabled;
        private int persistenceThresholdMillis;
        private int stuckThresholdSeconds;
        private int spanStackTraceThresholdMillis;
        private int maxEntries;
        private int rollingSizeMb;
        private boolean warnOnEntryOutsideTrace;
        private int metricPeriodMillis;

        public boolean isEnabled() {
            return enabled;
        }
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        public int getPersistenceThresholdMillis() {
            return persistenceThresholdMillis;
        }
        public void setPersistenceThresholdMillis(int persistenceThresholdMillis) {
            this.persistenceThresholdMillis = persistenceThresholdMillis;
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
        public int getMaxEntries() {
            return maxEntries;
        }
        public void setMaxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
        }
        public int getRollingSizeMb() {
            return rollingSizeMb;
        }
        public void setRollingSizeMb(int rollingSizeMb) {
            this.rollingSizeMb = rollingSizeMb;
        }
        public boolean isWarnOnEntryOutsideTrace() {
            return warnOnEntryOutsideTrace;
        }
        public void setWarnOnEntryOutsideTrace(boolean warnOnEntryOutsideTrace) {
            this.warnOnEntryOutsideTrace = warnOnEntryOutsideTrace;
        }
        public int getMetricPeriodMillis() {
            return metricPeriodMillis;
        }
        public void setMetricPeriodMillis(int metricPeriodMillis) {
            this.metricPeriodMillis = metricPeriodMillis;
        }
        @Override
        public int hashCode() {
            return Objects.hashCode(enabled, persistenceThresholdMillis, stuckThresholdSeconds,
                    spanStackTraceThresholdMillis, maxEntries, rollingSizeMb,
                    warnOnEntryOutsideTrace, metricPeriodMillis);
        }
        @Override
        public boolean equals(@Nullable Object o) {
            if (!(o instanceof CoreConfig)) {
                return false;
            }
            CoreConfig other = (CoreConfig) o;
            return Objects.equal(enabled, other.enabled)
                    && Objects.equal(persistenceThresholdMillis, other.persistenceThresholdMillis)
                    && Objects.equal(stuckThresholdSeconds, other.stuckThresholdSeconds)
                    && Objects.equal(spanStackTraceThresholdMillis,
                            other.spanStackTraceThresholdMillis)
                    && Objects.equal(maxEntries, other.maxEntries)
                    && Objects.equal(rollingSizeMb, other.rollingSizeMb)
                    && Objects.equal(warnOnEntryOutsideTrace, other.warnOnEntryOutsideTrace)
                    && Objects.equal(metricPeriodMillis, other.metricPeriodMillis);
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
        public int hashCode() {
            return Objects.hashCode(enabled, initialDelayMillis, intervalMillis, totalSeconds);
        }
        @Override
        public boolean equals(@Nullable Object o) {
            if (!(o instanceof CoarseProfilingConfig)) {
                return false;
            }
            CoarseProfilingConfig other = (CoarseProfilingConfig) o;
            return Objects.equal(enabled, other.enabled)
                    && Objects.equal(initialDelayMillis, other.initialDelayMillis)
                    && Objects.equal(intervalMillis, other.intervalMillis)
                    && Objects.equal(totalSeconds, other.totalSeconds);
        }
    }

    public static class FineProfilingConfig {

        private boolean enabled;
        private double tracePercentage;
        private int intervalMillis;
        private int totalSeconds;
        private int persistenceThresholdMillis;

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
        public int getPersistenceThresholdMillis() {
            return persistenceThresholdMillis;
        }
        public void setPersistenceThresholdMillis(int persistenceThresholdMillis) {
            this.persistenceThresholdMillis = persistenceThresholdMillis;
        }
        @Override
        public int hashCode() {
            return Objects.hashCode(enabled, tracePercentage, intervalMillis, totalSeconds,
                    persistenceThresholdMillis);
        }
        @Override
        public boolean equals(@Nullable Object o) {
            if (!(o instanceof FineProfilingConfig)) {
                return false;
            }
            FineProfilingConfig other = (FineProfilingConfig) o;
            return Objects.equal(enabled, other.enabled)
                    && Objects.equal(tracePercentage, other.tracePercentage)
                    && Objects.equal(intervalMillis, other.intervalMillis)
                    && Objects.equal(totalSeconds, other.totalSeconds)
                    && Objects.equal(persistenceThresholdMillis, other.persistenceThresholdMillis);
        }
    }

    public static class UserTracingConfig {

        private boolean enabled;
        private String userId;
        private int persistenceThresholdMillis;
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
        public int getPersistenceThresholdMillis() {
            return persistenceThresholdMillis;
        }
        public void setPersistenceThresholdMillis(int persistenceThresholdMillis) {
            this.persistenceThresholdMillis = persistenceThresholdMillis;
        }
        public boolean isFineProfiling() {
            return fineProfiling;
        }
        public void setFineProfiling(boolean fineProfiling) {
            this.fineProfiling = fineProfiling;
        }
        @Override
        public int hashCode() {
            return Objects.hashCode(enabled, userId, persistenceThresholdMillis, fineProfiling);
        }
        @Override
        public boolean equals(@Nullable Object o) {
            if (!(o instanceof UserTracingConfig)) {
                return false;
            }
            UserTracingConfig other = (UserTracingConfig) o;
            return Objects.equal(enabled, other.enabled)
                    && Objects.equal(userId, other.userId)
                    && Objects.equal(persistenceThresholdMillis, other.persistenceThresholdMillis)
                    && Objects.equal(fineProfiling, other.fineProfiling);
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
        public int hashCode() {
            return Objects.hashCode(enabled, properties);
        }
        @Override
        public boolean equals(@Nullable Object o) {
            if (!(o instanceof PluginConfig)) {
                return false;
            }
            PluginConfig other = (PluginConfig) o;
            return Objects.equal(enabled, other.enabled)
                    && Objects.equal(properties, other.properties);
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
