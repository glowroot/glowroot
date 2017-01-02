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
package org.glowroot.agent.plugin.servlet;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentMap;

import javax.annotation.Nullable;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.MapMaker;
import com.google.common.collect.Maps;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.Logger;

class Beans {

    private static final Logger logger = Agent.getLogger(Beans.class);

    // sentinel method is used to represent null value in the weak valued ConcurrentMap below
    // using guava's Optional would make the weakness on the Optional instance instead of on the
    // Method instance which would cause unnecessary clearing of the map values
    private static final Method SENTINEL_METHOD;

    static {
        try {
            SENTINEL_METHOD = Beans.class.getDeclaredMethod("sentinelMethod");
        } catch (Exception e) {
            // unrecoverable error
            throw new AssertionError(e);
        }
    }

    // note, not using nested loading cache since the nested loading cache maintains a strong
    // reference to the class loader
    //
    // weak keys in loading cache to prevent Class retention
    private static final LoadingCache<Class<?>, ConcurrentMap<String, AccessibleObject>> getters =
            CacheBuilder.newBuilder()
                    .weakKeys()
                    .build(new CacheLoader<Class<?>, ConcurrentMap<String, AccessibleObject>>() {
                        @Override
                        public ConcurrentMap<String, AccessibleObject> load(Class<?> clazz) {
                            // weak values since Method has a strong reference to its Class which
                            // is used as the key in the outer loading cache
                            return new MapMaker().weakValues().makeMap();
                        }
                    });

    // all getters for an individual class are only needed to handle wildcards at the end of a
    // session attribute path, e.g. "user.*"
    private static final LoadingCache<Class<?>, ImmutableMap<String, Method>> wildcardGetters =
            CacheBuilder.newBuilder().weakKeys().build(new WildcardGettersCacheLoader());

    private Beans() {}

    static @Nullable Object value(@Nullable Object obj, String path) {
        try {
            return valueInternal(obj, path);
        } catch (Exception e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            return "<could not access>";
        }
    }

    static Map<String, String> propertiesAsText(Object obj) {
        Map<String, String> properties = Maps.newHashMap();
        ImmutableMap<String, Method> allGettersForObj =
                wildcardGetters.getUnchecked(obj.getClass());
        for (Entry<String, Method> entry : allGettersForObj.entrySet()) {
            try {
                Object value = entry.getValue().invoke(obj);
                if (value != null) {
                    properties.put(entry.getKey(), value.toString());
                }
            } catch (Exception e) {
                // log exception at debug level
                logger.debug(e.getMessage(), e);
                properties.put(entry.getKey(), "<could not access>");
            }
        }
        return properties;
    }

    private static @Nullable Object valueInternal(@Nullable Object obj, String path)
            throws Exception {
        if (obj == null) {
            return null;
        }
        if (path.isEmpty()) {
            return obj;
        }
        int index = path.indexOf('.');
        String curr;
        String remaining;
        if (index == -1) {
            curr = path;
            remaining = "";
        } else {
            curr = path.substring(0, index);
            remaining = path.substring(index + 1);
        }
        if (obj instanceof Map) {
            return valueInternal(((Map<?, ?>) obj).get(curr), remaining);
        }
        AccessibleObject accessor = getAccessor(obj.getClass(), curr);
        if (accessor.equals(SENTINEL_METHOD)) {
            // no appropriate method found, dynamic paths that may or may not resolve
            // correctly are ok, just return null
            return null;
        }
        Object currItem = invoke(accessor, obj);
        return valueInternal(currItem, remaining);
    }

    private static AccessibleObject getAccessor(Class<?> clazz, String name) {
        ConcurrentMap<String, AccessibleObject> accessorsForType = getters.getUnchecked(clazz);
        AccessibleObject accessor = accessorsForType.get(name);
        if (accessor == null) {
            accessor = loadAccessor(clazz, name);
            accessor.setAccessible(true);
            accessorsForType.put(name, accessor);
        }
        return accessor;
    }

    private static AccessibleObject loadAccessor(Class<?> clazz, String name) {
        String capitalizedName = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        try {
            return getMethod(clazz, "get" + capitalizedName);
        } catch (Exception e) {
            // log exception at trace level
            logger.trace(e.getMessage(), e);
        }
        // fall back to "is" prefix
        try {
            return getMethod(clazz, "is" + capitalizedName);
        } catch (Exception f) {
            // log exception at trace level
            logger.trace(f.getMessage(), f);
        }
        // fall back to no prefix
        try {
            return getMethod(clazz, name);
        } catch (Exception g) {
            // log exception at trace level
            logger.trace(g.getMessage(), g);
        }
        // fall back to field access
        try {
            return getField(clazz, name);
        } catch (Exception h) {
            // log exception at trace level
            logger.trace(h.getMessage(), h);
        }
        // log general failure message at debug level
        logger.debug("no accessor found for {} in class {}", name, clazz.getName());
        return SENTINEL_METHOD;
    }

    private static @Nullable Object invoke(AccessibleObject accessor, Object obj) throws Exception {
        if (accessor instanceof Method) {
            return ((Method) accessor).invoke(obj);
        } else {
            return ((Field) accessor).get(obj);
        }
    }

    private static Method getMethod(Class<?> clazz, String methodName) throws Exception {
        try {
            return clazz.getMethod(methodName);
        } catch (NoSuchMethodException e) {
            // log exception at trace level
            logger.trace(e.getMessage(), e);
            return clazz.getDeclaredMethod(methodName);
        }
    }

    private static Field getField(Class<?> clazz, String fieldName) throws Exception {
        try {
            return clazz.getField(fieldName);
        } catch (NoSuchFieldException e) {
            // log exception at trace level
            logger.trace(e.getMessage(), e);
            return clazz.getDeclaredField(fieldName);
        }
    }

    // this unused private method is required for use as SENTINEL_METHOD above
    @SuppressWarnings("unused")
    private static void sentinelMethod() {}

    private static class WildcardGettersCacheLoader
            extends CacheLoader<Class<?>, ImmutableMap<String, Method>> {
        @Override
        public ImmutableMap<String, Method> load(Class<?> clazz) {
            Map<String, Method> propertyNames = Maps.newHashMap();
            for (Method method : clazz.getMethods()) {
                String propertyName = getPropertyName(method);
                if (propertyName == null) {
                    continue;
                }
                Method otherMethod = propertyNames.get(propertyName);
                if (otherMethod != null && otherMethod.getName().startsWith("get")) {
                    // "getX" takes precedence over "isX"
                    continue;
                }
                propertyNames.put(propertyName, method);
            }
            return ImmutableMap.copyOf(propertyNames);
        }

        private static @Nullable String getPropertyName(Method method) {
            if (method.getParameterTypes().length > 0) {
                return null;
            }
            String methodName = method.getName();
            if (methodName.equals("getClass")) {
                // ignore this "getter"
                return null;
            }
            if (startsWithAndThenUpperCaseChar(methodName, "get")) {
                return getRemainingWithFirstCharLowercased(methodName, "get");
            }
            if (startsWithAndThenUpperCaseChar(methodName, "is")) {
                return getRemainingWithFirstCharLowercased(methodName, "is");
            }
            return null;
        }

        private static boolean startsWithAndThenUpperCaseChar(String str, String prefix) {
            return str.startsWith(prefix) && str.length() > prefix.length()
                    && Character.isUpperCase(str.charAt(prefix.length()));
        }

        private static String getRemainingWithFirstCharLowercased(String str, String prefix) {
            return Character.toLowerCase(str.charAt(prefix.length()))
                    + str.substring(prefix.length() + 1);
        }
    }
}
