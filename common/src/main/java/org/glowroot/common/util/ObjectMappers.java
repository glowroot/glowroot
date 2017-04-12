/*
 * Copyright 2013-2017 the original author or authors.
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
import java.lang.reflect.Type;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map.Entry;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.PrettyPrinter;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.jsonFormatVisitors.JsonFormatVisitorWrapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ContainerNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ser.std.NonTypedScalarSerializerBase;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.google.common.base.StandardSystemProperty;

public class ObjectMappers {

    public static final String NEWLINE;

    static {
        String newline = StandardSystemProperty.LINE_SEPARATOR.value();
        if (newline == null) {
            NEWLINE = "\n";
        } else {
            NEWLINE = newline;
        }
    }

    private ObjectMappers() {}

    public static ObjectMapper create(Module... extraModules) {
        SimpleModule module = new SimpleModule();

        module.addSerializer(boolean.class, new BooleanSerializer(Boolean.class));
        module.addSerializer(Enum.class, new EnumSerializer(Enum.class));
        module.setDeserializerModifier(new EnumDeserializerModifier());

        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(module);
        mapper.registerModule(new GuavaModule());
        for (Module extraModule : extraModules) {
            mapper.registerModule(extraModule);
        }
        mapper.setSerializationInclusion(Include.NON_ABSENT);
        return mapper;
    }

    public static PrettyPrinter getPrettyPrinter() {
        CustomPrettyPrinter prettyPrinter = new CustomPrettyPrinter();
        prettyPrinter.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
        return prettyPrinter;
    }

    public static void stripEmptyContainerNodes(ObjectNode objectNode) {
        Iterator<Entry<String, JsonNode>> i = objectNode.fields();
        while (i.hasNext()) {
            Entry<String, JsonNode> entry = i.next();
            JsonNode value = entry.getValue();
            if (value instanceof ContainerNode && ((ContainerNode<?>) value).size() == 0) {
                // remove empty nodes, e.g. unused "smtp" and "alerts" nodes
                i.remove();
            }
        }
    }

    // com.fasterxml.jackson.databind.ser.std.BooleanSerializer is final so cannot subclass
    // this is the same, plus implements isEmpty() to not write Boolean.FALSE
    @SuppressWarnings("serial")
    private static class BooleanSerializer extends NonTypedScalarSerializerBase<Boolean> {
        private BooleanSerializer(Class<Boolean> t) {
            super(t);
        }
        @Override
        public void serialize(Boolean value, JsonGenerator jgen, SerializerProvider provider)
                throws IOException {
            jgen.writeBoolean(value.booleanValue());
        }
        @Override
        public JsonNode getSchema(SerializerProvider provider, Type typeHint) {
            return createSchemaNode("boolean", true);
        }
        @Override
        public void acceptJsonFormatVisitor(JsonFormatVisitorWrapper visitor, JavaType typeHint)
                throws JsonMappingException {
            if (visitor != null) {
                visitor.expectBooleanFormat(typeHint);
            }
        }
        @Override
        public boolean isEmpty(SerializerProvider provider, Boolean value) {
            return value == null || !value;
        }
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

    @SuppressWarnings("serial")
    private static class CustomPrettyPrinter extends DefaultPrettyPrinter {

        @Override
        public void writeObjectFieldValueSeparator(JsonGenerator jg) throws IOException {
            jg.writeRaw(": ");
        }
    }
}
