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

import org.informantproject.api.Optional;

import com.google.common.base.Objects;
import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class Configuration {

    private boolean enabled;
    private CoreConfiguration coreConfiguration;
    private Map<String, PluginConfiguration> pluginConfiguration;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public CoreConfiguration getCoreConfiguration() {
        return coreConfiguration;
    }

    public Map<String, PluginConfiguration> getPluginConfiguration() {
        return pluginConfiguration;
    }

    public static class CoreConfiguration {
        private int thresholdMillis;
        private int stuckThresholdSeconds;
        private int stackTraceInitialDelayMillis;
        private int stackTracePeriodMillis;
        private int spanStackTraceThresholdMillis;
        private int maxSpansPerTrace;
        private int rollingSizeMb;
        private boolean warnOnSpanOutsideTrace;
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
        public int getStackTraceInitialDelayMillis() {
            return stackTraceInitialDelayMillis;
        }
        public void setStackTraceInitialDelayMillis(int stackTraceInitialDelayMillis) {
            this.stackTraceInitialDelayMillis = stackTraceInitialDelayMillis;
        }
        public int getStackTracePeriodMillis() {
            return stackTracePeriodMillis;
        }
        public void setStackTracePeriodMillis(int stackTracePeriodMillis) {
            this.stackTracePeriodMillis = stackTracePeriodMillis;
        }
        public int getSpanStackTraceThresholdMillis() {
            return spanStackTraceThresholdMillis;
        }
        public void setSpanStackTraceThresholdMillis(int spanStackTraceThresholdMillis) {
            this.spanStackTraceThresholdMillis = spanStackTraceThresholdMillis;
        }
        public int getMaxSpansPerTrace() {
            return maxSpansPerTrace;
        }
        public void setMaxSpansPerTrace(int maxSpansPerTrace) {
            this.maxSpansPerTrace = maxSpansPerTrace;
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
        public int hashCode() {
            return Objects.hashCode(thresholdMillis, stuckThresholdSeconds,
                    stackTraceInitialDelayMillis, stackTracePeriodMillis,
                    spanStackTraceThresholdMillis, maxSpansPerTrace,
                    rollingSizeMb, warnOnSpanOutsideTrace, metricPeriodMillis);
        }
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof CoreConfiguration)) {
                return false;
            }
            CoreConfiguration other = (CoreConfiguration) o;
            return Objects.equal(thresholdMillis, other.thresholdMillis)
                    && Objects.equal(stuckThresholdSeconds, other.stuckThresholdSeconds)
                    && Objects.equal(stackTraceInitialDelayMillis,
                            other.stackTraceInitialDelayMillis)
                    && Objects.equal(stackTracePeriodMillis, other.stackTracePeriodMillis)
                    && Objects.equal(spanStackTraceThresholdMillis,
                            other.spanStackTraceThresholdMillis)
                    && Objects.equal(maxSpansPerTrace, other.maxSpansPerTrace)
                    && Objects.equal(rollingSizeMb, other.rollingSizeMb)
                    && Objects.equal(warnOnSpanOutsideTrace, other.warnOnSpanOutsideTrace)
                    && Objects.equal(metricPeriodMillis, other.metricPeriodMillis);
        }
    }

    public static class PluginConfiguration {
        private boolean enabled;
        private final Map<String, Optional<Object>> properties = Maps.newHashMap();
        public boolean isEnabled() {
            return enabled;
        }
        public Optional<Object> getProperty(String name) {
            return properties.get(name);
        }
        public void setProperty(String name, Object value) {
            properties.put(name, Optional.fromNullable(value));
        }
        public String getPropertiesJson() {
            Gson gson = new GsonBuilder().registerTypeHierarchyAdapter(Optional.class,
                    new OptionalJsonSerializer()).serializeNulls().create();
            return gson.toJson(properties);
        }
    }

    public static class PluginConfigurationJsonDeserializer implements
            JsonDeserializer<PluginConfiguration> {

        public PluginConfiguration deserialize(JsonElement json, Type typeOfT,
                JsonDeserializationContext context) throws JsonParseException {

            PluginConfiguration pluginConfiguration = new PluginConfiguration();
            pluginConfiguration.enabled = json.getAsJsonObject().get("enabled").getAsBoolean();
            JsonObject properties = json.getAsJsonObject().get("properties").getAsJsonObject();
            for (Entry<String, JsonElement> entry : properties.entrySet()) {
                if (entry.getValue().isJsonNull()) {
                    pluginConfiguration.setProperty(entry.getKey(), null);
                } else {
                    pluginConfiguration.setProperty(entry.getKey(), context.deserialize(entry
                            .getValue(), Object.class));
                }
            }
            return pluginConfiguration;
        }
    }

    private static class OptionalJsonSerializer implements JsonSerializer<Optional<?>> {

        public JsonElement serialize(Optional<?> src, Type typeOfSrc,
                JsonSerializationContext context) {
            return src.isPresent() ? context.serialize(src.get()) : JsonNull.INSTANCE;
        }
    }
}
