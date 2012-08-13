/**
 * Copyright 2012 the original author or authors.
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
package org.informantproject.local.trace;

import java.io.IOException;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import org.informantproject.api.UnresolvedMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.gson.stream.JsonWriter;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class ContextMapSerializer {

    private static final Logger logger = LoggerFactory.getLogger(ContextMapSerializer.class);

    @Nullable
    private static final Class<?> SHADED_OPTIONAL_CLASS = getShadedOptionalClass();
    private static final UnresolvedMethod isPresentMethod = UnresolvedMethod.from(
            "org.informantproject.shaded.google.common.base.Optional", "isPresent");
    private static final UnresolvedMethod getMethod = UnresolvedMethod.from(
            "org.informantproject.shaded.google.common.base.Optional", "get");

    private final JsonWriter jw;

    ContextMapSerializer(JsonWriter jw) {
        this.jw = jw;
    }

    void write(Map<?, ?> contextMap) throws IOException {
        jw.beginObject();
        for (Entry<?, ?> entry : contextMap.entrySet()) {
            Object key = entry.getKey();
            if (key instanceof String) {
                jw.name((String) key);
            } else if (key == null) {
                logger.warn("context map has null key");
                jw.name("");
            } else {
                logger.warn("context map has unexpected key type '{}'", key.getClass().getName());
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
        } else if (SHADED_OPTIONAL_CLASS != null
                && SHADED_OPTIONAL_CLASS.isAssignableFrom(value.getClass())) {
            // this is hackery to make informant plugin unit tests (e.g. ServletPluginTest) pass
            // inside an IDE when running against unshaded informant-core
            //
            // informant plugins are compiled directly against shaded guava, so when they pass a
            // context map with a value of type Optional, it is the shaded Optional class
            boolean present = (Boolean) isPresentMethod.invoke(value);
            if (present) {
                write(getMethod.invoke(value));
            } else {
                jw.nullValue();
            }
        } else {
            logger.warn("context map has unexpected value type '{}'", value.getClass().getName());
            jw.value(value.toString());
        }
    }

    @Nullable
    private static Class<?> getShadedOptionalClass() {
        try {
            return Class.forName("org.informantproject.shaded.google.common.base.Optional");
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
}
