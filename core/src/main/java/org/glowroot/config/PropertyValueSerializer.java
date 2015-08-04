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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

class PropertyValueSerializer extends JsonSerializer<PropertyValue> {

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
