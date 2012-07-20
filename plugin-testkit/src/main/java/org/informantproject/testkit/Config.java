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

    private boolean enabled;
    private CoreProperties coreProperties;
    private Map<String, PluginConfig> pluginConfigs;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public CoreProperties getCoreProperties() {
        return coreProperties;
    }

    public Map<String, PluginConfig> getPluginConfigs() {
        return pluginConfigs;
    }

    public static class CoreProperties {
        private int thresholdMillis;
        private int stuckThresholdSeconds;
        private int profilerInitialDelayMillis;
        private int profilerIntervalMillis;
        private int spanStackTraceThresholdMillis;
        private int maxEntries;
        private int rollingSizeMb;
        private boolean warnOnEntryOutsideTrace;
        private int metricPeriodMillis;
        public int getThresholdMillis() {
            return thresholdMillis;
        }
        public void setThresholdMillis(int thresholdMillis) {
            this.thresholdMillis = thresholdMillis;
        }
        public int getStuckThresholdSeconds() {
            return stuckThresholdSeconds;
        }
        public void setStuckThresholdSeconds(int stuckThresholdSeconds) {
            this.stuckThresholdSeconds = stuckThresholdSeconds;
        }
        public int getProfilerInitialDelayMillis() {
            return profilerInitialDelayMillis;
        }
        public void setProfilerInitialDelayMillis(int profilerInitialDelayMillis) {
            this.profilerInitialDelayMillis = profilerInitialDelayMillis;
        }
        public int getProfilerIntervalMillis() {
            return profilerIntervalMillis;
        }
        public void setProfilerIntervalMillis(int profilerIntervalMillis) {
            this.profilerIntervalMillis = profilerIntervalMillis;
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
            return Objects.hashCode(thresholdMillis, stuckThresholdSeconds,
                    profilerInitialDelayMillis, profilerIntervalMillis,
                    spanStackTraceThresholdMillis, maxEntries,
                    rollingSizeMb, warnOnEntryOutsideTrace, metricPeriodMillis);
        }
        @Override
        public boolean equals(@Nullable Object o) {
            if (!(o instanceof CoreProperties)) {
                return false;
            }
            CoreProperties other = (CoreProperties) o;
            return Objects.equal(thresholdMillis, other.thresholdMillis)
                    && Objects.equal(stuckThresholdSeconds, other.stuckThresholdSeconds)
                    && Objects.equal(profilerInitialDelayMillis, other.profilerInitialDelayMillis)
                    && Objects.equal(profilerIntervalMillis, other.profilerIntervalMillis)
                    && Objects.equal(spanStackTraceThresholdMillis,
                            other.spanStackTraceThresholdMillis)
                    && Objects.equal(maxEntries, other.maxEntries)
                    && Objects.equal(rollingSizeMb, other.rollingSizeMb)
                    && Objects.equal(warnOnEntryOutsideTrace, other.warnOnEntryOutsideTrace)
                    && Objects.equal(metricPeriodMillis, other.metricPeriodMillis);
        }
    }

    public static class PluginConfig {
        private boolean enabled;
        // map values are @Nullable
        private final Map<String, Object> properties = Maps.newHashMap();
        public boolean isEnabled() {
            return enabled;
        }
        @Nullable
        public Object getProperty(String name) {
            return properties.get(name);
        }
        public void setProperty(String name, @Nullable Object value) {
            properties.put(name, value);
        }
        public String getPropertiesJson() {
            return new GsonBuilder().serializeNulls().create().toJson(properties);
        }
    }

    public static class PluginConfigJsonDeserializer implements JsonDeserializer<PluginConfig> {
        public PluginConfig deserialize(JsonElement json, Type typeOfT,
                JsonDeserializationContext context) {

            PluginConfig pluginConfig = new PluginConfig();
            pluginConfig.enabled = json.getAsJsonObject().get("enabled").getAsBoolean();
            JsonObject properties = json.getAsJsonObject().get("properties").getAsJsonObject();
            for (Entry<String, JsonElement> entry : properties.entrySet()) {
                if (entry.getValue().isJsonNull()) {
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
