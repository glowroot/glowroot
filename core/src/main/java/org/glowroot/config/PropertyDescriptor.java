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

import com.google.common.collect.Ordering;
import org.immutables.value.Json;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.config.MarshalingRoutines.LowercaseMarshaling;
import org.glowroot.config.MarshalingRoutines.Multiline;

import static com.google.common.base.Preconditions.checkNotNull;

@Value.Immutable
@Json.Marshaled
@Json.Import({MarshalingRoutines.class})
public abstract class PropertyDescriptor {

    private static final Logger logger = LoggerFactory.getLogger(PropertyDescriptor.class);

    static final Ordering<PropertyDescriptor> orderingByName = new Ordering<PropertyDescriptor>() {
        @Override
        public int compare(@Nullable PropertyDescriptor left, @Nullable PropertyDescriptor right) {
            checkNotNull(left);
            checkNotNull(right);
            return left.name().compareToIgnoreCase(right.name());
        }
    };

    public abstract String name();
    public abstract PropertyType type();
    @Json.Named("default")
    abstract @Nullable PropertyValue defaultValue();
    @Value.Default
    public boolean hidden() {
        return false;
    }
    public abstract String label();
    @Value.Default
    public String checkboxLabel() {
        return "";
    }
    @Value.Default
    public Multiline description() {
        return Multiline.of("");
    }

    PropertyValue getValidatedNonNullDefaultValue() {
        PropertyValue defaultValue = defaultValue();
        if (defaultValue == null) {
            return PropertyValue.getDefaultValue(type());
        }
        Object value = defaultValue.value();
        if (value == null) {
            return PropertyValue.getDefaultValue(type());
        }
        if (isValidType(value, type())) {
            return new PropertyValue(value);
        } else {
            logger.warn("invalid default value for plugin property: {}", name());
            return PropertyValue.getDefaultValue(type());
        }
    }

    static boolean isValidType(Object value, PropertyType type) {
        switch (type) {
            case BOOLEAN:
                return value instanceof Boolean;
            case STRING:
                return value instanceof String;
            case DOUBLE:
                return value instanceof Double;
            default:
                throw new AssertionError("Unexpected property type: " + type);
        }
    }

    enum PropertyType implements LowercaseMarshaling {
        STRING, BOOLEAN, DOUBLE
    }
}
