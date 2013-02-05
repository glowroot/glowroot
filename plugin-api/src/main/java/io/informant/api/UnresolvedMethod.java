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
package io.informant.api;

import java.lang.reflect.Method;
import java.util.Map;

import checkers.nullness.quals.Nullable;

import com.google.common.collect.MapMaker;

/**
 * Designed to be statically cached. A single UnresolvedMethod instance works across multiple class
 * loaders.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class UnresolvedMethod {

    private static final Logger logger = LoggerFactory.getLogger(UnresolvedMethod.class);

    // sentinel class loader and sentinel method are used to represent null keys and null values,
    // respectively, in the weak valued ConcurrentMap below
    // using guava's Optional would make the weakness on the Optional instance instead of on the
    // ClassLoader/Method instances which would cause unnecessary clearing of the map values
    private static final ClassLoader SENTINEL_CLASS_LOADER;
    private static final Method SENTINEL_METHOD;

    static {
        SENTINEL_CLASS_LOADER = new ClassLoader() {};
        try {
            SENTINEL_METHOD = UnresolvedMethod.class.getDeclaredMethod("sentinelMethod");
        } catch (SecurityException e) {
            throw new IllegalStateException("Unrecoverable error", e);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Unrecoverable error", e);
        }
    }

    private final String unresolvedClass;
    private final String name;
    private final Class<?>/*@Nullable*/[] parameterTypes;
    private final String/*@Nullable*/[] unresolvedParameterTypes;

    // for performance sensitive areas do not use guava's LoadingCache due to volatile write (via
    // incrementing an AtomicInteger) at the end of get() in LocalCache$Segment.postReadCleanup()
    //
    // weak keys and weak values to prevent retention of class loaders (weak values since Method
    // retains its ClassLoader)
    private final Map<ClassLoader, Method> resolvedMethods = new MapMaker().weakKeys().weakValues()
            .makeMap();

    public static UnresolvedMethod from(String unresolvedClass, String name) {
        return new UnresolvedMethod(unresolvedClass, name, new Class<?>[0], null);
    }

    public static UnresolvedMethod from(String unresolvedClass, String name,
            Class<?>... parameterTypes) {
        return new UnresolvedMethod(unresolvedClass, name, parameterTypes, null);
    }

    public static UnresolvedMethod from(String unresolvedClass, String name,
            String... unresolvedParameterTypes) {
        return new UnresolvedMethod(unresolvedClass, name, null, unresolvedParameterTypes);
    }

    private UnresolvedMethod(String unresolvedClass, String name,
            Class<?>/*@Nullable*/[] parameterTypes,
            String/*@Nullable*/[] unresolvedParameterTypes) {

        if (parameterTypes == null && unresolvedParameterTypes == null) {
            throw new NullPointerException("Constructor args 'parameterTypes' and"
                    + " 'unresolvedParameterTypes' cannot both be null (enforced by static factory"
                    + " methods)");
        }
        this.unresolvedClass = unresolvedClass;
        this.name = name;
        this.parameterTypes = parameterTypes;
        this.unresolvedParameterTypes = unresolvedParameterTypes;
    }

    @Nullable
    public Object invokeWithDefaultOnError(Object target, @Nullable Object defaultValue) {
        return invokeWithDefaultOnError(target, new Object[0], defaultValue);
    }

    @Nullable
    public Object invokeWithDefaultOnError(Object target, Object parameters,
            @Nullable Object defaultValue) {
        return invokeWithDefaultOnError(target, new Object[] { parameters }, defaultValue);
    }

    @Nullable
    public Object invokeWithDefaultOnError(Object target, Object[] parameters,
            @Nullable Object defaultValue) {
        Method method = getResolvedMethod(target.getClass().getClassLoader());
        if (method == null) {
            // warning has already been logged in getResolvedMethod()
            return defaultValue;
        }
        try {
            return invoke(method, target, parameters);
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
            return defaultValue;
        }
    }

    @Nullable
    public Object invokeStaticWithDefaultOnError(Object target, @Nullable Object defaultValue) {
        return invokeStaticWithDefaultOnError(target, new Object[0], defaultValue);
    }

    @Nullable
    public Object invokeStaticWithDefaultOnError(Object target, Object parameters,
            @Nullable Object defaultValue) {
        return invokeStaticWithDefaultOnError(target, new Object[] { parameters }, defaultValue);
    }

    @Nullable
    public Object invokeStaticWithDefaultOnError(@Nullable ClassLoader loader, Object[] parameters,
            @Nullable Object defaultValue) throws Exception {
        Method method = getResolvedMethod(loader);
        if (method == null) {
            // warning has already been logged in getResolvedMethod()
            return defaultValue;
        }
        try {
            return invoke(method, null, parameters);
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
            return defaultValue;
        }
    }

    @Nullable
    private Method getResolvedMethod(@Nullable ClassLoader loader) {
        ClassLoader key = loader;
        if (key == null) {
            key = SENTINEL_CLASS_LOADER;
        }
        Method method = resolvedMethods.get(key);
        if (method == null) {
            // just a cache, ok if two threads happen to load and store in parallel
            method = loadResolvedMethod(loader);
            resolvedMethods.put(key, method);
        }
        if (method == SENTINEL_METHOD) {
            return null;
        }
        return method;
    }

    private Method loadResolvedMethod(@Nullable ClassLoader loader) {
        try {
            Class<?> resolvedClass = Class.forName(unresolvedClass, false, loader);
            if (parameterTypes != null) {
                return resolvedClass.getMethod(name, parameterTypes);
            } else if (unresolvedParameterTypes == null) {
                throw new NullPointerException("Fields 'parameterTypes' and"
                        + " 'unresolvedParameterTypes' cannot both be null (enforced by static"
                        + " factory methods)");
            } else {
                Class<?>[] parameterTypes = new Class<?>[unresolvedParameterTypes.length];
                for (int i = 0; i < unresolvedParameterTypes.length; i++) {
                    parameterTypes[i] = Class.forName(unresolvedParameterTypes[i], false, loader);
                }
                return resolvedClass.getMethod(name, parameterTypes);
            }
        } catch (ClassNotFoundException e) {
            // this is only logged once because the sentinel method is returned and cached
            logger.warn(e.getMessage(), e);
            return SENTINEL_METHOD;
        } catch (SecurityException e) {
            // this is only logged once because the sentinel method is returned and cached
            logger.warn(e.getMessage(), e);
            return SENTINEL_METHOD;
        } catch (NoSuchMethodException e) {
            // this is only logged once because the sentinel method is returned and cached
            logger.warn(e.getMessage(), e);
            return SENTINEL_METHOD;
        }
    }

    @Nullable
    private Object invoke(Method method, @Nullable Object target, Object... parameters)
            throws Exception {
        try {
            return method.invoke(target, parameters);
        } catch (IllegalAccessException e) {
            logger.warn(e.getMessage(), e);
            throw e;
        }
    }

    // this unused private method is required for use as SENTINEL_METHOD above
    @SuppressWarnings("unused")
    private static void sentinelMethod() {}
}
