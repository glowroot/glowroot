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
package io.informant.testkit.internal;

import io.informant.api.Logger;
import io.informant.api.LoggerFactory;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import checkers.nullness.quals.Nullable;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// unfortunately this is an exact duplicate of a class from informant-core
// plugin-testkit cannot use the class from informant-core since sometimes that class exposes
// unshaded gson types (in IDE) and sometimes it exposes shaded gson types (in maven build)
public class GsonFactory {

    private static final Logger logger = LoggerFactory.getLogger(GsonFactory.class);

    private GsonFactory() {}

    public static Gson create() {
        return new GsonBuilder()
                .registerTypeAdapterFactory(new LowercaseEnumTypeAdapterFactory())
                .create();
    }

    public static GsonBuilder newBuilder() {
        return new GsonBuilder().registerTypeAdapterFactory(new LowercaseEnumTypeAdapterFactory());
    }

    // modified from http://google-gson.googlecode.com/
    // svn/trunk/gson/docs/javadocs/com/google/gson/TypeAdapterFactory.html
    private static class LowercaseEnumTypeAdapterFactory implements TypeAdapterFactory {
        @Nullable
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
            @SuppressWarnings("unchecked")
            Class<T> rawType = (Class<T>) type.getRawType();
            if (!rawType.isEnum()) {
                return null;
            }
            T[] enumConstants = rawType.getEnumConstants();
            if (enumConstants == null) {
                // this really shouldn't happen since isEnum() returns true above
                return null;
            }
            return new EnumTypeAdapter<T>(rawType, enumConstants);
        }
    }

    private static final class EnumTypeAdapter<T> extends TypeAdapter<T> {

        private final Class<T> rawType;
        private final Map<String, T> lowercaseNameToEnumConstant;

        private EnumTypeAdapter(Class<T> rawType, T[] enumConstants) {
            this.rawType = rawType;
            lowercaseNameToEnumConstant = Maps.newHashMap();
            for (T enumConstant : enumConstants) {
                lowercaseNameToEnumConstant.put(
                        ((Enum<?>) enumConstant).name().toLowerCase(Locale.ENGLISH), enumConstant);
            }
        }

        @Override
        public void write(JsonWriter out, @Nullable T value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.toString().toLowerCase(Locale.ENGLISH));
            }
        }

        @Override
        @Nullable
        public T read(JsonReader reader) throws IOException {
            if (reader.peek() == JsonToken.NULL) {
                reader.nextNull();
                return null;
            } else {
                String name = reader.nextString();
                @Nullable
                T enumConstant = lowercaseNameToEnumConstant.get(name);
                if (enumConstant == null) {
                    logger.warn("invalid enum value {} for enum: {}", name, rawType);
                }
                return enumConstant;
            }
        }
    }
}
