/*
 * Copyright 2014-2017 the original author or authors.
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
package org.glowroot.ui;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.google.common.base.CaseFormat;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import static com.google.common.base.Preconditions.checkNotNull;

class QueryStrings {

    private static LoadingCache<Class<?>, Map<String, Method>> settersCache =
            CacheBuilder.newBuilder().build(new SettersCacheBuilder());

    private QueryStrings() {}

    static <T> /*@NonNull*/ T decode(Map<String, List<String>> queryParameters, Class<T> clazz)
            throws Exception {
        Class<?> immutableClass = getImmutableClass(clazz);
        Method builderMethod = immutableClass.getDeclaredMethod("builder");
        Object builder = builderMethod.invoke(null);
        checkNotNull(builder);
        Class<?> immutableBuilderClass = builder.getClass();
        Map<String, Method> setters = settersCache.getUnchecked(immutableBuilderClass);
        for (Entry<String, List<String>> entry : queryParameters.entrySet()) {
            String key = entry.getKey();
            key = CaseFormat.LOWER_HYPHEN.to(CaseFormat.LOWER_CAMEL, key);
            // special rule for "-mbean" so that it will convert to "...MBean"
            key = key.replace("Mbean", "MBean");
            Method setter = setters.get(key);
            checkNotNull(setter, "Unexpected attribute: %s", key);
            Type valueType = setter.getGenericParameterTypes()[0];
            Object value;
            if (valueType instanceof ParameterizedType) {
                // only generic iterable supported
                valueType = ((ParameterizedType) valueType).getActualTypeArguments()[0];
                List<Object> parsedValues = Lists.newArrayList();
                for (String stringValue : entry.getValue()) {
                    Object parsedValue = parseString(stringValue, (Class<?>) valueType);
                    // ignore empty query param values, e.g. the empty percentile value in
                    // percentile=&percentile=95&percentile=99
                    if (parsedValue != null) {
                        parsedValues.add(parsedValue);
                    }
                }
                value = parsedValues;
            } else {
                value = parseString(entry.getValue().get(0), (Class<?>) valueType);
            }
            setter.invoke(builder, value);
        }
        Method build = immutableBuilderClass.getDeclaredMethod("build");
        @SuppressWarnings("unchecked")
        T decoded = (T) build.invoke(builder);
        return checkNotNull(decoded);
    }

    static <T> Class<?> getImmutableClass(Class<T> clazz) throws ClassNotFoundException {
        String prefix = "";
        Package pkg = clazz.getPackage();
        if (pkg != null) {
            prefix = pkg.getName() + '.';
        }
        String immutableClassName = prefix + "Immutable" + clazz.getSimpleName();
        return Class.forName(immutableClassName, false, clazz.getClassLoader());
    }

    private static @Nullable Object parseString(String str, Class<?> targetClass) {
        if (targetClass == String.class) {
            return str;
        } else if (isInteger(targetClass)) {
            // parse as double and truncate, just in case there is a decimal part
            return (int) Double.parseDouble(str);
        } else if (isLong(targetClass)) {
            // parse as double and truncate, just in case there is a decimal part
            return (long) Double.parseDouble(str);
        } else if (isDouble(targetClass)) {
            return Double.parseDouble(str);
        } else if (isBoolean(targetClass)) {
            return Boolean.parseBoolean(str);
        } else if (Enum.class.isAssignableFrom(targetClass)) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Enum<?> enumValue = Enum.valueOf((Class<? extends Enum>) targetClass,
                    str.replace('-', '_').toUpperCase(Locale.ENGLISH));
            return enumValue;
        } else {
            throw new IllegalStateException("Unexpected class: " + targetClass);
        }
    }

    private static boolean isInteger(Class<?> targetClass) {
        return targetClass == int.class || targetClass == Integer.class;
    }

    private static boolean isLong(Class<?> targetClass) {
        return targetClass == long.class || targetClass == Long.class;
    }

    private static boolean isDouble(Class<?> targetClass) {
        return targetClass == double.class || targetClass == Double.class;
    }

    private static boolean isBoolean(Class<?> targetClass) {
        return targetClass == boolean.class || targetClass == Boolean.class;
    }

    private static class SettersCacheBuilder extends CacheLoader<Class<?>, Map<String, Method>> {
        @Override
        public Map<String, Method> load(Class<?> key) throws Exception {
            Map<String, Method> setters = Maps.newHashMap();
            for (Method method : key.getMethods()) {
                if (method.getName().startsWith("add") && !method.getName().startsWith("addAll")) {
                    continue;
                }
                if (method.getParameterTypes().length == 1) {
                    if (!isSimpleSetter(method.getParameterTypes()[0])) {
                        continue;
                    }
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

        private static boolean isSimpleSetter(Class<?> targetClass) {
            return targetClass == String.class
                    || isInteger(targetClass)
                    || isLong(targetClass)
                    || isDouble(targetClass)
                    || isBoolean(targetClass)
                    || Enum.class.isAssignableFrom(targetClass)
                    || targetClass == Iterable.class;
        }

    }
}
