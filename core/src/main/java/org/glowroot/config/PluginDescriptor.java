/*
 * Copyright 2012-2014 the original author or authors.
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
package org.glowroot.config;

import java.io.IOException;
import java.util.List;

import checkers.igj.quals.Immutable;
import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import dataflow.quals.Pure;

import org.glowroot.common.ObjectMappers;
import org.glowroot.markers.UsedByJsonBinding;

import static org.glowroot.common.ObjectMappers.checkRequiredProperty;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
@UsedByJsonBinding
public class PluginDescriptor {

    private static final ObjectMapper mapper = ObjectMappers.create();

    private final String name;
    private final String id;
    private final String version;
    private final ImmutableList<String> traceAttributes;
    private final ImmutableList<PropertyDescriptor> properties;
    private final ImmutableList<String> aspects;
    private final ImmutableList<PointcutConfig> pointcuts;

    public static PluginDescriptor.Builder builder(PluginDescriptor base) {
        return new Builder(base);
    }

    private PluginDescriptor(String name, String id, String version,
            @ReadOnly List<String> traceAttributes, @ReadOnly List<PropertyDescriptor> properties,
            @ReadOnly List<String> aspects, @ReadOnly List<PointcutConfig> pointcuts) {
        this.name = name;
        this.id = id;
        this.version = version;
        this.traceAttributes = ImmutableList.copyOf(traceAttributes);
        this.properties = ImmutableList.copyOf(properties);
        this.aspects = ImmutableList.copyOf(aspects);
        this.pointcuts = ImmutableList.copyOf(pointcuts);
    }

    public String getName() {
        return name;
    }

    public String getId() {
        return id;
    }

    public String getVersion() {
        return version;
    }

    public ImmutableList<String> getTraceAttributes() {
        return traceAttributes;
    }

    public ImmutableList<PropertyDescriptor> getProperties() {
        return properties;
    }

    public ImmutableList<String> getAspects() {
        return aspects;
    }

    public ImmutableList<PointcutConfig> getPointcuts() {
        return pointcuts;
    }

    public PluginDescriptor copyWithoutAdvice() {
        return new PluginDescriptor(name, id, version, traceAttributes, properties,
                ImmutableList.<String>of(), ImmutableList.<PointcutConfig>of());
    }

    @Override
    @Pure
    public String toString() {
        return Objects.toStringHelper(this)
                .add("name", name)
                .add("id", id)
                .add("version", version)
                .add("traceAttributes", traceAttributes)
                .add("properties", properties)
                .add("aspects", aspects)
                .add("pointcuts", pointcuts)
                .toString();
    }

    @JsonCreator
    static PluginDescriptor readValue(@JsonProperty("name") @Nullable String name,
            @JsonProperty("id") @Nullable String id,
            @JsonProperty("version") @Nullable String version,
            @JsonProperty("traceAttributes") @Nullable List<String> traceAttributes,
            @JsonProperty("properties") @Nullable List<PropertyDescriptor> properties,
            @JsonProperty("aspects") @Nullable List<String> aspects,
            @JsonProperty("pointcuts") @Nullable List<PointcutConfig> pointcuts)
            throws JsonMappingException {
        checkRequiredProperty(name, "name");
        checkRequiredProperty(id, "id");
        checkRequiredProperty(version, "version");
        return new PluginDescriptor(name, id, version, orEmpty(traceAttributes),
                orEmpty(properties), orEmpty(aspects), orEmpty(pointcuts));
    }

    @ReadOnly
    private static <T extends /*@NonNull*/Object> List<T> orEmpty(
            @ReadOnly @Nullable List<T> list) {
        if (list == null) {
            return ImmutableList.of();
        }
        return list;
    }

    // only used by packager-maven-plugin, placed in glowroot to avoid shading issues
    public static PluginDescriptor readValue(String content) throws IOException {
        return ObjectMappers.readRequiredValue(mapper, content, PluginDescriptor.class);
    }

    public static class Builder {

        private final String name;
        private final String id;
        private final String version;
        @ReadOnly
        private List<String> traceAttributes = ImmutableList.of();
        @ReadOnly
        private List<PropertyDescriptor> properties = ImmutableList.of();
        @ReadOnly
        private List<String> aspects = ImmutableList.of();
        @ReadOnly
        private List<PointcutConfig> pointcuts = ImmutableList.of();

        private Builder(PluginDescriptor base) {
            name = base.name;
            id = base.id;
            version = base.version;
            traceAttributes = base.traceAttributes;
            properties = base.properties;
            aspects = base.aspects;
            pointcuts = base.pointcuts;
        }

        public Builder properties(List<PropertyDescriptor> properties) {
            this.properties = properties;
            return this;
        }

        public PluginDescriptor build() {
            return new PluginDescriptor(name, id, version, traceAttributes, properties, aspects,
                    pointcuts);
        }
    }
}
