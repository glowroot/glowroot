/**
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.config;

import static io.informant.common.ObjectMappers.checkRequiredProperty;

import io.informant.common.ObjectMappers;

import java.io.IOException;
import java.util.List;

import checkers.igj.quals.Immutable;
import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class PluginDescriptor {

    private final String groupId;
    private final String artifactId;
    private final String version;
    private final String name;
    private final ImmutableList<PropertyDescriptor> properties;
    private final ImmutableList<String> aspects;

    public static PluginDescriptor.Builder builder(PluginDescriptor base) {
        return new Builder(base);
    }

    private PluginDescriptor(String groupId, String artifactId, String version, String name,
            @ReadOnly List<PropertyDescriptor> properties, @ReadOnly List<String> aspects) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.name = name;
        this.properties = ImmutableList.copyOf(properties);
        this.aspects = ImmutableList.copyOf(aspects);
    }

    public String getGroupId() {
        return groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public String getVersion() {
        return version;
    }

    public String getName() {
        return name;
    }

    public ImmutableList<PropertyDescriptor> getProperties() {
        return properties;
    }

    public ImmutableList<String> getAspects() {
        return aspects;
    }

    @JsonIgnore
    public String getId() {
        return groupId + ":" + artifactId;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("name", name)
                .add("groupId", groupId)
                .add("artifactId", artifactId)
                .add("version", version)
                .add("properties", properties)
                .add("aspects", aspects)
                .toString();
    }

    @JsonCreator
    static PluginDescriptor readValue(@JsonProperty("groupId") @Nullable String groupId,
            @JsonProperty("artifactId") @Nullable String artifactId,
            @JsonProperty("version") @Nullable String version,
            @JsonProperty("name") @Nullable String name,
            @JsonProperty("properties") @Nullable List<PropertyDescriptor> properties,
            @JsonProperty("aspects") @Nullable List<String> aspects) throws JsonMappingException {
        checkRequiredProperty(groupId, "groupId");
        checkRequiredProperty(artifactId, "artifactId");
        checkRequiredProperty(version, "version");
        checkRequiredProperty(name, "name");
        return new PluginDescriptor(groupId, artifactId, version, name, orEmpty(properties),
                orEmpty(aspects));
    }

    @ReadOnly
    private static <T> List<T> orEmpty(@ReadOnly @Nullable List<T> list) {
        if (list == null) {
            return ImmutableList.of();
        }
        return list;
    }

    // only used by packager-maven-plugin, placed in informant to avoid shading issues
    public static PluginDescriptor readValue(String content) throws JsonProcessingException,
            IOException {
        ObjectMapper mapper = ObjectMappers.create();
        return ObjectMappers.readRequiredValue(mapper, content, PluginDescriptor.class);
    }

    public static class Builder {

        private final String groupId;
        private final String artifactId;
        private final String version;
        private final String name;
        @ReadOnly
        private List<PropertyDescriptor> properties = ImmutableList.of();
        @ReadOnly
        private List<String> aspects = ImmutableList.of();

        private Builder(PluginDescriptor base) {
            groupId = base.groupId;
            artifactId = base.artifactId;
            version = base.version;
            name = base.name;
            properties = base.properties;
            aspects = base.aspects;
        }

        public Builder properties(List<PropertyDescriptor> properties) {
            this.properties = properties;
            return this;
        }

        public PluginDescriptor build() {
            return new PluginDescriptor(groupId, artifactId, version, name, properties, aspects);
        }
    }
}
