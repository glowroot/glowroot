/*
 * Copyright 2013-2015 the original author or authors.
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
package org.glowroot.common;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.google.common.collect.ImmutableList;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;

public class ObjectMappers {

    private ObjectMappers() {}

    public static ObjectMapper create() {
        ObjectMapper mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.setDeserializerModifier(new EnumDeserializerModifier());
        module.addSerializer(Enum.class, new EnumSerializer(Enum.class));
        mapper.registerModule(module);
        mapper.registerModule(new GuavaModule());
        return mapper;
    }

    public static <T> T readRequiredValue(ObjectMapper mapper, String content, Class<T> valueType)
            throws IOException {
        T value = mapper.readValue(content, valueType);
        if (value == null) {
            throw new JsonMappingException("Content is json null");
        }
        return value;
    }

    public static <T extends /*@NonNull*/Object> T readRequiredValue(ObjectMapper mapper,
            String content, TypeReference<T> valueTypeRef) throws IOException {
        T value = mapper.readValue(content, valueTypeRef);
        if (value == null) {
            throw new JsonMappingException("Content is json null");
        }
        return value;
    }

    @EnsuresNonNull("#1")
    public static <T> void checkRequiredProperty(T reference, String fieldName)
            throws JsonMappingException {
        if (reference == null) {
            throw new JsonMappingException("Null value not allowed for field: " + fieldName);
        }
    }

    @SuppressWarnings("return.type.incompatible")
    public static <T> List</*@NonNull*/T> orEmpty(@Nullable List<T> list, String fieldName)
            throws JsonMappingException {
        if (list == null) {
            return ImmutableList.of();
        }
        for (T item : list) {
            if (item == null) {
                throw new JsonMappingException(
                        "Null items are not allowed in array field: " + fieldName);
            }
        }
        return list;
    }

    @SuppressWarnings({"rawtypes", "serial"})
    private static class EnumSerializer extends StdSerializer<Enum> {
        private EnumSerializer(Class<Enum> t) {
            super(t);
        }
        @Override
        public void serialize(Enum value, JsonGenerator jgen, SerializerProvider provider)
                throws IOException {
            jgen.writeString(value.name().replace('_', '-').toLowerCase(Locale.ENGLISH));
        }
    }

    private static class EnumDeserializerModifier extends BeanDeserializerModifier {
        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public JsonDeserializer<Enum> modifyEnumDeserializer(DeserializationConfig config,
                final JavaType type, BeanDescription beanDesc,
                final JsonDeserializer<?> deserializer) {
            return new JsonDeserializer<Enum>() {
                @Override
                public Enum<?> deserialize(JsonParser jp, DeserializationContext ctxt)
                        throws IOException {
                    Class<? extends Enum> rawClass = (Class<Enum>) type.getRawClass();
                    return Enum.valueOf(rawClass,
                            jp.getValueAsString().replace('-', '_').toUpperCase(Locale.ENGLISH));
                }
            };
        }
    }
}
