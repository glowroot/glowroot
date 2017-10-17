/*
 * Copyright 2012-2017 the original author or authors.
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
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static com.google.common.base.Preconditions.checkNotNull;

public class DetailMapWriter {

    private static final Logger logger = LoggerFactory.getLogger(DetailMapWriter.class);

    private static final int MESSAGE_CHAR_LIMIT =
            Integer.getInteger("glowroot.message.char.limit", 100000);

    private static final String UNSHADED_GUAVA_OPTIONAL_CLASS_NAME;

    static {
        String className = Optional.class.getName();
        String shadingPrefix = "org.glowroot.agent.shaded.";
        if (className.startsWith(shadingPrefix)) {
            className = className.substring(shadingPrefix.length());
        }
        UNSHADED_GUAVA_OPTIONAL_CLASS_NAME = className;
    }

    private DetailMapWriter() {}

    public static List<Trace.DetailEntry> toProto(
            Map<String, ?> detail) {
        return writeMap(detail);
    }

    private static List<Trace.DetailEntry> writeMap(Map<?, ?> detail) {
        List<Trace.DetailEntry> entries = Lists.newArrayListWithCapacity(detail.size());
        for (Entry<?, ?> entry : detail.entrySet()) {
            Object key = entry.getKey();
            if (key == null) {
                // skip invalid data
                logger.warn("detail map has null key");
                continue;
            }
            String name;
            if (key instanceof String) {
                name = (String) key;
            } else {
                name = convertToStringAndTruncate(key);
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
            builder.addValueBuilder().setString((String) value).build();
        } else if (value instanceof Boolean) {
            builder.addValueBuilder().setBoolean((Boolean) value).build();
        } else if (value instanceof Long) {
            builder.addValueBuilder().setLong((Long) value).build();
        } else if (value instanceof Number) {
            builder.addValueBuilder().setDouble(((Number) value).doubleValue()).build();
        } else {
            logger.warn("detail map has unexpected value type: {}", value.getClass().getName());
            builder.addValueBuilder().setString(convertToStringAndTruncate(value)).build();
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
        if (isUnshadedGuavaOptional(value) || isGuavaOptionalInAnotherClassLoader(value)) {
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

    private static boolean isUnshadedGuavaOptional(Object value) {
        Class<?> superClass = value.getClass().getSuperclass();
        return superClass != null
                && superClass.getName().equals(UNSHADED_GUAVA_OPTIONAL_CLASS_NAME);
    }

    private static boolean isGuavaOptionalInAnotherClassLoader(Object value) {
        Class<?> superClass = value.getClass().getSuperclass();
        return superClass != null && superClass.getName().equals(Optional.class.getName());
    }

    // unexpected keys and values are not truncated in org.glowroot.agent.plugin.api.MessageImpl, so
    // need to be truncated here after converting them to strings
    private static String convertToStringAndTruncate(Object obj) {
        String str = obj.toString();
        if (str == null) {
            return "";
        }
        return truncate(str);
    }

    private static String truncate(String s) {
        if (s.length() <= MESSAGE_CHAR_LIMIT) {
            return s;
        } else {
            return s.substring(0, MESSAGE_CHAR_LIMIT) + " [truncated to " + MESSAGE_CHAR_LIMIT
                    + " characters]";
        }
    }
}
