/*
 * Copyright 2011-2015 the original author or authors.
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

import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Value.Immutable
public abstract class PluginConfigBase {

    private static final Logger logger = LoggerFactory.getLogger(PluginConfig.class);

    public abstract String id();
    @Value.Default
    public boolean enabled() {
        return true;
    }
    // when written to config.json, this will have all plugin properties
    // so not using @Json.ForceEmpty since new plugin properties can't be added in config.json
    // anyways
    public abstract Map<String, PropertyValue> properties();

    @Value.Derived
    @JsonIgnore
    ImmutableMap<String, Boolean> booleanProperties() {
        Map<String, Boolean> booleanProperties = Maps.newHashMap();
        for (Entry<String, PropertyValue> entry : properties().entrySet()) {
            PropertyValue propertyValue = entry.getValue();
            Object value = propertyValue.value();
            if (value instanceof Boolean) {
                booleanProperties.put(entry.getKey(), (Boolean) value);
            }
        }
        return ImmutableMap.copyOf(booleanProperties);
    }

    @Value.Derived
    @JsonIgnore
    ImmutableMap<String, String> stringProperties() {
        Map<String, String> stringProperties = Maps.newHashMap();
        for (Entry<String, PropertyValue> entry : properties().entrySet()) {
            PropertyValue propertyValue = entry.getValue();
            Object value = propertyValue.value();
            if (value instanceof String) {
                stringProperties.put(entry.getKey(), (String) value);
            }
        }
        return ImmutableMap.copyOf(stringProperties);
    }

    @Value.Derived
    @JsonIgnore
    ImmutableMap<String, Optional<Double>> doubleProperties() {
        Map<String, Optional<Double>> doubleProperties = Maps.newHashMap();
        for (Entry<String, PropertyValue> entry : properties().entrySet()) {
            PropertyValue propertyValue = entry.getValue();
            Object value = propertyValue.value();
            if (value == null) {
                doubleProperties.put(entry.getKey(), Optional.<Double>absent());
            } else if (value instanceof Double) {
                doubleProperties.put(entry.getKey(), Optional.of((Double) value));
            }
        }
        return ImmutableMap.copyOf(doubleProperties);
    }

    @Value.Derived
    @JsonIgnore
    public String version() {
        return Versions.getVersion(this);
    }

    public String getStringProperty(String name) {
        String value = stringProperties().get(name);
        return value == null ? "" : value;
    }

    public boolean getBooleanProperty(String name) {
        Boolean value = booleanProperties().get(name);
        return value == null ? false : value;
    }

    public @Nullable Double getDoubleProperty(String name) {
        Optional<Double> value = doubleProperties().get(name);
        return value == null ? null : value.orNull();
    }

    @Nullable
    PropertyValue getValidatedPropertyValue(String propertyName, PropertyType type) {
        PropertyValue propertyValue = properties().get(propertyName);
        if (propertyValue == null) {
            return null;
        }
        Object value = propertyValue.value();
        if (value == null) {
            return PropertyValue.getDefaultValue(type);
        }
        if (PropertyDescriptor.isValidType(value, type)) {
            return propertyValue;
        } else {
            logger.warn("invalid value for plugin property: {}", propertyName);
            return PropertyValue.getDefaultValue(type);
        }
    }
}
