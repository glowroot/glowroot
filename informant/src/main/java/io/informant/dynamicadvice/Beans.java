/*
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.dynamicadvice;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import checkers.nullness.quals.Nullable;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.MapMaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// TODO address duplication between this and the Beans class in io.informant.plugin.servlet
class Beans {

    private static final Logger logger = LoggerFactory.getLogger(Beans.class);

    // sentinel method is used to represent null value in the weak valued ConcurrentMap below
    // using guava's Optional would make the weakness on the Optional instance instead of on the
    // Method instance which would cause unnecessary clearing of the map values
    private static final Method SENTINEL_METHOD;

    static {
        try {
            SENTINEL_METHOD = Beans.class.getDeclaredMethod("sentinelMethod");
        } catch (SecurityException e) {
            throw new IllegalStateException("Unrecoverable error", e);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Unrecoverable error", e);
        }
    }

    // note, not using nested loading cache since the nested loading cache maintains a strong
    // reference to the class loader
    //
    // weak keys in loading cache to prevent Class retention
    private static final LoadingCache<Class<?>, ConcurrentMap<String, Method>> getters =
            CacheBuilder.newBuilder().weakKeys()
                    .build(new CacheLoader<Class<?>, ConcurrentMap<String, Method>>() {
                        @Override
                        public ConcurrentMap<String, Method> load(Class<?> type) {
                            // weak values since Method has a strong reference to its Class which
                            // is used as the key in the outer loading cache
                            return new MapMaker().weakValues().makeMap();
                        }
                    });

    private Beans() {}

    @Nullable
    static Object value(@Nullable Object obj, String[] path) {
        return value(obj, path, 0);
    }

    @Nullable
    private static Object value(@Nullable Object obj, String[] path, int currIndex) {
        if (obj == null) {
            return null;
        } else if (currIndex == path.length) {
            return obj;
        } else if (obj instanceof Map) {
            return value(((Map<?, ?>) obj).get(path[currIndex]), path, currIndex + 1);
        } else {
            try {
                Method getter = getGetter(obj.getClass(), path[currIndex]);
                if (getter.equals(SENTINEL_METHOD)) {
                    // no appropriate method found, dynamic paths that may or may not resolve
                    // correctly are ok, just return null
                    return null;
                }
                return value(getter.invoke(obj), path, currIndex + 1);
            } catch (IllegalAccessException e) {
                logger.debug(e.getMessage(), e);
                // this is less ok
                return "<could not access>";
            } catch (InvocationTargetException e) {
                logger.debug(e.getMessage(), e);
                // this is less ok
                return "<could not access>";
            }
        }
    }

    private static Method getGetter(Class<?> type, String name) {
        ConcurrentMap<String, Method> getterForType = getters.getUnchecked(type);
        Method method = getterForType.get(name);
        if (method == null) {
            method = loadGetter(type, name);
            getterForType.put(name, method);
        }
        return method;
    }

    private static Method loadGetter(Class<?> type, String name) {
        String capitalizedName = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        try {
            return type.getMethod("get" + capitalizedName);
        } catch (NoSuchMethodException e) {
            // fall back for "is" prefix
            try {
                return type.getMethod("is" + capitalizedName);
            } catch (NoSuchMethodException f) {
                // fall back to no prefix
                try {
                    return type.getMethod(name);
                } catch (NoSuchMethodException g) {
                    logger.debug("no appropriate getter found for property '{}' in class '{}'",
                            name, type.getName());
                    return SENTINEL_METHOD;
                }
            }
        }
    }

    // this unused private method is required for use as SENTINEL_METHOD above
    @SuppressWarnings("unused")
    private static void sentinelMethod() {}
}
