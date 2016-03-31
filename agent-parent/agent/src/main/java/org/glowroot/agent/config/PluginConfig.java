/*
 * Copyright 2011-2016 the original author or authors.
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
package org.glowroot.agent.config;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import org.immutables.value.Value;

import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginProperty;

@Value.Immutable
public abstract class PluginConfig {

    @JsonIgnore
    public abstract PluginDescriptor pluginDescriptor();

    @Value.Derived
    public String id() {
        return pluginDescriptor().id();
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

    public AgentConfig.PluginConfig toProto() {
        AgentConfig.PluginConfig.Builder builder = AgentConfig.PluginConfig.newBuilder()
                .setId(id())
                .setName(pluginDescriptor().name());
        for (Entry<String, PropertyValue> entry : properties().entrySet()) {
            PropertyDescriptor propertyDescriptor = getPropertyDescriptor(entry.getKey());
            PluginProperty.Builder property = PluginProperty.newBuilder()
                    .setName(entry.getKey())
                    .setValue(getPropertyValue(entry.getValue().value()))
                    .setDefault(getPropertyValue(
                            propertyDescriptor.getValidatedNonNullDefaultValue().value()))
                    .setLabel(propertyDescriptor.label())
                    .setCheckboxLabel(propertyDescriptor.checkboxLabel())
                    .setDescription(propertyDescriptor.description());
            builder.addProperty(property);
        }
        return builder.build();
    }

    private PropertyDescriptor getPropertyDescriptor(String name) {
        for (PropertyDescriptor propertyDescriptor : pluginDescriptor().properties()) {
            if (propertyDescriptor.name().equals(name)) {
                return propertyDescriptor;
            }
        }
        throw new IllegalStateException("Could not find property descriptor: " + name);
    }

    public static PluginConfig create(PluginDescriptor pluginDescriptor,
            List<PluginProperty> props) {
        ImmutablePluginConfig.Builder builder = ImmutablePluginConfig.builder()
                .pluginDescriptor(pluginDescriptor);
        Map<String, PropertyValue> properties = Maps.newLinkedHashMap();
        for (PropertyDescriptor propertyDescriptor : pluginDescriptor.properties()) {
            properties.put(propertyDescriptor.name(),
                    propertyDescriptor.getValidatedNonNullDefaultValue());
        }
        for (PluginProperty prop : props) {
            PluginProperty.Value propertyValue = prop.getValue();
            switch (propertyValue.getValCase()) {
                case BVAL:
                    properties.put(prop.getName(), new PropertyValue(propertyValue.getBval()));
                    break;
                case DVAL_NULL:
                    properties.put(prop.getName(), new PropertyValue(null));
                    break;
                case DVAL:
                    properties.put(prop.getName(), new PropertyValue(propertyValue.getDval()));
                    break;
                case SVAL:
                    properties.put(prop.getName(), new PropertyValue(propertyValue.getSval()));
                    break;
                default:
                    throw new IllegalStateException(
                            "Unexpected plugin property type: " + propertyValue.getValCase());
            }
        }
        return builder.properties(properties)
                .build();
    }

    private static PluginProperty.Value getPropertyValue(@Nullable Object value)
            throws AssertionError {
        PluginProperty.Value.Builder propertyValue = PluginProperty.Value.newBuilder();
        if (value == null) {
            propertyValue.setDvalNull(true);
        } else if (value instanceof Boolean) {
            propertyValue.setBval((Boolean) value);
        } else if (value instanceof String) {
            propertyValue.setSval((String) value);
        } else if (value instanceof Double) {
            propertyValue.setDval((Double) value);
        } else {
            throw new AssertionError(
                    "Unexpected property value type: " + value.getClass().getName());
        }
        return propertyValue.build();
    }
}
