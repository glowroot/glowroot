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

import io.informant.util.MultilineDeserializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import checkers.igj.quals.Immutable;
import checkers.nullness.quals.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
@JsonDeserialize(builder = PropertyDescriptor.Builder.class)
public abstract class PropertyDescriptor {

    private static final Logger logger = LoggerFactory.getLogger(PropertyDescriptor.class);

    private final String name;
    private final boolean hidden;
    @Nullable
    private final String prompt;
    @Nullable
    private final String description;

    public static PropertyDescriptor.Builder builder() {
        return new Builder();
    }

    public static PropertyDescriptor.Builder builder(PropertyDescriptor base) {
        return new Builder(base);
    }

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

    @Immutable
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

    @Immutable
    public enum PropertyType {
        STRING, BOOLEAN, DOUBLE;
    }

    @Immutable
    static class StringPropertyDescriptor extends PropertyDescriptor {
        private final String defaultValue;
        private StringPropertyDescriptor(String name, String defaultValue, boolean hidden,
                @Nullable String prompt, @Nullable String description) {
            super(name, hidden, prompt, description);
            this.defaultValue = defaultValue;
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
        private BooleanPropertyDescriptor(String name, boolean defaultValue, boolean hidden,
                @Nullable String prompt, @Nullable String description) {
            super(name, hidden, prompt, description);
            this.defaultValue = defaultValue;
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
        private DoublePropertyDescriptor(String name, @Nullable Double defaultValue,
                boolean hidden, @Nullable String prompt, @Nullable String description) {
            super(name, hidden, prompt, description);
            this.defaultValue = defaultValue;
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

    @JsonPOJOBuilder(withPrefix = "")
    public static class Builder {

        @Nullable
        private String name;
        @Nullable
        private PropertyType type;
        @Nullable
        private Object defaultValue;
        private boolean hidden;
        @Nullable
        private String prompt;
        @Nullable
        private String description;

        private Builder() {}

        private Builder(PropertyDescriptor base) {
            name = base.name;
            type = base.getType();
            defaultValue = base.getDefault();
            hidden = base.hidden;
            prompt = base.prompt;
            description = base.description;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder type(PropertyType type) {
            this.type = type;
            return this;
        }

        // default is java reserved word
        @JsonProperty("default")
        public Builder defaultValue(@Nullable Object defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }

        public Builder hidden(boolean hidden) {
            this.hidden = hidden;
            return this;
        }

        public Builder prompt(@Nullable String prompt) {
            this.prompt = prompt;
            return this;
        }

        @JsonDeserialize(using = MultilineDeserializer.class)
        public Builder description(@Nullable String description) {
            this.description = description;
            return this;
        }

        public PropertyDescriptor build() {
            Preconditions.checkNotNull(name);
            Preconditions.checkNotNull(type);
            if (type == PropertyType.STRING) {
                return new StringPropertyDescriptor(name, getDefaultAsString(), hidden, prompt,
                        description);
            }
            if (type == PropertyType.BOOLEAN) {
                return new BooleanPropertyDescriptor(name, getDefaultAsBoolean(), hidden, prompt,
                        description);
            }
            if (type == PropertyType.DOUBLE) {
                return new DoublePropertyDescriptor(name, getDefaultAsDouble(), hidden, prompt,
                        description);
            }
            throw new IllegalStateException("Unexpected PropertyType: " + type);
        }

        private String getDefaultAsString() {
            if (defaultValue instanceof String) {
                return (String) defaultValue;
            } else if (defaultValue == null) {
                return "";
            } else {
                logger.error("unexpected value for property {}: {}", name, defaultValue);
                return "";
            }
        }

        private Boolean getDefaultAsBoolean() {
            if (defaultValue instanceof Boolean) {
                return (Boolean) defaultValue;
            } else if (defaultValue == null) {
                return false;
            } else {
                logger.error("unexpected value for property {}: {}", name, defaultValue);
                return false;
            }
        }

        @Nullable
        private Double getDefaultAsDouble() {
            if (defaultValue instanceof Double || defaultValue == null) {
                return (Double) defaultValue;
            } else {
                logger.error("unexpected value for property {}: {}", name, defaultValue);
                return null;
            }
        }
    }
}
