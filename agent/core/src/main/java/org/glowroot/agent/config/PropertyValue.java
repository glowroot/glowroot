/*
 * Copyright 2014-2016 the original author or authors.
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
package org.glowroot.agent.config;

import java.io.IOException;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import org.glowroot.agent.config.PropertyValue.PropertyValueDeserializer;
import org.glowroot.agent.config.PropertyValue.PropertyValueSerializer;

@JsonSerialize(using = PropertyValueSerializer.class)
@JsonDeserialize(using = PropertyValueDeserializer.class)
public class PropertyValue {

    // can be boolean, @Nullable Double or @NonNull String
    private final @Nullable Object value;

    static PropertyValue getDefaultValue(PropertyType type) {
        switch (type) {
            case BOOLEAN:
                return new PropertyValue(false);
            case DOUBLE:
                return new PropertyValue(null);
            case STRING:
                return new PropertyValue("");
            default:
                throw new AssertionError("Unexpected property type: " + type);
        }
    }

    public PropertyValue(@Nullable Object value) {
        this.value = value;
    }

    @Nullable
    Object value() {
        return value;
    }

    public enum PropertyType {
        STRING, BOOLEAN, DOUBLE
    }

    static class PropertyValueSerializer extends JsonSerializer<PropertyValue> {

        @Override
        public void serialize(PropertyValue propertyValue, JsonGenerator jgen,
                SerializerProvider provider) throws IOException {
            Object value = propertyValue.value();
            if (value == null) {
                jgen.writeNull();
            } else if (value instanceof Boolean) {
                jgen.writeBoolean((Boolean) value);
            } else if (value instanceof String) {
                jgen.writeString((String) value);
            } else if (value instanceof Double) {
                jgen.writeNumber((Double) value);
            } else {
                throw new AssertionError(
                        "Unexpected property value type: " + value.getClass().getName());
            }
        }
    }

    static class PropertyValueDeserializer extends JsonDeserializer<PropertyValue> {

        @Override
        public PropertyValue deserialize(JsonParser parser, DeserializationContext ctxt)
                throws IOException {
            JsonToken token = parser.getCurrentToken();
            switch (token) {
                case VALUE_FALSE:
                case VALUE_TRUE:
                    return new PropertyValue(parser.getBooleanValue());
                case VALUE_NUMBER_FLOAT:
                case VALUE_NUMBER_INT:
                    return new PropertyValue(parser.getDoubleValue());
                case VALUE_STRING:
                    return new PropertyValue(parser.getText());
                default:
                    throw new AssertionError("Unexpected json type: " + token);
            }
        }

        @Override
        public PropertyValue getNullValue(DeserializationContext ctxt) {
            return new PropertyValue(null);
        }
    }
}
