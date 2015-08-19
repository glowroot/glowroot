/*
 * Copyright 2012-2015 the original author or authors.
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
package org.glowroot.common.repo.helper;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.base.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;

class DetailMapWriter {

    private static final Logger logger = LoggerFactory.getLogger(DetailMapWriter.class);

    private static final String UNSHADED_GUAVA_OPTIONAL_CLASS_NAME;

    static {
        String className = Optional.class.getName();
        if (className.startsWith("org.glowroot.shaded")) {
            className = className.replace("org.glowroot.shaded", "com");
        }
        UNSHADED_GUAVA_OPTIONAL_CLASS_NAME = className;
    }

    private final JsonGenerator jg;

    DetailMapWriter(JsonGenerator jg) {
        this.jg = jg;
    }

    void write(Map<String, ? extends /*@Nullable*/Object> detail) throws IOException {
        writeMap(detail);
    }

    private void writeMap(Map<?, ?> detail) throws IOException {
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
                logger.warn("detail map has unexpected key type: {}", key.getClass().getName());
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
        } else if (value instanceof Number) {
            jg.writeNumber(((Number) value).doubleValue());
        } else if (value instanceof Optional) {
            Optional<?> val = (Optional<?>) value;
            writeValue(val.orNull());
        } else if (value instanceof Map) {
            writeMap((Map<?, ?>) value);
        } else if (value instanceof List) {
            jg.writeStartArray();
            for (Object v : (List<?>) value) {
                writeValue(v);
            }
            jg.writeEndArray();
        } else if (isUnshadedGuavaOptionalClass(value)) {
            // this is just for plugin tests that run against shaded glowroot-core
            Class<?> optionalClass = value.getClass().getSuperclass();
            // just tested that super class is not null in condition
            checkNotNull(optionalClass);
            try {
                Method orNullMethod = optionalClass.getMethod("orNull");
                writeValue(orNullMethod.invoke(value));
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        } else {
            logger.warn("detail map has unexpected value type: {}", value.getClass().getName());
            jg.writeString(value.toString());
        }
    }

    private static boolean isUnshadedGuavaOptionalClass(Object value) {
        Class<?> superClass = value.getClass().getSuperclass();
        return superClass != null
                && superClass.getName().equals(UNSHADED_GUAVA_OPTIONAL_CLASS_NAME);
    }
}
