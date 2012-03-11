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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.base.Objects.ToStringHelper;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

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
        validValueTypes.add(Double.class);
        validValueTypes.add(Boolean.class);
    }

    private final Map<String, Map<String, Object>> propertyMaps;

    ImmutablePluginConfiguration(Map<String, Map<String, Object>> values) {
        // make a copy and validate value types at the same time
        propertyMaps = new HashMap<String, Map<String, Object>>();
        for (Entry<String, Map<String, Object>> entry : values.entrySet()) {
            Map<String, Object> propertyMap = new HashMap<String, Object>();
            for (Entry<String, Object> subEntry : entry.getValue().entrySet()) {
                if (validValueTypes.contains(subEntry.getValue().getClass())) {
                    propertyMap.put(subEntry.getKey(), subEntry.getValue());
                } else {
                    logger.error("unexpected plugin configuration value type '"
                            + subEntry.getValue().getClass() + "' for pluginId '" + entry.getKey()
                            + "' and property name '" + subEntry.getKey() + "' (expecting one of "
                            + Joiner.on(", ").join(validValueTypes) + ")", new Throwable());
                }
            }
            propertyMaps.put(entry.getKey(), propertyMap);
        }
    }

    // never returns null
    public String getStringProperty(String pluginId, String propertyName) {
        Map<String, Object> propertyMap = propertyMaps.get(pluginId);
        if (propertyMap == null) {
            return "";
        }
        Object propertyValue = propertyMap.get(propertyName);
        if (propertyValue == null) {
            return "";
        } else {
            return propertyValue.toString();
        }
    }

    public Double getDoubleProperty(String pluginId, String propertyName, Double defaultValue) {
        Map<String, Object> propertyMap = propertyMaps.get(pluginId);
        if (propertyMap == null) {
            return defaultValue;
        }
        Object propertyValue = propertyMap.get(propertyName);
        if (propertyValue instanceof Number) {
            return ((Number) propertyValue).doubleValue();
        } else if (propertyValue == null) {
            return defaultValue;
        } else {
            logger.warn("expecting Double value type, but found value type '"
                    + propertyValue.getClass() + "' for pluginId '" + pluginId
                    + "' and property name '" + propertyName + "'");
            return defaultValue;
        }
    }

    public Boolean getBooleanProperty(String pluginId, String propertyName, Boolean defaultValue) {

        Map<String, Object> propertyMap = propertyMaps.get(pluginId);
        if (propertyMap == null) {
            return defaultValue;
        }
        Object propertyValue = propertyMap.get(propertyName);
        if (propertyValue instanceof Boolean) {
            return (Boolean) propertyValue;
        } else if (propertyValue == null) {
            return defaultValue;
        } else {
            logger.warn("expecting Boolean value type, but found value type '"
                    + propertyValue.getClass() + "' for pluginId '" + pluginId
                    + "' and property name '" + propertyName + "'");
            return defaultValue;
        }
    }

    public Iterable<String> getPluginIds() {
        return propertyMaps.keySet();
    }

    public Iterable<String> getPropertyNames(String pluginId) {
        Map<String, Object> propertyMap = propertyMaps.get(pluginId);
        if (propertyMap == null) {
            return Collections.emptySet();
        } else {
            return propertyMap.keySet();
        }
    }

    @Override
    public String toString() {
        ToStringHelper toStringHelper = Objects.toStringHelper(this).add("propertyMaps",
                propertyMaps);
        return toStringHelper.toString();
    }

    @Override
    public boolean equals(Object object) {
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
        return propertyMaps.equals(rhs.propertyMaps);
    }

    public String toJson() {
        return new Gson().toJson(propertyMaps);
    }

    @Override
    public int hashCode() {
        return propertyMaps.hashCode();
    }

    static ImmutablePluginConfiguration fromJson(String json) {
        // the default Gson deserializer maps number types to double
        // which perfectly fits this needs of this class
        Map<String, Map<String, Object>> values = new Gson().fromJson(json,
                new TypeToken<Map<String, Map<String, Object>>>() {}.getType());
        return new ImmutablePluginConfiguration(values);
    }

    static ImmutablePluginConfiguration fromJson(JsonObject json) {
        // the default Gson deserializer maps number types to double
        // which perfectly fits this needs of this class
        Map<String, Map<String, Object>> values = new Gson().fromJson(json,
                new TypeToken<Map<String, Map<String, Object>>>() {}.getType());
        return new ImmutablePluginConfiguration(values);
    }
}
