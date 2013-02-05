/**
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
package io.informant.plugin.servlet;

import io.informant.api.Logger;
import io.informant.api.LoggerFactory;
import io.informant.shaded.google.common.cache.CacheBuilder;
import io.informant.shaded.google.common.cache.CacheLoader;
import io.informant.shaded.google.common.cache.LoadingCache;
import io.informant.shaded.google.common.collect.ImmutableMap;
import io.informant.shaded.google.common.collect.MapMaker;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentMap;

import checkers.nullness.quals.Nullable;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
final class Beans {

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

    // TODO not sure if there is a retention cycle between Method and its class, so using weak keys
    // and weak values for now
    //
    // note, not using nested loading cache since the nested loading cache maintains a strong
    // reference to the class loader
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

    // all getters for an individual class are only needed to handle wildcards at the end of a
    // session attribute path, e.g. "user.*"
    private static final LoadingCache<Class<?>, ImmutableMap<String, Method>> wildcardGetters =
            CacheBuilder.newBuilder().weakKeys()
                    .build(new CacheLoader<Class<?>, ImmutableMap<String, Method>>() {
                        @Override
                        public ImmutableMap<String, Method> load(Class<?> type) throws Exception {
                            return getPropertyNames(type);
                        }
                    });

    @Nullable
    static Object value(@Nullable Object obj, String[] path, int currIndex) {
        if (obj == null) {
            return null;
        } else if (currIndex == path.length) {
            return obj;
        } else if (obj instanceof Map) {
            return value(((Map<?, ?>) obj).get(path[currIndex]), path, currIndex + 1);
        } else {
            try {
                Method getter = getGetter(obj.getClass(), path[currIndex]);
                if (getter == SENTINEL_METHOD) {
                    // no appropriate method found, dynamic paths that may or may not resolve
                    // correctly are ok, just return null
                    return null;
                }
                return value(getter.invoke(obj), path, currIndex + 1);
            } catch (IllegalAccessException e) {
                logger.debug(e.getMessage(), e);
                // this is less ok (than invalid dynamic path above)
                return "<could not access>";
            } catch (InvocationTargetException e) {
                logger.debug(e.getMessage(), e);
                // this is less ok (than invalid dynamic path above)
                return "<could not access>";
            }
        }
    }

    static ImmutableMap<String, String> propertiesAsText(Object obj) {
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
                logger.debug(e.getMessage(), e);
                builder.put(entry.getKey(), "<could not access>");
            } catch (InvocationTargetException e) {
                logger.debug(e.getMessage(), e);
                builder.put(entry.getKey(), "<could not access>");
            }
        }
        return builder.build();
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

    private static ImmutableMap<String, Method> getPropertyNames(Class<?> type) {
        ImmutableMap.Builder<String, Method> builder = ImmutableMap.builder();
        for (Method method : type.getMethods()) {
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

    // this unused private method is required for use as SENTINEL_METHOD above
    @SuppressWarnings("unused")
    private static void sentinelMethod() {}

    private Beans() {}
}
