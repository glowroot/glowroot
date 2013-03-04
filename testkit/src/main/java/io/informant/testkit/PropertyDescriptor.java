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
package io.informant.testkit;

import checkers.nullness.quals.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Objects;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class PropertyDescriptor {

    @JsonProperty
    @Nullable
    private String name;
    @JsonProperty
    @Nullable
    private PropertyType type;
    // default is java reserved word
    @JsonProperty("default")
    @Nullable
    private Object defaultValue;
    @JsonProperty
    private boolean hidden;
    @JsonProperty
    @Nullable
    private String prompt;
    @JsonProperty
    @Nullable
    private String description;

    @Nullable
    String getName() {
        return name;
    }

    @Nullable
    PropertyType getType() {
        return type;
    }

    @Nullable
    Object getDefaultValue() {
        return defaultValue;
    }

    boolean isHidden() {
        return hidden;
    }

    @Nullable
    String getPrompt() {
        return prompt;
    }

    @Nullable
    String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("name", name)
                .add("type", type)
                .add("default", defaultValue)
                .add("hidden", hidden)
                .add("prompt", prompt)
                .add("description", description)
                .toString();
    }

    enum PropertyType {
        STRING, BOOLEAN, DOUBLE;
    }
}
