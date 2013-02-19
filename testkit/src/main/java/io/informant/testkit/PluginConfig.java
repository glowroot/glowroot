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

import io.informant.testkit.internal.GsonFactory;

import java.util.Map;

import checkers.nullness.quals.Nullable;

import com.google.common.base.Objects;
import com.google.common.collect.Maps;
import com.google.gson.Gson;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class PluginConfig {

    private static final Gson gson = GsonFactory.newBuilder().serializeNulls().create();

    private boolean enabled;
    private final Map<String, /*@Nullable*/Object> properties = Maps.newHashMap();
    @Nullable
    private String versionHash;

    String toJson() {
        return gson.toJson(this);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean hasProperty(String name) {
        return properties.containsKey(name);
    }

    @Nullable
    public Object getProperty(String name) {
        return properties.get(name);
    }

    public void setProperty(String name, @Nullable Object value) {
        properties.put(name, value);
    }

    @Nullable
    public String getVersionHash() {
        return versionHash;
    }

    public void setVersionHash(String versionHash) {
        this.versionHash = versionHash;
    }

    Map<String, /*@Nullable*/Object> getProperties() {
        return properties;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof PluginConfig) {
            PluginConfig that = (PluginConfig) obj;
            // intentionally leaving off versionHash since it represents the prior version hash when
            // sending to the server, and represents the current version hash when receiving from
            // the server
            return Objects.equal(enabled, that.enabled)
                    && Objects.equal(properties, that.properties);
        }
        return false;
    }

    @Override
    public int hashCode() {
        // intentionally leaving off versionHash since it represents the prior version hash when
        // sending to the server, and represents the current version hash when receiving from the
        // server
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
