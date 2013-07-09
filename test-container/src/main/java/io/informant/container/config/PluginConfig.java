/*
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
package io.informant.container.config;

import java.util.Map;

import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.Objects;
import com.google.common.collect.Maps;

import static io.informant.container.common.ObjectMappers.checkRequiredProperty;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class PluginConfig {

    private final String groupId;
    private final String artifactId;

    private boolean enabled;
    private final Map<String, /*@Nullable*/Object> properties;

    private final String version;

    public PluginConfig(String groupId, String artifactId, String version) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        properties = Maps.newHashMap();
        this.version = version;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getArtifactId() {
        return artifactId;
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
            return Objects.equal(groupId, that.groupId)
                    && Objects.equal(artifactId, that.artifactId)
                    && Objects.equal(enabled, that.enabled)
                    && Objects.equal(properties, that.properties);
        }
        return false;
    }

    @Override
    public int hashCode() {
        // intentionally leaving off version since it represents the prior version hash when
        // sending to the server, and represents the current version hash when receiving from the
        // server
        return Objects.hashCode(groupId, artifactId, enabled, properties);
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("groupId", groupId)
                .add("artifactId", artifactId)
                .add("enabled", enabled)
                .add("properties", properties)
                .add("version", version)
                .toString();
    }

    @JsonCreator
    static PluginConfig readValue(@JsonProperty("groupId") @Nullable String groupId,
            @JsonProperty("artifactId") @Nullable String artifactId,
            @JsonProperty("enabled") @Nullable Boolean enabled,
            @JsonProperty("properties") @Nullable Map<String, /*@Nullable*/Object> properties,
            @JsonProperty("version") @Nullable String version) throws JsonMappingException {
        checkRequiredProperty(groupId, "groupId");
        checkRequiredProperty(artifactId, "artifactId");
        checkRequiredProperty(enabled, "enabled");
        checkRequiredProperty(properties, "properties");
        checkRequiredProperty(version, "version");
        PluginConfig config = new PluginConfig(groupId, artifactId, version);
        config.setEnabled(enabled);
        config.properties.putAll(properties);
        return config;
    }
}
