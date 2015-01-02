/*
 * Copyright 2014-2015 the original author or authors.
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

import java.io.IOException;
import java.util.Locale;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import org.glowroot.api.weaving.MethodModifier;

public class MarshalingRoutines {

    public static <T extends Enum<T> & LowercaseMarshaling> /*@NonNull*/T unmarshal(
            JsonParser parser, @SuppressWarnings("unused") @Nullable Enum<T> instanceNull,
            Class</*@NonNull*/T> expectedClass) throws IOException {
        return Enum.valueOf(expectedClass,
                parser.getText().replace('-', '_').toUpperCase(Locale.ENGLISH));
    }

    public static <T extends Enum<T> & LowercaseMarshaling> void marshal(JsonGenerator generator,
            T instance) throws IOException {
        generator.writeString(instance.name().replace('_', '-').toLowerCase(Locale.ENGLISH));
    }

    public static MethodModifier unmarshal(JsonParser parser,
            @SuppressWarnings("unused") @Nullable MethodModifier instanceNull,
            Class<MethodModifier> expectedClass) throws IOException {
        return MethodModifier.valueOf(expectedClass, parser.getText().toUpperCase(Locale.ENGLISH));
    }

    public static void marshal(JsonGenerator generator, MethodModifier instance)
            throws IOException {
        generator.writeString(instance.name().toLowerCase(Locale.ENGLISH));
    }

    public static PropertyValue unmarshal(JsonParser parser,
            @SuppressWarnings("unused") @Nullable PropertyValue instanceNull,
            @SuppressWarnings("unused") Class<PropertyValue> expectedClass) throws IOException {
        JsonToken token = parser.getCurrentToken();
        switch (token) {
            case VALUE_NULL:
                return new PropertyValue(null);
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

    public static void marshal(JsonGenerator generator, PropertyValue instance)
            throws IOException {
        Object value = instance.value();
        if (value == null) {
            generator.writeNull();
        } else if (value instanceof Boolean) {
            generator.writeBoolean((Boolean) value);
        } else if (value instanceof String) {
            generator.writeString((String) value);
        } else if (value instanceof Double) {
            generator.writeNumber((Double) value);
        } else {
            throw new AssertionError("Unexpected property value type: "
                    + value.getClass().getName());
        }
    }

    static Multiline unmarshal(JsonParser parser,
            @SuppressWarnings("unused") @Nullable Multiline instanceNull,
            @SuppressWarnings("unused") Class<Multiline> expectedClass) throws IOException {
        JsonToken token = parser.getCurrentToken();
        if (token == JsonToken.VALUE_STRING) {
            return new Multiline(parser.getValueAsString());
        }
        if (token == JsonToken.START_ARRAY) {
            StringBuilder sb = new StringBuilder();
            while (parser.nextToken() == JsonToken.VALUE_STRING) {
                sb.append(parser.getValueAsString());
            }
            if (parser.getCurrentToken() == JsonToken.END_ARRAY) {
                return new Multiline(sb.toString());
            } else {
                throw new IOException("Unexpected token when binding data into multi-line string: "
                        + token);
            }
        } else {
            throw new IOException("Unexpected token when binding data into multi-line string: "
                    + token);
        }
    }

    public interface LowercaseMarshaling {}

    // for binding either string or array of strings joined into a single string
    static class Multiline {

        private final String string;

        static Multiline of(String string) {
            return new Multiline(string);
        }

        private Multiline(String string) {
            this.string = string;
        }

        @Override
        public String toString() {
            return string;
        }
    }
}
