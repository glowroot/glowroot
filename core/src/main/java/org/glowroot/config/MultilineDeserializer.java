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

class MultilineDeserializer extends JsonDeserializer<Multiline> {

    @Override
    public Multiline deserialize(JsonParser parser, DeserializationContext ctx) throws IOException {
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
}
