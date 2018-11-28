/*
 * Copyright 2011-2018 the original author or authors.
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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.immutables.value.Value;

import org.glowroot.common.config.PropertyValue;
import org.glowroot.common.config.PropertyValue.PropertyType;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginProperty;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginProperty.Value.ValCase;

import static com.google.common.base.Preconditions.checkNotNull;

@Value.Immutable
public abstract class PluginConfig {

    @JsonIgnore
    public abstract PluginDescriptor pluginDescriptor();

    @Value.Derived
    public String id() {
        return pluginDescriptor().id();
    }

    public abstract Map<String, PropertyValue> properties();

    @Value.Derived
    @JsonIgnore
    ImmutableMap<String, Boolean> booleanProperties() {
        Map<String, Boolean> booleanProperties = Maps.newHashMap();
        for (Map.Entry<String, PropertyValue> entry : properties().entrySet()) {
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
        for (Map.Entry<String, PropertyValue> entry : properties().entrySet()) {
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
        for (Map.Entry<String, PropertyValue> entry : properties().entrySet()) {
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
    ImmutableMap<String, List<String>> listProperties() {
        Map<String, List<String>> listProperties = Maps.newHashMap();
        for (Map.Entry<String, PropertyValue> entry : properties().entrySet()) {
            PropertyValue propertyValue = entry.getValue();
            Object value = propertyValue.value();
            if (value instanceof List<?>) {
                List<String> list = Lists.newArrayList();
                for (Object v : (List<?>) value) {
                    list.add((String) checkNotNull(v));
                }
                listProperties.put(entry.getKey(), list);
            }
        }
        return ImmutableMap.copyOf(listProperties);
    }

    public String getStringProperty(String name) {
        String value = stringProperties().get(name);
        return value == null ? "" : value;
    }

    public boolean getBooleanProperty(String name) {
        Boolean value = booleanProperties().get(name);
        return value != null && value;
    }

    public @Nullable Double getDoubleProperty(String name) {
        Optional<Double> value = doubleProperties().get(name);
        return value == null ? null : value.orNull();
    }

    public List<String> getListProperty(String name) {
        List<String> value = listProperties().get(name);
        return value == null ? ImmutableList.<String>of() : value;
    }

    public AgentConfig.PluginConfig toProto() {
        AgentConfig.PluginConfig.Builder builder = AgentConfig.PluginConfig.newBuilder()
                .setId(id())
                .setName(pluginDescriptor().name());
        for (Map.Entry<String, PropertyValue> entry : properties().entrySet()) {
            PropertyDescriptor propertyDescriptor = getPropertyDescriptor(entry.getKey());
            PluginProperty.Builder property = PluginProperty.newBuilder()
                    .setName(entry.getKey())
                    .setValue(entry.getValue().toProto())
                    .setDefault(propertyDescriptor.getValidatedNonNullDefaultValue().toProto())
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
            List<PluginProperty> newProperties) {
        ImmutablePluginConfig.Builder builder = ImmutablePluginConfig.builder()
                .pluginDescriptor(pluginDescriptor);
        Map<String, PluginProperty> remainingNewProperties = Maps.newHashMap();
        for (PluginProperty newProperty : newProperties) {
            remainingNewProperties.put(newProperty.getName(), newProperty);
        }
        Map<String, PropertyValue> propertyValues = Maps.newLinkedHashMap();
        for (PropertyDescriptor propertyDescriptor : pluginDescriptor.properties()) {
            PluginProperty newProperty = remainingNewProperties.remove(propertyDescriptor.name());
            if (newProperty == null) {
                propertyValues.put(propertyDescriptor.name(),
                        propertyDescriptor.getValidatedNonNullDefaultValue());
            } else if (!isValidType(newProperty.getValue().getValCase(),
                    propertyDescriptor.type())) {
                throw new IllegalStateException("Plugin property " + newProperty.getName()
                        + " has incorrect type: " + newProperty.getValue().getValCase());
            } else {
                propertyValues.put(newProperty.getName(),
                        PropertyValue.create(newProperty.getValue()));
            }
        }
        if (remainingNewProperties.isEmpty()) {
            return builder.properties(propertyValues)
                    .build();
        } else {
            throw new IllegalStateException("Plugin properties not found: "
                    + Joiner.on(", ").join(remainingNewProperties.keySet()));
        }
    }

    private static boolean isValidType(PluginProperty.Value.ValCase valueType,
            PropertyType targetType) {
        switch (targetType) {
            case BOOLEAN:
                return valueType == ValCase.BVAL;
            case STRING:
                return valueType == ValCase.SVAL;
            case DOUBLE:
                return valueType == ValCase.DVAL || valueType == ValCase.DVAL_NULL;
            case LIST:
                return valueType == ValCase.LVAL;
            default:
                throw new AssertionError("Unexpected property type: " + targetType);
        }
    }
}
