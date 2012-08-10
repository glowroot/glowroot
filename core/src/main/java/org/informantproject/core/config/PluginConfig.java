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
package org.informantproject.core.config;

import java.io.IOException;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import org.informantproject.core.config.PluginDescriptor.PropertyDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Objects;
import com.google.common.base.Objects.ToStringHelper;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.io.CharStreams;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonWriter;

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

    // all defined properties (as defined in each plugin's org.informantproject.plugin.xml file) are
    // included in the property map, even those properties with null value, so that an appropriate
    // error can be logged if a plugin tries to access a property value that it hasn't defined in
    // its plugin.xml file
    private final ImmutableMap<String, Optional<?>> properties;

    static PluginConfig getEnabledInstance() {
        ImmutableMap<String, Optional<?>> properties = ImmutableMap.of();
        return new PluginConfig(true, properties);
    }

    static PluginConfig getDisabledInstance() {
        ImmutableMap<String, Optional<?>> properties = ImmutableMap.of();
        return new PluginConfig(false, properties);
    }

    static Builder builder(PluginDescriptor pluginDescriptor) {
        return new Builder(pluginDescriptor);
    }

    private PluginConfig(boolean enabled, ImmutableMap<String, Optional<?>> properties) {
        this.enabled = enabled;
        this.properties = properties;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Nullable
    public String getStringProperty(String name) {
        Optional<?> optional = properties.get(name);
        if (optional == null) {
            logger.error("unexpected property name '{}'", name);
            return null;
        }
        Object value = optional.orNull();
        if (value == null) {
            return null;
        } else if (value instanceof String) {
            return (String) value;
        } else {
            logger.error("expecting string value type, but found value type '"
                    + value.getClass() + "' for property name '" + name + "'");
            return null;
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

    String getNonHiddenPropertiesJson(PluginDescriptor pluginDescriptor) {
        StringBuilder sb = new StringBuilder();
        JsonWriter jw = new JsonWriter(CharStreams.asWriter(sb));
        try {
            jw.beginObject();
            for (PropertyDescriptor property : pluginDescriptor.getPropertyDescriptors()) {
                if (property.isHidden()) {
                    continue;
                }
                Optional<?> optional = properties.get(property.getName());
                Object value = optional == null ? null : optional.orNull();
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
                    jw.value("");
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
        ToStringHelper toStringHelper = Objects.toStringHelper(this).add("properties", properties);
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
        PluginConfig rhs = (PluginConfig) object;
        return enabled == rhs.enabled && properties.equals(rhs.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(enabled, properties);
    }

    static class Builder {

        private static final Gson gson = new Gson();

        private final PluginDescriptor pluginDescriptor;
        private final Map<String, Optional<?>> properties;
        private boolean enabled;

        private Builder(PluginDescriptor pluginDescriptor) {
            this.pluginDescriptor = pluginDescriptor;
            properties = Maps.newHashMap();
            for (PropertyDescriptor property : pluginDescriptor.getPropertyDescriptors()) {
                properties.put(property.getName(), Optional.fromNullable(property.getDefault()));
            }
        }

        Builder copy(PluginConfig base) {
            this.enabled = base.enabled;
            properties.putAll(base.properties);
            return this;
        }

        Builder setEnabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        // ignoreExtraProperties option exists for instantiating plugin config from a stored json
        // value which may be out of sync if the plugin has been updated and the given property has
        // changed, e.g. from not hidden to hidden, in which case the associated error messages
        // should be suppressed
        private Builder setProperty(String name, @Nullable Object value,
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
            if (value != null && !property.getJavaClass().isAssignableFrom(value.getClass())) {
                if (!ignoreExtraProperties) {
                    logger.error("unexpected property type '{}' for property name '{}'", value
                            .getClass().getName(), name);
                }
                return this;
            }
            if (value == null && property.getJavaClass() == Boolean.class) {
                logger.error("boolean property types do not accept null values");
                properties.put(name, Optional.of(false));
            } else {
                properties.put(name, Optional.fromNullable(value));
            }
            return this;
        }

        Builder setProperties(JsonObject jsonObject) {
            setProperties(jsonObject, false);
            return this;
        }

        private void setProperties(JsonObject jsonObject, boolean ignoreExtraProperties) {
            Map<String, Object> overlayProperties = gson.fromJson(jsonObject,
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
        }

        PluginConfig build() {
            return new PluginConfig(enabled, ImmutableMap.copyOf(properties));
        }
    }
}
