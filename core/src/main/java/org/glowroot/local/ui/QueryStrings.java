/*
 * Copyright 2014-2015 the original author or authors.
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
package org.glowroot.local.ui;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.google.common.base.CaseFormat;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Maps;
import org.jboss.netty.handler.codec.http.QueryStringDecoder;

import org.glowroot.common.Reflections;

import static com.google.common.base.Preconditions.checkNotNull;

class QueryStrings {

    private static LoadingCache<Class<?>, Map<String, Method>> settersCache =
            CacheBuilder.newBuilder().build(new CacheLoader<Class<?>, Map<String, Method>>() {
                @Override
                public Map<String, Method> load(Class<?> key) throws Exception {
                    return loadSetters(key);
                }
            });

    private QueryStrings() {}

    static <T> T decode(String queryString, Class<T> clazz) throws Exception {
        String className = createImmutableClassName(clazz);
        Class<?> immutableClass = Class.forName(className);
        Method builderMethod = Reflections.getDeclaredMethod(immutableClass, "builder");
        Object builder = Reflections.invokeStatic(builderMethod);
        checkNotNull(builder);
        Class<?> immutableBuilderClass = builder.getClass();
        Map<String, Method> setters = settersCache.getUnchecked(immutableBuilderClass);
        QueryStringDecoder decoder = new QueryStringDecoder('?' + queryString);
        for (Entry<String, List<String>> entry : decoder.getParameters().entrySet()) {
            String key = entry.getKey();
            key = CaseFormat.LOWER_HYPHEN.to(CaseFormat.LOWER_CAMEL, key);
            // special rule for "-mbean" so that it will convert to "...MBean"
            key = key.replace("Mbean", "MBean");
            Method setter = setters.get(key);
            if (setter == null) {
                throw new IllegalArgumentException("Unexpected attribute: " + key);
            }
            Class<?> valueClass = setter.getParameterTypes()[0];
            Object value;

            if (valueClass == Iterable.class) {
                // only lists of type string supported
                value = entry.getValue();
            } else {
                value = parseString(entry.getValue().get(0), valueClass);
            }
            Reflections.invoke(setter, builder, value);
        }
        Method build = Reflections.getDeclaredMethod(immutableBuilderClass, "build");
        @SuppressWarnings("unchecked")
        T decoded = (T) Reflections.invoke(build, builder);
        return decoded;
    }

    private static @Nullable Object parseString(String str, Class<?> targetClass) {
        if (str.equals("")) {
            return null;
        } else if (targetClass == String.class) {
            return str;
        } else if (targetClass == int.class || targetClass == Integer.class) {
            // parse as double and truncate, just in case there is a decimal part
            return (int) Double.parseDouble(str);
        } else if (targetClass == long.class || targetClass == Long.class) {
            // parse as double and truncate, just in case there is a decimal part
            return (long) Double.parseDouble(str);
        } else if (targetClass == double.class || targetClass == Double.class) {
            return Double.parseDouble(str);
        } else if (targetClass == boolean.class || targetClass == Boolean.class) {
            return Boolean.parseBoolean(str);
        } else if (Enum.class.isAssignableFrom(targetClass)) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Enum<?> enumValue = Enum.valueOf((Class<? extends Enum>) targetClass,
                    str.toUpperCase(Locale.ENGLISH));
            return enumValue;
        } else {
            throw new IllegalStateException("Unexpected class: " + targetClass);
        }
    }

    private static <T> String createImmutableClassName(Class<T> clazz) {
        Package pkg = clazz.getPackage();
        if (pkg == null) {
            return "Immutable" + clazz.getSimpleName();
        } else {
            return pkg.getName() + ".Immutable" + clazz.getSimpleName();
        }
    }

    private static Map<String, Method> loadSetters(Class<?> immutableBuilderClass) {
        Map<String, Method> setters = Maps.newHashMap();
        for (Method method : immutableBuilderClass.getMethods()) {
            if (method.getName().startsWith("add") && !method.getName().startsWith("addAll")) {
                continue;
            }
            if (method.getParameterTypes().length == 1) {
                method.setAccessible(true);
                if (method.getName().startsWith("addAll")) {
                    String propertyName = method.getName().substring(6);
                    propertyName = Character.toLowerCase(propertyName.charAt(0))
                            + propertyName.substring(1);
                    setters.put(propertyName, method);
                } else {
                    setters.put(method.getName(), method);
                }
            }
        }
        return setters;
    }
}
