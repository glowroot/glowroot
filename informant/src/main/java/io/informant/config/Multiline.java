/**
 * Copyright 2013 the original author or authors.
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

import io.informant.config.Multiline.MultilineDeserializer;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// for binding either string or array of strings joined into a single string
@JsonDeserialize(using = MultilineDeserializer.class)
class Multiline {

    private final String joined;

    Multiline(String s) {
        this.joined = s;
    }

    String getJoined() {
        return joined;
    }

    static class MultilineDeserializer extends JsonDeserializer<Multiline> {
        @Override
        public Multiline deserialize(JsonParser jp, DeserializationContext ctxt)
                throws JsonProcessingException, IOException {
            JsonToken token = jp.getCurrentToken();
            if (token == JsonToken.VALUE_STRING) {
                return new Multiline(jp.getValueAsString());
            }
            if (token == JsonToken.START_ARRAY) {
                StringBuilder sb = new StringBuilder();
                while (jp.nextToken() == JsonToken.VALUE_STRING) {
                    sb.append(jp.getValueAsString());
                }
                if (jp.getCurrentToken() == JsonToken.END_ARRAY) {
                    return new Multiline(sb.toString());
                } else {
                    throw ctxt.mappingException(
                            "Unexpected token when binding data into multi-line string: " + token);
                }
            } else {
                throw ctxt.mappingException(
                        "Unexpected token when binding data into multi-line string: " + token);
            }
        }
    }
}
