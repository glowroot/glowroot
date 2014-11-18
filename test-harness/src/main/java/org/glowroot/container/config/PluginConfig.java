/*
 * Copyright 2011-2014 the original author or authors.
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
package org.glowroot.container.config;

import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.Maps;

import static org.glowroot.container.common.ObjectMappers.checkRequiredProperty;

public class PluginConfig {

    private boolean enabled;
    private final Map<String, /*@Nullable*/Object> properties;

    private final String version;

    public PluginConfig(String version) {
        properties = Maps.newHashMap();
        this.version = version;
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

    public @Nullable Object getProperty(String name) {
        return properties.get(name);
    }

    public void setProperty(String name, @Nullable Object value) {
        properties.put(name, value);
    }

    @JsonProperty
    public Map<String, /*@Nullable*/Object> getProperties() {
        return properties;
    }

    public String getVersion() {
        return version;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof PluginConfig) {
            PluginConfig that = (PluginConfig) obj;
            // intentionally leaving off version since it represents the prior version hash when
            // sending to the server, and represents the current version hash when receiving from
            // the server
            return Objects.equal(enabled, that.enabled)
                    && Objects.equal(properties, that.properties);
        }
        return false;
    }

    @Override
    public int hashCode() {
        // intentionally leaving off version since it represents the prior version hash when
        // sending to the server, and represents the current version hash when receiving from the
        // server
        return Objects.hashCode(enabled, properties);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("enabled", enabled)
                .add("properties", properties)
                .add("version", version)
                .toString();
    }

    @JsonCreator
    static PluginConfig readValue(@JsonProperty("enabled") @Nullable Boolean enabled,
            @JsonProperty("properties") @Nullable Map<String, /*@Nullable*/Object> properties,
            @JsonProperty("version") @Nullable String version) throws JsonMappingException {
        checkRequiredProperty(enabled, "enabled");
        checkRequiredProperty(properties, "properties");
        checkRequiredProperty(version, "version");
        PluginConfig config = new PluginConfig(version);
        config.setEnabled(enabled);
        config.properties.putAll(properties);
        return config;
    }
}
