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
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.io.CharStreams;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
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

    @Nullable
    private final PluginDescriptor pluginDescriptor;

    public static Builder builder(String pluginId, PluginConfig base) {
        return new Builder(pluginId, base);
    }

    static PluginConfig getNopInstance() {
        return new PluginConfig(false, ImmutableMap.<String, Optional<?>> of(), null);
    }

    static PluginConfig fromJson(final String pluginId, String json) throws JsonSyntaxException {
        PluginConfig.Builder builder = PluginConfig.builder(pluginId);
        builder.overlay(json, true);
        return builder.build();
    }

    static Builder builder(String pluginId) {
        return new Builder(pluginId);
    }

    private PluginConfig(boolean enabled, ImmutableMap<String, Optional<?>> properties,
            PluginDescriptor pluginDescriptor) {

        this.enabled = enabled;
        this.properties = properties;
        this.pluginDescriptor = pluginDescriptor;
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

    // only non-hidden properties
    String toJson() {
        StringBuilder sb = new StringBuilder();
        JsonWriter jw = new JsonWriter(CharStreams.asWriter(sb));
        try {
            toJson(jw);
            jw.close();
            return sb.toString();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            return "{}";
        }
    }

    public void toJson(JsonWriter jw) throws IOException {
        // pluginDescriptor can be null for anonymous disabled instance, see getDisabledInstance()
        jw.beginObject();
        jw.name("enabled");
        jw.value(enabled);
        if (pluginDescriptor != null) {
            for (PropertyDescriptor property : pluginDescriptor.getPropertyDescriptors()) {
                if (property.isHidden()) {
                    continue;
                }
                jw.name(property.getName());
                if (property.getType().equals("string")) {
                    String value = getStringProperty(property.getName());
                    if (value == null) {
                        jw.nullValue();
                    } else {
                        jw.value(value);
                    }
                } else if (property.getType().equals("boolean")) {
                    jw.value(getBooleanProperty(property.getName()));
                } else if (property.getType().equals("double")) {
                    Double value = getDoubleProperty(property.getName());
                    if (value == null) {
                        jw.nullValue();
                    } else {
                        jw.value(value);
                    }
                } else {
                    logger.error("unexpected type '" + property.getType() + "', this should have"
                            + " been caught by schema validation");
                }
            }
        }
        jw.endObject();
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("enabled", enabled)
                .add("properties", properties)
                .toString();
    }

    public static class Builder {

        private boolean enabled = true;
        private final PluginDescriptor pluginDescriptor;
        private final Map<String, Optional<?>> properties;

        private Builder(String pluginId) {
            this.pluginDescriptor = Plugins.getDescriptor(pluginId);
            properties = Maps.newHashMap();
            for (PropertyDescriptor property : pluginDescriptor.getPropertyDescriptors()) {
                properties.put(property.getName(), Optional.fromNullable(property.getDefault()));
            }
        }
        private Builder(String pluginId, PluginConfig base) {
            this(pluginId);
            this.enabled = base.enabled;
            properties.putAll(base.properties);
        }
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }
        public void overlay(String json) throws JsonSyntaxException {
            overlay(json, false);
        }
        public PluginConfig build() {
            return new PluginConfig(enabled, ImmutableMap.copyOf(properties), pluginDescriptor);
        }
        private void overlay(String json, boolean ignoreWarnings) throws JsonSyntaxException {
            JsonObject jsonObject = new JsonParser().parse(json).getAsJsonObject();
            if (jsonObject.get("enabled") != null) {
                enabled(jsonObject.get("enabled").getAsBoolean());
            }
            for (Entry<String, JsonElement> subEntry : jsonObject.entrySet()) {
                String name = subEntry.getKey();
                if (name.equals("enabled")) {
                    continue;
                }
                JsonElement value = subEntry.getValue();
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
        private Builder setProperty(String name, @Nullable Object value, boolean ignoreWarnings) {
            PropertyDescriptor property = pluginDescriptor.getPropertyDescriptor(name);
            if (property == null) {
                if (!ignoreWarnings) {
                    logger.warn("unexpected property name '{}'", name);
                }
                return this;
            }
            if (property.isHidden()) {
                if (!ignoreWarnings) {
                    logger.warn("cannot set hidden property '{}' with value '{}', hidden"
                            + " properties can only be set by via org.informantproject.plugin"
                            + ".xml or org.informantproject.package.xml", name, value);
                }
                return this;
            }
            if (value != null && !property.getJavaClass().isAssignableFrom(value.getClass())) {
                if (!ignoreWarnings) {
                    logger.warn("unexpected property type '{}' for property name '{}'", value
                            .getClass().getName(), name);
                }
                return this;
            }
            if (value == null && property.getJavaClass() == Boolean.class) {
                if (!ignoreWarnings) {
                    logger.warn("boolean property types do not accept null values");
                }
                properties.put(name, Optional.of(false));
            } else {
                properties.put(name, Optional.fromNullable(value));
            }
            return this;
        }
    }
}
