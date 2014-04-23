/*
 * Copyright 2012-2014 the original author or authors.
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

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.markers.UsedByJsonBinding;

import static org.glowroot.common.ObjectMappers.checkRequiredProperty;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
@UsedByJsonBinding
public abstract class PropertyDescriptor {

    private static final Logger logger = LoggerFactory.getLogger(PropertyDescriptor.class);

    private final String name;
    private final boolean hidden;
    @Nullable
    private final String prompt;
    @Nullable
    private final String description;

    private PropertyDescriptor(String name, boolean hidden, @Nullable String prompt,
            @Nullable String description) {
        this.name = name;
        this.hidden = hidden;
        this.prompt = prompt;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public abstract PropertyType getType();

    @Nullable
    public abstract Object getDefault();

    public boolean isHidden() {
        return hidden;
    }

    @Nullable
    public String getPrompt() {
        return prompt;
    }

    @Nullable
    public String getDescription() {
        return description;
    }

    /*@Pure*/
    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("name", name)
                .add("type", getType())
                .add("default", getDefault())
                .add("hidden", hidden)
                .add("prompt", prompt)
                .add("description", description)
                .toString();
    }

    // visible for packager-maven-plugin
    public static PropertyDescriptor create(String name, boolean hidden,
            @Nullable String prompt, @Nullable String description, PropertyType type,
            @Nullable Object defaultValue) {
        if (type == PropertyType.STRING) {
            return new StringPropertyDescriptor(name, defaultValue, hidden, prompt,
                    description);
        }
        if (type == PropertyType.BOOLEAN) {
            return new BooleanPropertyDescriptor(name, defaultValue, hidden, prompt,
                    description);
        }
        if (type == PropertyType.DOUBLE) {
            return new DoublePropertyDescriptor(name, defaultValue, hidden, prompt,
                    description);
        }
        throw new AssertionError("Unknown PropertyType enum: " + type);
    }

    @JsonCreator
    static PropertyDescriptor readValue(@JsonProperty("name") @Nullable String name,
            @JsonProperty("type") @Nullable PropertyType type,
            @JsonProperty("default") @Nullable Object defaultValue,
            @JsonProperty("hidden") @Nullable Boolean hidden,
            @JsonProperty("prompt") @Nullable String prompt,
            @JsonProperty("description") @Nullable Multiline description)
            throws JsonMappingException {
        checkRequiredProperty(name, "name");
        checkRequiredProperty(type, "type");
        checkRequiredProperty(prompt, "prompt");
        return create(name, hidden != null && hidden, prompt,
                description == null ? null : description.getJoined(),
                type, defaultValue);
    }

    enum PropertyType {
        STRING, BOOLEAN, DOUBLE
    }

    @Immutable
    static class StringPropertyDescriptor extends PropertyDescriptor {

        private final String defaultValue;

        private StringPropertyDescriptor(String name, @Nullable Object defaultValue,
                boolean hidden, @Nullable String prompt, @Nullable String description) {
            super(name, hidden, prompt, description);
            if (defaultValue instanceof String) {
                this.defaultValue = (String) defaultValue;
            } else if (defaultValue == null) {
                this.defaultValue = "";
            } else {
                logger.error("property {} has unexpected value: {}", name, defaultValue);
                this.defaultValue = "";
            }
        }

        @Override
        public PropertyType getType() {
            return PropertyType.STRING;
        }

        @Override
        public String getDefault() {
            return defaultValue;
        }
    }

    @Immutable
    static class BooleanPropertyDescriptor extends PropertyDescriptor {

        private final boolean defaultValue;

        private BooleanPropertyDescriptor(String name, @Nullable Object defaultValue,
                boolean hidden, @Nullable String prompt, @Nullable String description) {
            super(name, hidden, prompt, description);
            if (defaultValue instanceof Boolean) {
                this.defaultValue = (Boolean) defaultValue;
            } else if (defaultValue == null) {
                this.defaultValue = false;
            } else {
                logger.error("property {} has unexpected value: {}", name, defaultValue);
                this.defaultValue = false;
            }
        }

        @Override
        public PropertyType getType() {
            return PropertyType.BOOLEAN;
        }

        @Override
        public Boolean getDefault() {
            return defaultValue;
        }
    }

    @Immutable
    static class DoublePropertyDescriptor extends PropertyDescriptor {

        @Nullable
        private final Double defaultValue;

        private DoublePropertyDescriptor(String name, @Nullable Object defaultValue,
                boolean hidden, @Nullable String prompt, @Nullable String description) {
            super(name, hidden, prompt, description);
            if (defaultValue instanceof Double || defaultValue == null) {
                this.defaultValue = (Double) defaultValue;
            } else {
                logger.error("property {} has unexpected value: {}", name, defaultValue);
                this.defaultValue = null;
            }
        }

        @Override
        public PropertyType getType() {
            return PropertyType.DOUBLE;
        }

        @Override
        @Nullable
        public Double getDefault() {
            return defaultValue;
        }
    }
}
