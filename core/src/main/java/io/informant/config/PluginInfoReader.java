/**
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.config;

import io.informant.config.PropertyDescriptor.PropertyType;
import io.informant.util.JsonElements;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import checkers.igj.quals.Immutable;
import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;

import com.google.common.collect.Lists;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class PluginInfoReader {

    private static final Logger logger = LoggerFactory.getLogger(PluginInfoReader.class);

    private PluginInfoReader() {}

    public static PluginInfo createPluginInfo(String json) throws JsonSyntaxException {
        JsonObject rootElement = (JsonObject) new JsonParser().parse(json);
        return PluginInfoReader.createPluginInfo(rootElement);
    }

    static PluginInfo createPluginInfo(@ReadOnly JsonObject pluginElement)
            throws JsonSyntaxException {
        String name = JsonElements.getRequiredString(pluginElement, "name");
        String groupId = JsonElements.getRequiredString(pluginElement, "groupId");
        String artifactId = JsonElements.getRequiredString(pluginElement, "artifactId");
        String version = JsonElements.getRequiredString(pluginElement, "version");
        JsonArray propertyElements = JsonElements.getOptionalArray(pluginElement, "properties");
        List<PropertyDescriptor> properties = Lists.newArrayList();
        for (Iterator<JsonElement> i = propertyElements.iterator(); i.hasNext();) {
            properties.add(createPropertyDescriptor(i.next().getAsJsonObject()));
        }
        JsonArray aspectElements = JsonElements.getOptionalArray(pluginElement, "aspects");
        List<String> aspects = Lists.newArrayList();
        for (Iterator<JsonElement> i = aspectElements.iterator(); i.hasNext();) {
            aspects.add(i.next().getAsString());
        }
        return new PluginInfo(name, groupId, artifactId, version, properties, aspects);
    }

    private static PropertyDescriptor createPropertyDescriptor(JsonObject propertyElement)
            throws JsonSyntaxException {
        String prompt = JsonElements.getRequiredString(propertyElement, "prompt");
        String name = JsonElements.getRequiredString(propertyElement, "name");
        PropertyType type = getType(JsonElements.getRequiredString(propertyElement, "type"));
        Object defaultValue = getDefaultValueFromElement(propertyElement.get("default"), type);
        boolean hidden = getHidden(propertyElement.get("hidden"));
        String description = getDescription(propertyElement.get("description"));
        return new PropertyDescriptor(prompt, name, type, defaultValue, hidden, description);
    }

    private static boolean getHidden(@ReadOnly @Nullable JsonElement hiddenElement) {
        if (hiddenElement == null || hiddenElement.isJsonNull()) {
            return false;
        }
        return hiddenElement.getAsBoolean();
    }

    private static PropertyType getType(String type) throws JsonSyntaxException {
        try {
            return PropertyType.valueOf(type.toUpperCase(Locale.ENGLISH));
        } catch (IllegalArgumentException e) {
            throw new JsonSyntaxException("Invalid type attribute: " + type, e);
        }
    }

    @Nullable
    private static Object getDefaultValueFromElement(
            @ReadOnly @Nullable JsonElement defaultValueElement, PropertyType type) {
        if (defaultValueElement == null || defaultValueElement.isJsonNull()) {
            return getDefaultValueForType(type);
        }
        String defaultValueText = defaultValueElement.getAsString();
        if (type == PropertyType.STRING) {
            return defaultValueText;
        } else if (type == PropertyType.BOOLEAN) {
            if (defaultValueText.equalsIgnoreCase("true")) {
                return true;
            } else if (defaultValueText.equalsIgnoreCase("false")) {
                return false;
            } else {
                logger.warn("unexpected boolean value '" + defaultValueText + "', must be either"
                        + " 'true' or 'false', defaulting to 'false'");
                return false;
            }
        } else if (type == PropertyType.DOUBLE) {
            try {
                return Double.parseDouble(defaultValueText);
            } catch (NumberFormatException e) {
                logger.warn("unable to parse default value '" + defaultValueText
                        + "' as a double");
                return null;
            }
        } else {
            logger.warn("unexpected type: {}", type);
            return null;
        }
    }

    @Nullable
    private static Object getDefaultValueForType(PropertyType type) {
        switch (type) {
        case STRING:
            return "";
        case BOOLEAN:
            return false;
        case DOUBLE:
            return null;
        default:
            logger.warn("unexpected property type '{}'", type);
            return null;
        }
    }

    @Nullable
    private static String getDescription(@ReadOnly @Nullable JsonElement descriptionElement) {
        if (descriptionElement == null || descriptionElement.isJsonNull()) {
            return null;
        }
        if (descriptionElement.isJsonArray()) {
            JsonArray descriptionElements = descriptionElement.getAsJsonArray();
            StringBuilder description = new StringBuilder();
            for (Iterator<JsonElement> i = descriptionElements.iterator(); i.hasNext();) {
                description.append(i.next().getAsString());
            }
            return description.toString();
        } else {
            return descriptionElement.getAsString();
        }
    }
}
