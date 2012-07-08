/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.core.configuration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import org.informantproject.core.configuration.PluginDescriptor.PropertyDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.base.Objects.ToStringHelper;
import com.google.common.collect.Maps;
import com.google.common.io.CharStreams;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonWriter;

/**
 * Immutable structure to hold the current configuration for Plugins.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ImmutablePluginConfiguration {

    private static final Logger logger = LoggerFactory
            .getLogger(ImmutablePluginConfiguration.class);

    private static final List<Class<?>> validValueTypes;

    static {
        // TODO write unit tests using each of these data types
        validValueTypes = new ArrayList<Class<?>>();
        validValueTypes.add(String.class);
        validValueTypes.add(Boolean.class);
        validValueTypes.add(Double.class);
    }

    private final boolean enabled;

    // all defined properties (as defined in each plugin's org.informantproject.plugin.xml file) are
    // included in the property map, even those properties with null value, so that an appropriate
    // error can be logged if a plugin tries to access a property value that it hasn't defined in
    // its plugin.xml file
    //
    // map values are @Nullable
    private final Map<String, Object> properties = new HashMap<String, Object>();

    ImmutablePluginConfiguration(boolean enabled, Map<String, Object> properties) {
        this.enabled = enabled;
        // make a copy and validate value types at the same time
        for (Entry<String, Object> subEntry : properties.entrySet()) {
            String propertyName = subEntry.getKey();
            if (subEntry.getValue() == null) {
                this.properties.put(propertyName, null);
            } else {
                Object value = subEntry.getValue();
                if (validValueTypes.contains(value.getClass())) {
                    this.properties.put(propertyName, value);
                } else {
                    logger.error("unexpected plugin configuration value type '" + value.getClass()
                            + "' for property name '" + propertyName + "' (expecting one of "
                            + Joiner.on(", ").join(validValueTypes) + ")", new Throwable());
                }
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Nullable
    public String getStringProperty(String name) {
        Object value = properties.get(name);
        if (value == null) {
            return null;
        } else if (value instanceof String) {
            return (String) value;
        } else {
            logger.error("expecting string value type, but found value type '" + value.getClass()
                    + "' for property name '" + name + "'");
            return null;
        }
    }

    public boolean getBooleanProperty(String name) {
        Object value = properties.get(name);
        if (value == null) {
            return false;
        } else if (value instanceof Boolean) {
            return (Boolean) value;
        } else {
            logger.error("expecting boolean value type, but found value type '" + value.getClass()
                    + "' for property name '" + name + "'");
            return false;
        }
    }

    @Nullable
    public Double getDoubleProperty(String name) {
        Object value = properties.get(name);
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

    public String getNonHiddenPropertiesJson(PluginDescriptor pluginDescriptor) {
        StringBuilder sb = new StringBuilder();
        JsonWriter jw = new JsonWriter(CharStreams.asWriter(sb));
        try {
            jw.beginObject();
            for (PropertyDescriptor property : pluginDescriptor.getPropertyDescriptors()) {
                if (property.isHidden()) {
                    continue;
                }
                Object value = properties.get(property.getName());
                jw.name(property.getName());
                if (value == null) {
                    jw.nullValue();
                } else if (value instanceof String) {
                    jw.value((String) value);
                } else if (value instanceof Boolean) {
                    jw.value((Boolean) value);
                } else if (value instanceof Double) {
                    jw.value((Double) value);
                } else {
                    logger.error("unexpected property value type '{}'", value.getClass().getName());
                }
            }
            jw.endObject();
            jw.close();
            return sb.toString();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            return "{}";
        }
    }

    @Override
    public String toString() {
        ToStringHelper toStringHelper = Objects.toStringHelper(this).add("properties",
                properties);
        return toStringHelper.toString();
    }

    @Override
    public boolean equals(@Nullable Object object) {
        if (object == null) {
            return false;
        }
        if (object == this) {
            return true;
        }
        if (object.getClass() != getClass()) {
            return false;
        }
        ImmutablePluginConfiguration rhs = (ImmutablePluginConfiguration) object;
        return enabled == rhs.enabled && properties.equals(rhs.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(enabled, properties);
    }

    public static ImmutablePluginConfiguration create(PluginDescriptor pluginDescriptor,
            boolean enabled) {

        return new PluginConfigurationBuilder(pluginDescriptor)
                .setEnabled(enabled)
                .build();
    }

    public static ImmutablePluginConfiguration create(PluginDescriptor pluginDescriptor,
            boolean enabled, JsonObject propertiesJson) {

        return new PluginConfigurationBuilder(pluginDescriptor)
                .setEnabled(enabled)
                .setProperties(propertiesJson)
                .build();
    }

    public static ImmutablePluginConfiguration createDisabledHasNoDescriptor() {
        return new ImmutablePluginConfiguration(false, new HashMap<String, Object>());
    }

    public static class PluginConfigurationBuilder {

        private boolean enabled;
        private final PluginDescriptor pluginDescriptor;
        // map values are @Nullable
        private final Map<String, Object> properties;

        public PluginConfigurationBuilder(PluginDescriptor pluginDescriptor) {
            this.pluginDescriptor = pluginDescriptor;
            properties = Maps.newHashMap();
            for (PropertyDescriptor property : pluginDescriptor.getPropertyDescriptors()) {
                properties.put(property.getName(), property.getDefault());
            }
        }

        public PluginConfigurationBuilder(PluginDescriptor pluginDescriptor,
                ImmutablePluginConfiguration base) {

            this.pluginDescriptor = pluginDescriptor;
            this.enabled = base.enabled;
            properties = Maps.newHashMap(base.properties);
        }

        public PluginConfigurationBuilder setEnabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        // ignoreExtraProperties option exists for instantiating plugin configuration from a stored
        // json value which may be out of sync if the plugin has been updated and the given property
        // has changed, e.g. from not hidden to hidden, in which case the associated error messages
        // should be suppressed
        public PluginConfigurationBuilder setProperty(String name, @Nullable Object value,
                boolean ignoreExtraProperties) {

            PropertyDescriptor property = pluginDescriptor.getPropertyDescriptor(name);
            if (property == null) {
                logger.error("unexpected property name '{}'", name);
                return this;
            }
            if (property.isHidden()) {
                if (!ignoreExtraProperties) {
                    logger.error("cannot set hidden property '{}' with value '{}', hidden"
                            + " properties can only be set by via org.informantproject.plugin"
                            + ".xml or org.informantproject.package.xml", name, value);
                }
                return this;
            }
            if (value != null && !property.getJavaClass().isAssignableFrom(value
                    .getClass())) {
                if (!ignoreExtraProperties) {
                    logger.error("unexpected property type '{}' for property name '{}'",
                            value.getClass().getName(), name);
                }
                return this;
            }
            if (value == null && property.getJavaClass() == Boolean.class) {
                logger.error("boolean property types do not accept null values");
                properties.put(name, false);
            } else {
                properties.put(name, value);
            }
            return this;
        }

        public PluginConfigurationBuilder setProperties(JsonObject jsonObject) {
            setProperties(jsonObject, false);
            return this;
        }

        public PluginConfigurationBuilder setProperties(JsonObject jsonObject,
                boolean ignoreExtraProperties) {

            Map<String, Object> overlayProperties = new Gson().fromJson(jsonObject,
                    new TypeToken<Map<String, Object>>() {}.getType());
            // overlay new values
            for (Entry<String, Object> subEntry : overlayProperties.entrySet()) {
                String name = subEntry.getKey();
                Object value = subEntry.getValue();
                // convert all numbers to double
                if (value instanceof Number) {
                    value = ((Number) value).doubleValue();
                }
                setProperty(name, value, ignoreExtraProperties);
            }
            return this;
        }

        public ImmutablePluginConfiguration build() {
            return new ImmutablePluginConfiguration(enabled, properties);
        }
    }
}
