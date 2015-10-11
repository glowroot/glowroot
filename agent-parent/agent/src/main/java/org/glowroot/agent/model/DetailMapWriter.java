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
package org.glowroot.agent.model;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static com.google.common.base.Preconditions.checkNotNull;

class DetailMapWriter {

    private static final Logger logger = LoggerFactory.getLogger(DetailMapWriter.class);

    private static final String UNSHADED_GUAVA_OPTIONAL_CLASS_NAME;

    static {
        String className = Optional.class.getName();
        if (className.startsWith("org.glowroot.agent.shaded")) {
            className = className.replace("org.glowroot.agent.shaded", "com");
        }
        UNSHADED_GUAVA_OPTIONAL_CLASS_NAME = className;
    }

    private DetailMapWriter() {}

    static List<Trace.DetailEntry> toProtobufDetail(
            Map<String, ? extends /*@Nullable*/Object> detail) {
        return writeMap(detail);
    }

    private static List<Trace.DetailEntry> writeMap(Map<?, ?> detail) {
        List<Trace.DetailEntry> entries = Lists.newArrayListWithCapacity(detail.size());
        for (Entry<?, ? extends /*@Nullable*/Object> entry : detail.entrySet()) {
            Object key = entry.getKey();
            if (key == null) {
                // skip invalid data
                logger.warn("detail map has null key");
                continue;
            }
            String name = key.toString();
            if (name == null) {
                // skip invalid data
                continue;
            }
            entries.add(createDetailEntry(name, entry.getValue()));
        }
        return entries;
    }

    private static Trace.DetailEntry createDetailEntry(String name, @Nullable Object value) {
        if (value instanceof Map) {
            return Trace.DetailEntry.newBuilder().setName(name)
                    .addAllChildEntry(writeMap((Map<?, ?>) value)).build();
        } else if (value instanceof List) {
            Trace.DetailEntry.Builder builder = Trace.DetailEntry.newBuilder().setName(name);
            for (Object v : (List<?>) value) {
                addValue(builder, v);
            }
            return builder.build();
        } else {
            // simple value
            Trace.DetailEntry.Builder builder = Trace.DetailEntry.newBuilder().setName(name);
            addValue(builder, value);
            return builder.build();
        }
    }

    private static void addValue(Trace.DetailEntry.Builder builder,
            @Nullable Object possiblyOptionalValue) {
        Object value = stripOptional(possiblyOptionalValue);
        if (value == null) {
            // add nothing (as a corollary, this will strip null/Optional.absent() items from lists)
        } else if (value instanceof String) {
            builder.addValueBuilder().setSval((String) value).build();
        } else if (value instanceof Boolean) {
            builder.addValueBuilder().setBval((Boolean) value).build();
        } else if (value instanceof Long) {
            builder.addValueBuilder().setLval((Long) value).build();
        } else if (value instanceof Number) {
            builder.addValueBuilder().setDval(((Number) value).doubleValue()).build();
        } else {
            logger.warn("detail map has unexpected value type: {}", value.getClass().getName());
            builder.addValueBuilder().setSval(Strings.nullToEmpty(value.toString())).build();
        }
    }

    private static @Nullable Object stripOptional(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Optional) {
            Optional<?> val = (Optional<?>) value;
            return val.orNull();
        }
        if (isUnshadedGuavaOptionalClass(value)) {
            // this is just for plugin tests that run against shaded glowroot-core
            Class<?> optionalClass = value.getClass().getSuperclass();
            // just tested that super class is not null in condition
            checkNotNull(optionalClass);
            try {
                Method orNullMethod = optionalClass.getMethod("orNull");
                return orNullMethod.invoke(value);
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
                return null;
            }
        }
        return value;
    }

    private static boolean isUnshadedGuavaOptionalClass(Object value) {
        Class<?> superClass = value.getClass().getSuperclass();
        return superClass != null
                && superClass.getName().equals(UNSHADED_GUAVA_OPTIONAL_CLASS_NAME);
    }
}
