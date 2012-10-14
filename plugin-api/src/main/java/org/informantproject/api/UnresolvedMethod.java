/**
 * Copyright 2012 the original author or authors.
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
package org.informantproject.api;

import java.lang.reflect.Method;
import java.util.Map;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import com.google.common.collect.MapMaker;

/**
 * Designed to be statically cached. A single UnresolvedMethod instance works across multiple class
 * loaders.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class UnresolvedMethod {

    private static final Logger logger = LoggerFactory.getLogger(UnresolvedMethod.class);

    // sentinel method is used to represent null value in the weak valued ConcurrentMap below
    // using guava's Optional would make the weakness on the Optional instance instead of on the
    // Method instance which would cause unnecessary clearing of the map values
    private static final Method SENTINEL_METHOD;

    static {
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
    @Nullable
    private final Class<?>[] parameterTypes;
    @Nullable
    private final String[] unresolvedParameterTypes;

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
            @Nullable Class<?>[] parameterTypes, @Nullable String[] unresolvedParameterTypes) {

        this.unresolvedClass = unresolvedClass;
        this.name = name;
        this.parameterTypes = parameterTypes;
        this.unresolvedParameterTypes = unresolvedParameterTypes;
    }

    @Nullable
    public <T> T invokeWithDefaultOnError(Object target, T defaultValue) {
        return invokeWithDefaultOnError(target, new Object[0], defaultValue);
    }

    @Nullable
    public <T> T invokeWithDefaultOnError(Object target, Object parameters, T defaultValue) {
        return invokeWithDefaultOnError(target, new Object[] { parameters }, defaultValue);
    }

    @Nullable
    public <T> T invokeWithDefaultOnError(Object target, Object[] parameters, T defaultValue) {
        Method method = getResolvedMethod(target.getClass().getClassLoader());
        if (method == null) {
            // warning has already been logged in getResolvedMethod()
            return defaultValue;
        }
        try {
            return (T) invoke(method, target, parameters);
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
            return defaultValue;
        }
    }

    @Nullable
    public Object invokeStatic(ClassLoader loader, Object... parameters) throws Exception {
        Method method = getResolvedMethod(loader);
        return invoke(method, null, parameters);
    }

    @Nullable
    private Method getResolvedMethod(ClassLoader loader) {
        Method method = resolvedMethods.get(loader);
        if (method == null) {
            // just a cache, ok if two threads happen to load and store in parallel
            method = loadResolvedMethod(loader);
            resolvedMethods.put(loader, method);
        }
        if (method == SENTINEL_METHOD) {
            return null;
        }
        return method;
    }

    private Method loadResolvedMethod(ClassLoader loader) {
        try {
            Class<?> resolvedClass = loader.loadClass(unresolvedClass);
            if (parameterTypes != null) {
                return resolvedClass.getMethod(name, parameterTypes);
            } else {
                Class<?>[] parameterTypes = new Class<?>[unresolvedParameterTypes.length];
                for (int i = 0; i < unresolvedParameterTypes.length; i++) {
                    parameterTypes[i] = loader.loadClass(unresolvedParameterTypes[i]);
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
