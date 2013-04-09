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
package io.informant.snapshot;

import java.io.IOException;
import java.util.Map;
import java.util.Map.Entry;

import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.api.Optional;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class DetailMapWriter {

    private static final Logger logger = LoggerFactory.getLogger(DetailMapWriter.class);

    private final JsonGenerator jg;

    DetailMapWriter(JsonGenerator jg) {
        this.jg = jg;
    }

    void write(@ReadOnly Map<String, ? extends /*@Nullable*/Object> detail) throws IOException {
        writeMap(detail);
    }

    private void writeMap(@ReadOnly Map<?, ?> detail) throws IOException,
            JsonGenerationException {
        jg.writeStartObject();
        for (Entry<?, ?> entry : detail.entrySet()) {
            Object key = entry.getKey();
            if (key instanceof String) {
                jg.writeFieldName((String) key);
            } else if (key == null) {
                // this map comes from plugin, so need extra defensive check
                logger.warn("detail map has null key");
                jg.writeFieldName("");
            } else {
                // this map comes from plugin, so need extra defensive check
                logger.warn("detail map has unexpected key type '{}'", key.getClass().getName());
                jg.writeFieldName(key.toString());
            }
            writeValue(entry.getValue());
        }
        jg.writeEndObject();
    }

    private void writeValue(@Nullable Object value) throws IOException {
        if (value == null) {
            jg.writeNull();
        } else if (value instanceof String) {
            jg.writeString((String) value);
        } else if (value instanceof Boolean) {
            jg.writeBoolean((Boolean) value);
        } else if (value instanceof Double) {
            jg.writeNumber((Double) value);
        } else if (value instanceof Optional) {
            Optional<?> val = (Optional<?>) value;
            if (val.isPresent()) {
                writeValue(val.get());
            } else {
                jg.writeNull();
            }
        } else if (value instanceof Map) {
            writeMap((Map<?, ?>) value);
        } else {
            logger.warn("detail map has unexpected value type '{}'", value.getClass().getName());
            jg.writeString(value.toString());
        }
    }
}
