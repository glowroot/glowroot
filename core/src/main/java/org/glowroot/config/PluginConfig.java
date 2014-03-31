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
package org.glowroot.config;

import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Charsets;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.Optional;
import org.glowroot.config.JsonViews.UiView;
import org.glowroot.config.PropertyDescriptor.BooleanPropertyDescriptor;
import org.glowroot.config.PropertyDescriptor.DoublePropertyDescriptor;
import org.glowroot.config.PropertyDescriptor.PropertyType;
import org.glowroot.config.PropertyDescriptor.StringPropertyDescriptor;
import org.glowroot.markers.OnlyUsedByTests;

/**
 * Immutable structure to hold the current config for a plugin.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@JsonPropertyOrder({"id"})
@Immutable
public class PluginConfig {

    private static final Logger logger = LoggerFactory.getLogger(PluginConfig.class);

    private final PluginDescriptor pluginDescriptor;

    private final boolean enabled;

    // all defined properties (as defined in each plugin's glowroot.plugin.json file) are
    // included in the property map, even those properties with null value, so that an appropriate
    // error can be logged if a plugin tries to access a property value that it hasn't defined in
    // its plugin.json file
    private final ImmutableMap<String, String> stringProperties;
    private final ImmutableMap<String, Boolean> booleanProperties;
    private final ImmutableMap<String, Optional<Double>> doubleProperties;

    private final String version;

    public static Builder builder(PluginConfig base) {
        return new Builder(base);
    }

    static PluginConfig getDefault(PluginDescriptor pluginDescriptor) {
        return new Builder(pluginDescriptor).build();
    }

    private PluginConfig(PluginDescriptor pluginDescriptor, boolean enabled,
            ImmutableMap<String, String> stringProperties,
            ImmutableMap<String, Boolean> booleanProperties,
            ImmutableMap<String, Optional<Double>> doubleProperties, String version) {
        this.pluginDescriptor = pluginDescriptor;
        this.enabled = enabled;
        this.stringProperties = stringProperties;
        this.booleanProperties = booleanProperties;
        this.doubleProperties = doubleProperties;
        this.version = version;
    }

    // used by json serialization
    public String getId() {
        return pluginDescriptor.getId();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getStringProperty(String name) {
        String value = stringProperties.get(name);
        if (value == null) {
            logger.warn("unexpected string property name: {}", name);
            return "";
        }
        return value;
    }

    public boolean getBooleanProperty(String name) {
        Boolean value = booleanProperties.get(name);
        if (value == null) {
            logger.warn("unexpected boolean property name: {}", name);
            return false;
        }
        return value;
    }

    @Nullable
    public Double getDoubleProperty(String name) {
        Optional<Double> value = doubleProperties.get(name);
        if (value == null) {
            logger.warn("unexpected double property name: {}", name);
            return null;
        }
        return value.orNull();
    }

    // used by json serialization
    public Map<String, /*@Nullable*/Object> getProperties() {
        Map<String, /*@Nullable*/Object> properties = Maps.newHashMap();
        for (PropertyDescriptor propertyDescriptor : pluginDescriptor.getProperties()) {
            if (propertyDescriptor.isHidden()) {
                // don't want hidden fields to be written to config file
                // (and they aren't needed in ui either)
                continue;
            }
            String propertyName = propertyDescriptor.getName();
            PropertyType propertyType = propertyDescriptor.getType();
            switch (propertyType) {
                case STRING:
                    properties.put(propertyName, getStringProperty(propertyName));
                    break;
                case BOOLEAN:
                    properties.put(propertyName, getBooleanProperty(propertyName));
                    break;
                case DOUBLE:
                    properties.put(propertyName, getDoubleProperty(propertyName));
                    break;
                default:
                    throw new AssertionError("Unknown PropertyType enum: " + propertyType);
            }
        }
        return properties;
    }

    @JsonView(UiView.class)
    public String getVersion() {
        return version;
    }

    /*@Pure*/
    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("id", pluginDescriptor.getId())
                .add("enabled", enabled)
                .add("stringProperties", stringProperties)
                .add("booleanProperties", booleanProperties)
                .add("doubleProperties", doubleProperties)
                .add("version", version)
                .toString();
    }

    public static class Builder {

        private final PluginDescriptor pluginDescriptor;
        private boolean enabled = true;
        private final Map<String, String> stringProperties;
        private final Map<String, Boolean> booleanProperties;
        private final Map<String, Optional<Double>> doubleProperties;

        Builder(PluginDescriptor pluginDescriptor) {
            this.pluginDescriptor = pluginDescriptor;
            stringProperties = Maps.newHashMap();
            booleanProperties = Maps.newHashMap();
            doubleProperties = Maps.newHashMap();
            for (PropertyDescriptor property : pluginDescriptor.getProperties()) {
                String name = property.getName();
                if (property instanceof StringPropertyDescriptor) {
                    String defaultValue = ((StringPropertyDescriptor) property).getDefault();
                    stringProperties.put(name, defaultValue);
                } else if (property instanceof BooleanPropertyDescriptor) {
                    Boolean defaultValue = ((BooleanPropertyDescriptor) property).getDefault();
                    booleanProperties.put(name, defaultValue);
                } else if (property instanceof DoublePropertyDescriptor) {
                    Double defaultValue = ((DoublePropertyDescriptor) property).getDefault();
                    doubleProperties.put(name, Optional.fromNullable(defaultValue));
                } else {
                    logger.error("unexpected property descriptor type: {}", property.getClass());
                }
            }
        }

        private Builder(PluginConfig base) {
            this(base.pluginDescriptor);
            this.enabled = base.enabled;
            stringProperties.putAll(base.stringProperties);
            booleanProperties.putAll(base.booleanProperties);
            doubleProperties.putAll(base.doubleProperties);
        }

        @OnlyUsedByTests
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public void overlay(ObjectNode configNode) throws JsonMappingException {
            overlay(configNode, false);
        }

        public PluginConfig build() {
            String version = buildVersion();
            return new PluginConfig(pluginDescriptor, enabled,
                    ImmutableMap.copyOf(stringProperties), ImmutableMap.copyOf(booleanProperties),
                    ImmutableMap.copyOf(doubleProperties), version);
        }

        void overlay(ObjectNode configNode, boolean ignoreWarnings) throws JsonMappingException {
            JsonNode enabledElement = configNode.get("enabled");
            if (enabledElement != null) {
                enabled(enabledElement.asBoolean());
            }
            ObjectNode propertiesNode = (ObjectNode) configNode.get("properties");
            if (propertiesNode == null) {
                return;
            }
            for (Iterator<Entry<String, JsonNode>> i = propertiesNode.fields(); i.hasNext();) {
                Entry<String, JsonNode> entry = i.next();
                String name = entry.getKey();
                JsonNode value = entry.getValue();
                if (value.isNull()) {
                    setProperty(name, null, ignoreWarnings);
                } else if (value.isValueNode()) {
                    if (value.isBoolean()) {
                        setProperty(name, value.asBoolean(), ignoreWarnings);
                    } else if (value.isNumber()) {
                        // convert all numbers to double
                        setProperty(name, value.asDouble(), ignoreWarnings);
                    } else if (value.isTextual()) {
                        setProperty(name, value.asText(), ignoreWarnings);
                    } else {
                        throw new JsonMappingException("Unexpected json value: " + value);
                    }
                } else {
                    throw new JsonMappingException("Unexpected json node: " + value);
                }
            }
        }

        @OnlyUsedByTests
        public Builder setProperty(String name, @Nullable Object value) {
            return setProperty(name, value, false);
        }

        // ignoreExtraProperties option exists for instantiating plugin config from a stored json
        // value which may be out of sync if the plugin has been updated and the given property has
        // changed, e.g. from not hidden to hidden, in which case the associated error messages
        // should be suppressed
        private Builder setProperty(String name, @Nullable Object value, boolean ignoreWarnings) {
            PropertyDescriptor propertyDescriptor = getPropertyDescriptor(name);
            if (propertyDescriptor == null) {
                if (!ignoreWarnings) {
                    logger.warn("unexpected property name: {}", name);
                }
                return this;
            }
            if (propertyDescriptor.isHidden()) {
                if (!ignoreWarnings) {
                    logger.warn("cannot set hidden property {} with value: {} (hidden properties"
                            + " can only be set by via glowroot.plugin.json)", name, value);
                }
                return this;
            }
            PropertyType propertyType = propertyDescriptor.getType();
            switch (propertyType) {
                case STRING:
                    setStringProperty(name, value, ignoreWarnings);
                    return this;
                case BOOLEAN:
                    setBooleanProperty(name, value, ignoreWarnings);
                    return this;
                case DOUBLE:
                    setDoubleProperty(name, value, ignoreWarnings);
                    return this;
                default:
                    throw new AssertionError("Unknown PropertyType enum: " + propertyType);
            }
        }

        @Nullable
        private PropertyDescriptor getPropertyDescriptor(String name) {
            for (PropertyDescriptor propertyDescriptor : pluginDescriptor.getProperties()) {
                if (propertyDescriptor.getName().equals(name)) {
                    return propertyDescriptor;
                }
            }
            return null;
        }

        private void setStringProperty(String name, @Nullable Object value,
                boolean ignoreWarnings) {
            if (value == null) {
                if (!ignoreWarnings) {
                    logger.warn("string property types do not accept null values"
                            + " (use empty string instead)");
                }
            } else if (value instanceof String) {
                stringProperties.put(name, (String) value);
            } else if (!ignoreWarnings) {
                logger.warn("property {} has unexpected type: {}", name,
                        value.getClass().getName());
            }
        }

        private void setBooleanProperty(String name, @Nullable Object value,
                boolean ignoreWarnings) {
            if (value == null) {
                if (!ignoreWarnings) {
                    logger.warn("boolean property types do not accept null values");
                }
            } else if (value instanceof Boolean) {
                booleanProperties.put(name, (Boolean) value);
            } else if (!ignoreWarnings) {
                logger.warn("property {} has unexpected type: {}", name,
                        value.getClass().getName());
            }
        }

        private void setDoubleProperty(String name, @Nullable Object value,
                boolean ignoreWarnings) {
            if (value == null) {
                doubleProperties.put(name, Optional.absent(Double.class));
            } else if (value instanceof Double) {
                doubleProperties.put(name, Optional.of((Double) value));
            } else if (!ignoreWarnings) {
                logger.warn("property {} has unexpected type: {}", name,
                        value.getClass().getName());
            }
        }

        private String buildVersion() {
            Hasher hasher = Hashing.sha1().newHasher();
            hasher.putBoolean(enabled);
            // ? extends String needed for checker framework, see issue #311
            for (Map.Entry<? extends String, String> property : stringProperties.entrySet()) {
                String name = property.getKey();
                String value = property.getValue();
                hasher.putString(name, Charsets.UTF_8);
                hasher.putInt(name.length());
                hasher.putString(value, Charsets.UTF_8);
                hasher.putInt(value.length());
            }
            // ? extends String needed for checker framework, see issue #311
            for (Map.Entry<? extends String, Boolean> property : booleanProperties.entrySet()) {
                String name = property.getKey();
                Boolean value = property.getValue();
                hasher.putString(name, Charsets.UTF_8);
                hasher.putInt(name.length());
                hasher.putBoolean(value);
            }
            // ? extends String needed for checker framework, see issue #311
            for (Map.Entry<? extends String, Optional<Double>> property : doubleProperties
                    .entrySet()) {
                String name = property.getKey();
                Double value = property.getValue().orNull();
                hasher.putString(name, Charsets.UTF_8);
                hasher.putInt(name.length());
                if (value != null) {
                    hasher.putDouble(value);
                }
            }
            return hasher.hash().toString();
        }
    }
}
