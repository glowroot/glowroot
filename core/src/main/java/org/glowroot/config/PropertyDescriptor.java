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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.Objects;
import com.google.common.collect.Ordering;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.markers.Immutable;
import org.glowroot.markers.UsedByJsonBinding;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.glowroot.common.ObjectMappers.checkRequiredProperty;
import static org.glowroot.common.ObjectMappers.nullToFalse;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
@UsedByJsonBinding
public abstract class PropertyDescriptor {

    private static final Logger logger = LoggerFactory.getLogger(PropertyDescriptor.class);

    static final Ordering<PropertyDescriptor> orderingByName = new Ordering<PropertyDescriptor>() {
        @Override
        public int compare(@Nullable PropertyDescriptor left, @Nullable PropertyDescriptor right) {
            checkNotNull(left);
            checkNotNull(right);
            return left.name.compareToIgnoreCase(right.name);
        }
    };

    private final String name;
    private final boolean hidden;
    private final String label;
    private final String checkboxLabel;
    private final String description;

    private PropertyDescriptor(String name, boolean hidden, String label, String checkboxLabel,
            String description) {
        this.name = name;
        this.hidden = hidden;
        this.label = label;
        this.checkboxLabel = checkboxLabel;
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

    public String getLabel() {
        return label;
    }

    public String getCheckboxLabel() {
        return checkboxLabel;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("name", name)
                .add("type", getType())
                .add("default", getDefault())
                .add("hidden", hidden)
                .add("label", label)
                .add("checkboxLabel", checkboxLabel)
                .add("description", description)
                .toString();
    }

    @JsonCreator
    static PropertyDescriptor readValue(@JsonProperty("name") @Nullable String name,
            @JsonProperty("type") @Nullable PropertyType type,
            @JsonProperty("default") @Nullable Object defaultValue,
            @JsonProperty("hidden") @Nullable Boolean hidden,
            @JsonProperty("label") @Nullable String label,
            @JsonProperty("checkboxLabel") @Nullable String checkboxLabel,
            @JsonProperty("description") @Nullable Multiline descriptionMultiline)
            throws JsonMappingException {
        checkRequiredProperty(name, "name");
        checkRequiredProperty(type, "type");
        checkRequiredProperty(label, "label");
        String description = descriptionMultiline == null ? "" : descriptionMultiline.getJoined();
        if (type == PropertyType.STRING) {
            return new StringPropertyDescriptor(name, defaultValue, nullToFalse(hidden), label,
                    description);
        }
        if (type == PropertyType.BOOLEAN) {
            checkRequiredProperty(checkboxLabel, "checkboxLabel");
            return new BooleanPropertyDescriptor(name, defaultValue, nullToFalse(hidden), label,
                    checkboxLabel, description);
        }
        if (type == PropertyType.DOUBLE) {
            return new DoublePropertyDescriptor(name, defaultValue, nullToFalse(hidden), label,
                    description);
        }
        throw new AssertionError("Unknown PropertyType enum: " + type);
    }

    enum PropertyType {
        STRING, BOOLEAN, DOUBLE
    }

    @Immutable
    static class StringPropertyDescriptor extends PropertyDescriptor {

        private final String defaultValue;

        private StringPropertyDescriptor(String name, @Nullable Object defaultValue,
                boolean hidden, String label, String description) {
            super(name, hidden, label, "", description);
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
                boolean hidden, String label, String checkboxLabel, String description) {
            super(name, hidden, label, checkboxLabel, description);
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
                boolean hidden, String label, String description) {
            super(name, hidden, label, "", description);
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
