/*
 * Copyright 2015 the original author or authors.
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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

class PropertyValueDeserializer extends JsonDeserializer<PropertyValue> {

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
    public PropertyValue getNullValue() {
        return new PropertyValue(null);
    }
}
