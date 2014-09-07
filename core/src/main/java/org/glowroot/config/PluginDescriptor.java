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
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;
import org.checkerframework.checker.nullness.qual.Nullable;

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
    private final ImmutableList<String> transactionCustomAttributes;
    private final ImmutableList<PropertyDescriptor> properties;
    private final ImmutableList<CapturePoint> capturePoints;
    private final ImmutableList<String> aspects;

    private PluginDescriptor(String name, String id, String version,
            List<String> transactionTypes, List<String> transactionCustomAttributes,
            List<PropertyDescriptor> properties, List<CapturePoint> pointcuts,
            List<String> aspects) {
        this.name = name;
        this.id = id;
        this.version = version;
        this.transactionTypes = ImmutableList.copyOf(transactionTypes);
        this.transactionCustomAttributes = ImmutableList.copyOf(transactionCustomAttributes);
        this.properties = ImmutableList.copyOf(properties);
        this.capturePoints = ImmutableList.copyOf(pointcuts);
        this.aspects = ImmutableList.copyOf(aspects);
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

    public ImmutableList<String> getTransactionCustomAttributes() {
        return transactionCustomAttributes;
    }

    public ImmutableList<PropertyDescriptor> getProperties() {
        return properties;
    }

    public ImmutableList<CapturePoint> getCapturePoints() {
        return capturePoints;
    }

    public ImmutableList<String> getAspects() {
        return aspects;
    }

    public PluginDescriptor copyWithoutAdvice() {
        return new PluginDescriptor(name, id, version, transactionTypes,
                transactionCustomAttributes, properties, ImmutableList.<CapturePoint>of(),
                ImmutableList.<String>of());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("name", name)
                .add("id", id)
                .add("version", version)
                .add("transactionTypes", transactionTypes)
                .add("transactionCustomAttributes", transactionCustomAttributes)
                .add("properties", properties)
                .add("capturePoints", capturePoints)
                .add("aspects", aspects)
                .toString();
    }

    @JsonCreator
    static PluginDescriptor readValue(
            @JsonProperty("name") @Nullable String name,
            @JsonProperty("id") @Nullable String id,
            @JsonProperty("version") @Nullable String version,
            @JsonProperty("transactionTypes") @Nullable List</*@Nullable*/String> uncheckedTransactionTypes,
            @JsonProperty("transactionCustomAttributes") @Nullable List</*@Nullable*/String> uncheckedTransactionCustomAttributes,
            @JsonProperty("properties") @Nullable List</*@Nullable*/PropertyDescriptor> uncheckedProperties,
            @JsonProperty("capturePoints") @Nullable List</*@Nullable*/CapturePoint> uncheckedCapturePoints,
            @JsonProperty("aspects") @Nullable List</*@Nullable*/String> uncheckedAspects)
            throws JsonMappingException {
        List<String> transactionCustomAttributes = checkNotNullItemsForProperty(
                uncheckedTransactionCustomAttributes, "transactionCustomAttributes");
        List<String> transactionTypes =
                checkNotNullItemsForProperty(uncheckedTransactionTypes, "transactionTypes");
        List<PropertyDescriptor> properties =
                checkNotNullItemsForProperty(uncheckedProperties, "properties");
        List<CapturePoint> capturePoints =
                checkNotNullItemsForProperty(uncheckedCapturePoints, "capturePoints");
        List<String> aspects =
                checkNotNullItemsForProperty(uncheckedAspects, "aspects");
        checkRequiredProperty(name, "name");
        checkRequiredProperty(id, "id");
        checkRequiredProperty(version, "version");
        return new PluginDescriptor(name, id, version, nullToEmpty(transactionTypes),
                nullToEmpty(transactionCustomAttributes), nullToEmpty(properties),
                nullToEmpty(capturePoints), nullToEmpty(aspects));
    }

    private static String stripEndingIgnoreCase(String original, String ending) {
        if (original.toUpperCase(Locale.ENGLISH).endsWith(ending.toUpperCase(Locale.ENGLISH))) {
            return original.substring(0, original.length() - ending.length());
        } else {
            return original;
        }
    }
}
