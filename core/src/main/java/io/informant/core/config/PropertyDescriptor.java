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
package io.informant.core.config;

import io.informant.api.Logger;
import io.informant.api.LoggerFactory;

import com.google.common.base.Objects;

import checkers.igj.quals.Immutable;
import checkers.nullness.quals.Nullable;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class PropertyDescriptor {

    private static final Logger logger = LoggerFactory.getLogger(PropertyDescriptor.class);

    private final String prompt;
    private final String name;
    private final PropertyType type;
    @Nullable
    private final Object defaultValue;
    private final boolean hidden;
    @Nullable
    private final String description;

    PropertyDescriptor(String prompt, String name, PropertyType type,
            @Immutable @Nullable Object defaultValue, boolean hidden,
            @Nullable String description) {
        this.prompt = prompt;
        this.name = name;
        this.type = type;
        this.defaultValue = defaultValue;
        this.hidden = hidden;
        this.description = description;
    }

    public String getPrompt() {
        return prompt;
    }

    public String getName() {
        return name;
    }

    public PropertyType getType() {
        return type;
    }

    @Immutable
    @Nullable
    public Object getDefault() {
        return defaultValue;
    }

    public boolean isHidden() {
        return hidden;
    }

    @Nullable
    public String getDescription() {
        return description;
    }

    public Class<?> getJavaClass() {
        if (type == PropertyType.STRING) {
            return String.class;
        } else if (type == PropertyType.BOOLEAN) {
            return Boolean.class;
        } else if (type == PropertyType.DOUBLE) {
            return Double.class;
        } else {
            logger.error("unexpected type: {}", type);
            return String.class;
        }
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("prompt", prompt)
                .add("name", name)
                .add("type", type)
                .add("default", defaultValue)
                .add("hidden", hidden)
                .toString();
    }

    @Immutable
    public enum PropertyType {
        STRING, BOOLEAN, DOUBLE;
    }
}
