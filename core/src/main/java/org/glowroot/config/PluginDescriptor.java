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

import java.util.List;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;

import org.glowroot.markers.UsedByJsonBinding;

import static org.glowroot.common.ObjectMappers.checkRequiredProperty;
import static org.glowroot.common.ObjectMappers.nullToEmpty;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
@UsedByJsonBinding
public class PluginDescriptor {

    private final String name;
    private final String id;
    private final String version;
    private final ImmutableList<String> traceAttributes;
    private final ImmutableList<PropertyDescriptor> properties;
    private final ImmutableList<String> aspects;
    private final ImmutableList<PointcutConfig> pointcuts;

    private PluginDescriptor(String name, String id, String version, List<String> traceAttributes,
            List<PropertyDescriptor> properties, List<String> aspects,
            List<PointcutConfig> pointcuts) {
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

    /*@Pure*/
    @Override
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
        return new PluginDescriptor(name, id, version, nullToEmpty(traceAttributes),
                nullToEmpty(properties), nullToEmpty(aspects), nullToEmpty(pointcuts));
    }
}
