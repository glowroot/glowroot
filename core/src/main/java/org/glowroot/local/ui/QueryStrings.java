/*
 * Copyright 2014 the original author or authors.
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

import com.google.common.base.CaseFormat;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Maps;
import org.jboss.netty.handler.codec.http.QueryStringDecoder;

import org.glowroot.common.Reflections;
import org.glowroot.common.Reflections.ReflectiveException;

import static com.google.common.base.Preconditions.checkNotNull;

class QueryStrings {

    private static LoadingCache<Class<?>, Map<String, Method>> settersCache =
            CacheBuilder.newBuilder().build(new CacheLoader<Class<?>, Map<String, Method>>() {
                @Override
                public Map<String, Method> load(Class<?> key) throws Exception {
                    return loadSetters(key);
                }
            });

    static <T> T decode(String queryString, Class<T> clazz) throws ClassNotFoundException,
            ReflectiveException {
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
            List<String> values = entry.getValue();
            Object value;
            if (valueClass == Iterable.class) {
                // only lists of type string supported
                value = values;
            } else if (values.get(0).equals("")) {
                value = null;
            } else if (valueClass == String.class) {
                value = values.get(0);
            } else if (valueClass == int.class || valueClass == Integer.class) {
                value = Integer.parseInt(values.get(0));
            } else if (valueClass == long.class || valueClass == Long.class) {
                value = Long.parseLong(values.get(0));
            } else if (valueClass == boolean.class || valueClass == Boolean.class) {
                value = Boolean.parseBoolean(values.get(0));
            } else if (Enum.class.isAssignableFrom(valueClass)) {
                value = Enum.valueOf((Class<? extends Enum>) valueClass,
                        values.get(0).toUpperCase(Locale.ENGLISH));
            } else {
                throw new IllegalStateException("Unexpected class: " + valueClass);
            }
            Reflections.invoke(setter, builder, value);
        }
        Method build = Reflections.getMethod(immutableBuilderClass, "build");
        build.setAccessible(true);
        return (T) Reflections.invoke(build, builder);
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
