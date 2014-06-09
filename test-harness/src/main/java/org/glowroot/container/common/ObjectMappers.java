/*
 * Copyright 2013-2014 the original author or authors.
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
package org.glowroot.container.common;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.Deserializers;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// unfortunately this is mostly a duplicate of a class from the glowroot module test-container
// cannot use the class from the glowroot module since sometimes that class exposes unshaded jackson
// types (in IDE) and sometimes it exposes shaded jackson types (in maven build)
public class ObjectMappers {

    private static final Logger logger = LoggerFactory.getLogger(ObjectMappers.class);

    private ObjectMappers() {}

    public static ObjectMapper create() {
        return new ObjectMapper().registerModule(EnumModule.create());
    }

    public static <T extends /*@Nullable*/Object> T readRequiredValue(ObjectMapper mapper,
            String content, Class<T> type) throws IOException {
        T value = mapper.readValue(content, type);
        if (value == null) {
            throw new JsonMappingException("Content is json null");
        }
        return value;
    }

    public static JsonNode getRequiredChildNode(JsonNode parentNode, String fieldName)
            throws IOException {
        JsonNode node = parentNode.get(fieldName);
        if (node == null) {
            throw new JsonMappingException("Missing required field: " + fieldName);
        }
        if (node.isNull()) {
            throw new JsonMappingException("Required field is json null: " + fieldName);
        }
        return node;
    }

    /*@EnsuresNonNull("#1")*/
    public static <T extends /*@Nullable*/Object> void checkRequiredProperty(T reference,
            String fieldName) throws JsonMappingException {
        if (reference == null) {
            throw new JsonMappingException("Null value not allowed for field: " + fieldName);
        }
    }

    // named after guava Strings.nullToEmpty
    public static <T> List<T> nullToEmpty(@Nullable List<T> list) {
        if (list == null) {
            return Lists.newArrayList();
        } else {
            return list;
        }
    }

    // named after guava Strings.nullToEmpty
    public static <K, V extends /*@Nullable*/Object> Map<K, V> nullToEmpty(
            @Nullable Map<K, V> map) {
        if (map == null) {
            return Maps.newHashMap();
        } else {
            return map;
        }
    }

    // named after guava Strings.nullToEmpty
    public static boolean nullToFalse(@Nullable Boolean value) {
        return value == null ? false : value;
    }

    @SuppressWarnings("serial")
    private static class EnumModule extends SimpleModule {
        private static EnumModule create() {
            EnumModule module = new EnumModule();
            module.addSerializer(Enum.class, new EnumSerializer());
            return module;
        }
        @Override
        public void setupModule(SetupContext context) {
            super.setupModule(context);
            context.addDeserializers(new Deserializers.Base() {
                @Override
                public EnumDeserializer findEnumDeserializer(Class<?> type,
                        DeserializationConfig config, BeanDescription beanDesc)
                        throws JsonMappingException {
                    return new EnumDeserializer(type);
                }
            });
        }
    }

    private static class EnumSerializer extends JsonSerializer<Object> {
        @Override
        public void serialize(@Nullable Object value, JsonGenerator jgen,
                SerializerProvider provider) throws IOException {
            if (value == null) {
                jgen.writeNull();
            } else if (value instanceof Enum) {
                jgen.writeString(((Enum<?>) value).name().toLowerCase(Locale.ENGLISH));
            } else {
                logger.error("unexpected value class: {}", value.getClass());
            }
        }
    }

    private static class EnumDeserializer extends JsonDeserializer<Enum<?>> {

        private final Class<?> enumClass;
        private final ImmutableMap<String, Enum<?>> enumMap;

        public EnumDeserializer(Class<?> enumClass) {
            this.enumClass = enumClass;
            if (enumClass.isEnum()) {
                ImmutableMap.Builder<String, Enum<?>> theEnumMap = ImmutableMap.builder();
                Object[] enumConstants = enumClass.getEnumConstants();
                if (enumConstants != null) {
                    for (Object enumConstant : enumConstants) {
                        if (enumConstant instanceof Enum) {
                            Enum<?> constant = (Enum<?>) enumConstant;
                            theEnumMap.put(constant.name().toLowerCase(Locale.ENGLISH), constant);
                        } else {
                            logger.error("unexpected constant class: {}", enumConstant.getClass());
                        }
                    }
                }
                this.enumMap = theEnumMap.build();
            } else {
                logger.error("unexpected class: {}", enumClass);
                this.enumMap = ImmutableMap.of();
            }
        }

        @Override
        @Nullable
        public Enum<?> deserialize(JsonParser jp, DeserializationContext ctxt) throws IOException {
            String text = jp.getText();
            Enum<?> constant = enumMap.get(text);
            if (constant == null) {
                logger.warn("enum constant {} not found in enum type {}", text,
                        enumClass.getName());
            }
            return constant;
        }
    }
}
