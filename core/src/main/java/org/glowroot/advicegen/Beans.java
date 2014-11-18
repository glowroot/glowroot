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
package org.glowroot.advicegen;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import javax.annotation.Nullable;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.MapMaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Beans {

    private static final Logger logger = LoggerFactory.getLogger(Beans.class);

    // sentinel method is used to represent null value in the weak valued ConcurrentMap below
    // using guava's Optional would make the weakness on the Optional instance instead of on the
    // Method instance which would cause unnecessary clearing of the map values
    private static final Accessor SENTINEL_ACCESSOR;

    static {
        try {
            SENTINEL_ACCESSOR =
                    Accessor.fromMethod(Beans.class.getDeclaredMethod("sentinelMethod"));
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
    private static final LoadingCache<Class<?>, ConcurrentMap<String, Accessor>> getters =
            CacheBuilder.newBuilder().weakKeys()
                    .build(new CacheLoader<Class<?>, ConcurrentMap<String, Accessor>>() {
                        @Override
                        public ConcurrentMap<String, Accessor> load(Class<?> clazz) {
                            // weak values since Method has a strong reference to its Class which
                            // is used as the key in the outer loading cache
                            return new MapMaker().weakValues().makeMap();
                        }
                    });

    private Beans() {}

    public static @Nullable Object value(@Nullable Object obj, String[] path) {
        return value(obj, path, 0);
    }

    public static @Nullable Object value(@Nullable Object obj, String[] path, int currIndex) {
        if (obj == null) {
            return null;
        }
        if (currIndex == path.length) {
            return obj;
        }
        String curr = path[currIndex];
        if (obj instanceof Map) {
            return value(((Map<?, ?>) obj).get(curr), path, currIndex + 1);
        }
        try {
            Accessor accessor = getAccessor(obj.getClass(), curr);
            if (accessor.equals(SENTINEL_ACCESSOR)) {
                // no appropriate method found, dynamic paths that may or may not resolve
                // correctly are ok, just return null
                return null;
            }
            Object currItem = accessor.evaluate(obj);
            return value(currItem, path, currIndex + 1);
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

    private static Accessor getAccessor(Class<?> clazz, String name) {
        ConcurrentMap<String, Accessor> accessorsForType = getters.getUnchecked(clazz);
        Accessor accessor = accessorsForType.get(name);
        if (accessor == null) {
            accessor = findAccessor(clazz, name);
            if (accessor == null) {
                accessor = SENTINEL_ACCESSOR;
            }
            accessorsForType.put(name, accessor);
        }
        return accessor;
    }

    public static @Nullable Accessor findAccessor(Class<?> clazz, String name) {
        if (clazz.getComponentType() != null && name.equals("length")) {
            return Accessor.arrayLength();
        }
        Class<?> componentType = clazz;
        while (componentType.getComponentType() != null) {
            componentType = componentType.getComponentType();
        }
        String capitalizedName = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        try {
            Method method = getMethod(componentType, "get" + capitalizedName);
            method.setAccessible(true);
            return Accessor.fromMethod(method);
        } catch (ReflectiveException e) {
            // log exception at trace level
            logger.trace(e.getMessage(), e);
            // fall back to "is" prefix
            try {
                Method method = getMethod(componentType, "is" + capitalizedName);
                method.setAccessible(true);
                return Accessor.fromMethod(method);
            } catch (ReflectiveException f) {
                // log exception at trace level
                logger.trace(f.getMessage(), f);
                // fall back to no prefix
                try {
                    Method method = getMethod(componentType, name);
                    method.setAccessible(true);
                    return Accessor.fromMethod(method);
                } catch (ReflectiveException g) {
                    // log exception at trace level
                    logger.trace(g.getMessage(), g);
                    // fall back to field access
                    try {
                        Field field = getField(componentType, name);
                        field.setAccessible(true);
                        return Accessor.fromField(field);
                    } catch (ReflectiveException h) {
                        // log exception at trace level
                        logger.trace(h.getMessage(), h);
                        // log general failure message at debug level
                        logger.debug("no accessor found for {} in class {}", name,
                                componentType.getName());
                        return null;
                    }
                }
            }
        }
    }

    private static Method getMethod(Class<?> clazz, String methodName) throws ReflectiveException {
        try {
            return clazz.getMethod(methodName);
        } catch (NoSuchMethodException e) {
            try {
                return clazz.getDeclaredMethod(methodName);
            } catch (SecurityException f) {
                // re-throw new exception (f)
                throw new ReflectiveException(f);
            } catch (NoSuchMethodException f) {
                // re-throw original exception (e)
                throw new ReflectiveException(e);
            }
        } catch (SecurityException e) {
            throw new ReflectiveException(e);
        }
    }

    private static Field getField(Class<?> clazz, String fieldName) throws ReflectiveException {
        try {
            return clazz.getField(fieldName);
        } catch (NoSuchFieldException e) {
            try {
                return clazz.getDeclaredField(fieldName);
            } catch (SecurityException f) {
                // re-throw new exception (f)
                throw new ReflectiveException(f);
            } catch (NoSuchFieldException f) {
                // re-throw original exception (e)
                throw new ReflectiveException(e);
            }
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
