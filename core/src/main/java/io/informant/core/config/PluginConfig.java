/**
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
package io.informant.core.config;

import io.informant.api.Logger;
import io.informant.api.LoggerFactory;

import java.util.Map;
import java.util.Map.Entry;

import checkers.igj.quals.Immutable;
import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;

import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Immutable structure to hold the current config for a plugin.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class PluginConfig {

    private static final Logger logger = LoggerFactory.getLogger(PluginConfig.class);

    private final boolean enabled;

    // all defined properties (as defined in each plugin's io.informant.plugin.json file) are
    // included in the property map, even those properties with null value, so that an appropriate
    // error can be logged if a plugin tries to access a property value that it hasn't defined in
    // its plugin.json file
    private final ImmutableMap<String, Optional<?>> properties;

    private final PluginInfo pluginInfo;

    public static Builder builder(PluginConfig base) {
        return new Builder(base);
    }

    static PluginConfig fromJson(@ReadOnly JsonObject configObject, PluginInfo pluginInfo) {
        PluginConfig.Builder builder = new Builder(pluginInfo);
        builder.overlay(configObject, true);
        return builder.build();
    }

    private PluginConfig(boolean enabled, ImmutableMap<String, Optional<?>> properties,
            PluginInfo pluginInfo) {
        this.enabled = enabled;
        this.properties = properties;
        this.pluginInfo = pluginInfo;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getStringProperty(String name) {
        Optional<?> optional = properties.get(name);
        if (optional == null) {
            logger.error("unexpected property name '{}'", name);
            return "";
        }
        Object value = optional.orNull();
        if (value == null) {
            return "";
        } else if (value instanceof String) {
            return (String) value;
        } else {
            logger.error("expecting string value type, but found value type '"
                    + value.getClass() + "' for property name '" + name + "'");
            return "";
        }
    }

    public boolean getBooleanProperty(String name) {
        Optional<?> optional = properties.get(name);
        if (optional == null) {
            logger.error("unexpected property name '{}'", name);
            return false;
        }
        Object value = optional.orNull();
        if (value == null) {
            return false;
        } else if (value instanceof Boolean) {
            return (Boolean) value;
        } else {
            logger.error("expecting boolean value type, but found value type '"
                    + value.getClass() + "' for property name '" + name + "'");
            return false;
        }
    }

    @Nullable
    public Double getDoubleProperty(String name) {
        Optional<?> optional = properties.get(name);
        if (optional == null) {
            logger.error("unexpected property name '{}'", name);
            return null;
        }
        Object value = optional.orNull();
        if (value == null) {
            return null;
        } else if (value instanceof Double) {
            return (Double) value;
        } else {
            logger.error("expecting double value type, but found value type '" + value.getClass()
                    + "' for property name '" + name + "'");
            return null;
        }
    }

    public JsonObject toJson() {
        JsonObject configObject = new JsonObject();
        configObject.addProperty("groupId", pluginInfo.getGroupId());
        configObject.addProperty("artifactId", pluginInfo.getArtifactId());
        configObject.addProperty("enabled", enabled);
        JsonObject propertyMapObject = new JsonObject();
        for (PropertyDescriptor property : pluginInfo.getPropertyDescriptors()) {
            if (property.isHidden()) {
                continue;
            }
            switch (property.getType()) {
            case STRING:
                propertyMapObject.addProperty(property.getName(),
                        getStringProperty(property.getName()));
                break;
            case BOOLEAN:
                propertyMapObject.addProperty(property.getName(),
                        getBooleanProperty(property.getName()));
                break;
            case DOUBLE:
                propertyMapObject.addProperty(property.getName(),
                        getDoubleProperty(property.getName()));
                break;
            }
        }
        configObject.add("properties", propertyMapObject);
        return configObject;
    }

    String getId() {
        return pluginInfo.getGroupId() + ":" + pluginInfo.getArtifactId();
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("id", getId())
                .add("enabled", enabled)
                .add("properties", properties)
                .toString();
    }

    public static class Builder {

        private boolean enabled = true;
        private final PluginInfo pluginInfo;
        private final Map<String, Optional<?>> properties;

        private Builder(PluginInfo pluginInfo) {
            this.pluginInfo = pluginInfo;
            properties = Maps.newHashMap();
            for (PropertyDescriptor property : pluginInfo.getPropertyDescriptors()) {
                properties.put(property.getName(), Optional.fromNullable(property.getDefault()));
            }
        }
        private Builder(PluginConfig base) {
            this(base.pluginInfo);
            this.enabled = base.enabled;
            properties.putAll(base.properties);
        }
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }
        public void overlay(@ReadOnly JsonObject configObject) {
            overlay(configObject, false);
        }
        public PluginConfig build() {
            return new PluginConfig(enabled, ImmutableMap.copyOf(properties), pluginInfo);
        }
        private void overlay(@ReadOnly JsonObject configObject, boolean ignoreWarnings) {
            JsonElement enabledElement = configObject.get("enabled");
            if (enabledElement != null) {
                enabled(enabledElement.getAsBoolean());
            }
            JsonObject propertyMapObject = (JsonObject) Objects.firstNonNull(
                    configObject.get("properties"), new JsonObject());
            for (Entry<String, JsonElement> property : propertyMapObject.entrySet()) {
                String name = property.getKey();
                JsonElement value = property.getValue();
                if (value.isJsonNull()) {
                    setProperty(name, null, ignoreWarnings);
                } else if (value.isJsonPrimitive()) {
                    JsonPrimitive primitive = value.getAsJsonPrimitive();
                    if (primitive.isBoolean()) {
                        setProperty(name, primitive.getAsBoolean(), ignoreWarnings);
                    } else if (primitive.isNumber()) {
                        // convert all numbers to double
                        setProperty(name, primitive.getAsDouble(), ignoreWarnings);
                    } else if (primitive.isString()) {
                        setProperty(name, primitive.getAsString(), ignoreWarnings);
                    } else {
                        throw new IllegalStateException("Unexpected json primitive '" + primitive
                                + "'");
                    }
                } else {
                    throw new IllegalStateException("Unexpected json element type '"
                            + value.getClass().getName() + "'");
                }
            }
        }
        // ignoreExtraProperties option exists for instantiating plugin config from a stored json
        // value which may be out of sync if the plugin has been updated and the given property has
        // changed, e.g. from not hidden to hidden, in which case the associated error messages
        // should be suppressed
        private Builder setProperty(String name, @Immutable @Nullable Object value,
                boolean ignoreWarnings) {
            PropertyDescriptor propertyDescriptor = getPropertyDescriptor(name);
            if (propertyDescriptor == null) {
                if (!ignoreWarnings) {
                    logger.warn("unexpected property name '{}'", name);
                }
                return this;
            }
            if (propertyDescriptor.isHidden()) {
                if (!ignoreWarnings) {
                    logger.warn("cannot set hidden property '{}' with value '{}', hidden"
                            + " properties can only be set by via io.informant.plugin.json or"
                            + " io.informant.package.json", name, value);
                }
                return this;
            }
            switch (propertyDescriptor.getType()) {
            case STRING:
                setStringProperty(name, value, ignoreWarnings);
                return this;
            case BOOLEAN:
                setBooleanProperty(name, value, ignoreWarnings);
                return this;
            case DOUBLE:
                setDoubleProperty(name, value, ignoreWarnings);
                return this;
            }
            return this;
        }
        @Nullable
        private PropertyDescriptor getPropertyDescriptor(String name) {
            for (PropertyDescriptor propertyDescriptor : pluginInfo.getPropertyDescriptors()) {
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
                properties.put(name, Optional.of(""));
            } else if (value instanceof String) {
                properties.put(name, Optional.of(value));
            } else if (!ignoreWarnings) {
                logger.warn("unexpected property type '{}' for property name '{}'", value
                        .getClass().getName(), name);
            }
        }
        private void setBooleanProperty(String name, @Nullable Object value,
                boolean ignoreWarnings) {
            if (value == null) {
                if (!ignoreWarnings) {
                    logger.warn("boolean property types do not accept null values");
                }
                properties.put(name, Optional.of(false));
            } else if (value instanceof Boolean) {
                properties.put(name, Optional.of(value));
            } else if (!ignoreWarnings) {
                logger.warn("unexpected property type '{}' for property name '{}'", value
                        .getClass().getName(), name);
            }
        }
        private void setDoubleProperty(String name, @Nullable Object value,
                boolean ignoreWarnings) {
            if (value == null) {
                properties.put(name, Optional.absent());
            } else if (value instanceof Double) {
                properties.put(name, Optional.of(value));
            } else if (!ignoreWarnings) {
                logger.warn("unexpected property type '{}' for property name '{}'", value
                        .getClass().getName(), name);
            }
        }
    }
}
