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
package org.glowroot.common.util;

import java.io.IOException;
import java.util.Locale;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.datatype.guava.GuavaModule;

public class ObjectMappers {

    private ObjectMappers() {}

    public static ObjectMapper create(Module... extraModules) {
        SimpleModule module = new SimpleModule();

        module.addSerializer(Enum.class, new EnumSerializer(Enum.class));
        module.setDeserializerModifier(new EnumDeserializerModifier());

        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(module);
        mapper.registerModule(new GuavaModule());
        for (Module extraModule : extraModules) {
            mapper.registerModule(extraModule);
        }
        return mapper;
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
