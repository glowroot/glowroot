/*
 * Copyright 2012-2014 the original author or authors.
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
package org.glowroot.api;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentMap;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.MapMaker;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class Beans {

    private static final Logger logger = LoggerFactory.getLogger(Beans.class);

    // sentinel method is used to represent null value in the weak valued ConcurrentMap below
    // using guava's Optional would make the weakness on the Optional instance instead of on the
    // Method instance which would cause unnecessary clearing of the map values
    private static final Method SENTINEL_METHOD;

    static {
        try {
            SENTINEL_METHOD = Beans.class.getDeclaredMethod("sentinelMethod");
        } catch (NoSuchMethodException e) {
            // unrecoverable error
            throw new AssertionError(e);
        } catch (SecurityException e) {
            // unrecoverable error
            throw new AssertionError(e);
        }
    }

    // note, not using nested loading cache since the nested loading cache maintains a strong
    // reference to the class loader
    //
    // weak keys in loading cache to prevent Class retention
    private static final LoadingCache<Class<?>, ConcurrentMap<String, AccessibleObject>> getters =
            CacheBuilder.newBuilder().weakKeys()
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
            CacheBuilder.newBuilder().weakKeys()
                    .build(new CacheLoader<Class<?>, ImmutableMap<String, Method>>() {
                        @Override
                        public ImmutableMap<String, Method> load(Class<?> clazz) {
                            return getPropertyNames(clazz);
                        }
                    });

    private Beans() {}

    @Nullable
    public static Object value(@Nullable Object obj, String path) {
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
            return value(((Map<?, ?>) obj).get(curr), remaining);
        }
        try {
            AccessibleObject accessor = getAccessor(obj.getClass(), curr);
            if (accessor.equals(SENTINEL_METHOD)) {
                // no appropriate method found, dynamic paths that may or may not resolve
                // correctly are ok, just return null
                return null;
            }
            Object currItem;
            if (accessor instanceof Method) {
                currItem = ((Method) accessor).invoke(obj);
            } else {
                currItem = ((Field) accessor).get(obj);
            }
            return value(currItem, remaining);
        } catch (IllegalAccessException e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            return "<could not access>";
        } catch (IllegalArgumentException e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            return "<could not access>";
        } catch (InvocationTargetException e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            return "<could not access>";
        }
    }

    // don't expose guava ImmutableMap in public api
    public static Map<String, String> propertiesAsText(Object obj) {
        ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
        ImmutableMap<String, Method> allGettersForObj = wildcardGetters
                .getUnchecked(obj.getClass());
        for (Entry<String, Method> entry : allGettersForObj.entrySet()) {
            try {
                Object value = entry.getValue().invoke(obj);
                if (value != null) {
                    builder.put(entry.getKey(), value.toString());
                }
            } catch (IllegalAccessException e) {
                // log exception at debug level
                logger.debug(e.getMessage(), e);
                builder.put(entry.getKey(), "<could not access>");
            } catch (IllegalArgumentException e) {
                // log exception at debug level
                logger.debug(e.getMessage(), e);
                builder.put(entry.getKey(), "<could not access>");
            } catch (InvocationTargetException e) {
                // log exception at debug level
                logger.debug(e.getMessage(), e);
                builder.put(entry.getKey(), "<could not access>");
            }
        }
        return builder.build();
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
            // TODO getMethod will only find public methods, but the problem with using
            // getDeclaredMethod() is that it will miss public methods in super classes
            return getMethod(clazz, "get" + capitalizedName);
        } catch (ReflectiveException e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            // fall back to "is" prefix
            try {
                return getMethod(clazz, "is" + capitalizedName);
            } catch (ReflectiveException f) {
                // log exception at debug level
                logger.debug(f.getMessage(), f);
                // fall back to no prefix
                try {
                    return getMethod(clazz, name);
                } catch (ReflectiveException g) {
                    // log exception at debug level
                    logger.debug(g.getMessage(), g);
                    // fall back to field access
                    try {
                        // TODO getDeclaredField will miss fields in super classes
                        return clazz.getDeclaredField(name);
                    } catch (NoSuchFieldException h) {
                        // log exception at debug level
                        logger.debug("no accessor found for {} in class {}", name, clazz.getName(),
                                h);
                        return SENTINEL_METHOD;
                    } catch (SecurityException h) {
                        // log exception at debug level
                        logger.debug("no accessor found for {} in class {}", name, clazz.getName(),
                                h);
                        return SENTINEL_METHOD;
                    }
                }
            }
        }
    }

    private static ImmutableMap<String, Method> getPropertyNames(Class<?> clazz) {
        ImmutableMap.Builder<String, Method> builder = ImmutableMap.builder();
        for (Method method : clazz.getMethods()) {
            String propertyName = getPropertyName(method);
            if (propertyName != null) {
                builder.put(propertyName, method);
            }
        }
        return builder.build();
    }

    @Nullable
    private static String getPropertyName(Method method) {
        if (method.getParameterTypes().length > 0) {
            return null;
        }
        String methodName = method.getName();
        if (methodName.equals("getClass")) {
            // ignore this "getter"
            return null;
        }
        if (methodName.startsWith("get") && methodName.length() > 3) {
            return Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
        }
        if (methodName.startsWith("is") && methodName.length() > 2) {
            return Character.toLowerCase(methodName.charAt(2)) + methodName.substring(3);
        }
        return null;
    }

    private static Method getMethod(Class<?> clazz, String methodName) throws ReflectiveException {
        try {
            return clazz.getMethod(methodName);
        } catch (NoSuchMethodException e) {
            throw new ReflectiveException(e);
        } catch (SecurityException e) {
            throw new ReflectiveException(e);
        }
    }

    // this unused private method is required for use as SENTINEL_METHOD above
    @SuppressWarnings("unused")
    private static void sentinelMethod() {}

    @SuppressWarnings("serial")
    private static class ReflectiveException extends Exception {
        private ReflectiveException(Exception cause) {
            super(cause);
        }
    }
}
