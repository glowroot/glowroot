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
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;

import org.glowroot.markers.Immutable;
import org.glowroot.markers.UsedByJsonBinding;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.glowroot.common.ObjectMappers.checkNotNullItemsForProperty;
import static org.glowroot.common.ObjectMappers.checkRequiredProperty;
import static org.glowroot.common.ObjectMappers.nullToEmpty;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
@UsedByJsonBinding
public class PluginDescriptor {

    static final Ordering<PluginDescriptor> specialOrderingByName =
            new Ordering<PluginDescriptor>() {
                @Override
                public int compare(@Nullable PluginDescriptor left,
                        @Nullable PluginDescriptor right) {
                    checkNotNull(left);
                    checkNotNull(right);
                    // conventionally plugin names ends with " Plugin", so strip this off when
                    // comparing names so that, e.g., "Abc Plugin" will come before
                    // "Abc Extra Plugin"
                    String leftName = stripEndingIgnoreCase(left.name, " Plugin");
                    String rightName = stripEndingIgnoreCase(right.name, " Plugin");
                    return leftName.compareToIgnoreCase(rightName);
                }
            };

    private final String name;
    private final String id;
    private final String version;
    private final ImmutableList<String> transactionTypes;
    private final ImmutableList<String> traceAttributes;
    private final ImmutableList<PropertyDescriptor> properties;
    private final ImmutableList<String> aspects;
    private final ImmutableList<PointcutConfig> pointcuts;

    private PluginDescriptor(String name, String id, String version,
            List<String> transactionTypes, List<String> traceAttributes,
            List<PropertyDescriptor> properties, List<String> aspects,
            List<PointcutConfig> pointcuts) {
        this.name = name;
        this.id = id;
        this.version = version;
        this.transactionTypes = ImmutableList.copyOf(transactionTypes);
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

    public ImmutableList<String> getTransactionTypes() {
        return transactionTypes;
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
        return new PluginDescriptor(name, id, version, transactionTypes, traceAttributes,
                properties, ImmutableList.<String>of(), ImmutableList.<PointcutConfig>of());
    }

    @Override
    @Pure
    public String toString() {
        return Objects.toStringHelper(this)
                .add("name", name)
                .add("id", id)
                .add("version", version)
                .add("transactionTypes", transactionTypes)
                .add("traceAttributes", traceAttributes)
                .add("properties", properties)
                .add("aspects", aspects)
                .add("pointcuts", pointcuts)
                .toString();
    }

    @JsonCreator
    static PluginDescriptor readValue(
            @JsonProperty("name") @Nullable String name,
            @JsonProperty("id") @Nullable String id,
            @JsonProperty("version") @Nullable String version,
            @JsonProperty("transactionTypes") @Nullable List</*@Nullable*/String> uncheckedTransactionTypes,
            @JsonProperty("traceAttributes") @Nullable List</*@Nullable*/String> uncheckedTraceAttributes,
            @JsonProperty("properties") @Nullable List</*@Nullable*/PropertyDescriptor> uncheckedProperties,
            @JsonProperty("aspects") @Nullable List</*@Nullable*/String> uncheckedAspects,
            @JsonProperty("pointcuts") @Nullable List</*@Nullable*/PointcutConfig> uncheckedPointcuts)
            throws JsonMappingException {
        List<String> traceAttributes =
                checkNotNullItemsForProperty(uncheckedTraceAttributes, "traceAttributes");
        List<String> transactionTypes =
                checkNotNullItemsForProperty(uncheckedTransactionTypes, "transactionTypes");
        List<PropertyDescriptor> properties =
                checkNotNullItemsForProperty(uncheckedProperties, "properties");
        List<String> aspects =
                checkNotNullItemsForProperty(uncheckedAspects, "aspects");
        List<PointcutConfig> pointcuts =
                checkNotNullItemsForProperty(uncheckedPointcuts, "pointcuts");
        checkRequiredProperty(name, "name");
        checkRequiredProperty(id, "id");
        checkRequiredProperty(version, "version");
        return new PluginDescriptor(name, id, version, nullToEmpty(transactionTypes),
                nullToEmpty(traceAttributes), nullToEmpty(properties), nullToEmpty(aspects),
                nullToEmpty(pointcuts));
    }

    private static String stripEndingIgnoreCase(String original, String ending) {
        if (original.toUpperCase(Locale.ENGLISH).endsWith(ending.toUpperCase(Locale.ENGLISH))) {
            return original.substring(0, original.length() - ending.length());
        } else {
            return original;
        }
    }
}
