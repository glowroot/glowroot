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
package io.informant.local.trace;

import io.informant.api.Logger;
import io.informant.api.LoggerFactory;
import io.informant.api.Optional;

import java.io.IOException;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.google.gson.stream.JsonWriter;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class MessageDetailSerializer {

    private static final Logger logger = LoggerFactory.getLogger(MessageDetailSerializer.class);

    private final JsonWriter jw;

    MessageDetailSerializer(JsonWriter jw) {
        this.jw = jw;
    }

    void write(Map<?, ?> detail) throws IOException {
        jw.beginObject();
        for (Entry<?, ?> entry : detail.entrySet()) {
            Object key = entry.getKey();
            if (key instanceof String) {
                jw.name((String) key);
            } else if (key == null) {
                logger.warn("detail map has null key");
                jw.name("");
            } else {
                logger.warn("detail map has unexpected key type '{}'", key.getClass().getName());
                jw.name(key.toString());
            }
            write(entry.getValue());
        }
        jw.endObject();
    }

    private void write(@Nullable Object value) throws IOException {
        if (value == null) {
            jw.nullValue();
        } else if (value instanceof String) {
            jw.value((String) value);
        } else if (value instanceof Boolean) {
            jw.value((Boolean) value);
        } else if (value instanceof Double) {
            jw.value((Double) value);
        } else if (value instanceof Optional) {
            Optional<?> val = (Optional<?>) value;
            if (val.isPresent()) {
                write(val.get());
            } else {
                jw.nullValue();
            }
        } else if (value instanceof Map) {
            write((Map<?, ?>) value);
        } else {
            logger.warn("detail map has unexpected value type '{}'", value.getClass().getName());
            jw.value(value.toString());
        }
    }
}
