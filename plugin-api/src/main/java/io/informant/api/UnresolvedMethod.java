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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedAction;
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
        SENTINEL_CLASS_LOADER = AccessController.doPrivileged(
                new PrivilegedAction<ClassLoader>() {
                    public ClassLoader run() {
                        return new ClassLoader() {};
                    }
                });
        try {
            SENTINEL_METHOD = UnresolvedMethod.class.getDeclaredMethod("sentinelMethod");
        } catch (SecurityException e) {
            throw new IllegalStateException("Unrecoverable error", e);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Unrecoverable error", e);
        }
    }

    private final String typeName;
    private final String methodName;
    private final Class<?>/*@Nullable*/[] parameterTypes;
    private final String/*@Nullable*/[] parameterTypeNames;

    // for performance sensitive areas do not use guava's LoadingCache due to volatile write (via
    // incrementing an AtomicInteger) at the end of get() in LocalCache$Segment.postReadCleanup()
    //
    // weak keys and weak values to prevent retention of class loaders (weak values since Method
    // retains its ClassLoader)
    private final Map<ClassLoader, Method> resolvedMethods = new MapMaker().weakKeys().weakValues()
            .makeMap();

    public static UnresolvedMethod from(String typeName, String methodName) {
        return new UnresolvedMethod(typeName, methodName, new Class<?>[0], null);
    }

    public static UnresolvedMethod from(String typeName, String methodName,
            Class<?>... parameterTypes) {
        return new UnresolvedMethod(typeName, methodName, parameterTypes, null);
    }

    public static UnresolvedMethod from(String typeName, String methodName,
            String... parameterTypeNames) {
        return new UnresolvedMethod(typeName, methodName, null, parameterTypeNames);
    }

    private UnresolvedMethod(String typeName, String methodName,
            Class<?>/*@Nullable*/[] parameterTypes, String/*@Nullable*/[] parameterTypeNames) {
        if (parameterTypes == null && parameterTypeNames == null) {
            throw new NullPointerException("Constructor args 'parameterTypes' and"
                    + " 'parameterTypeNames' cannot both be null (enforced by static factory"
                    + " methods)");
        }
        this.typeName = typeName;
        this.methodName = methodName;
        this.parameterTypes = parameterTypes;
        this.parameterTypeNames = parameterTypeNames;
    }

    @Nullable
    public Object invoke(Object target, @Nullable Object returnOnError) {
        return invoke(target, new Object[0], returnOnError);
    }

    @Nullable
    public Object invoke(Object target, Object parameter, @Nullable Object returnOnError) {
        return invoke(target, new Object[] { parameter }, returnOnError);
    }

    @Nullable
    public Object invoke(Object target, Object[] parameters, @Nullable Object returnOnError) {
        Method method = getResolvedMethod(target.getClass().getClassLoader());
        if (method == null) {
            // warning has already been logged in getResolvedMethod()
            return returnOnError;
        }
        try {
            return invokeInternal(method, target, parameters);
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
            return returnOnError;
        }
    }

    @Nullable
    public Object invokeStatic(@Nullable ClassLoader loader, @Nullable Object returnOnError) {
        return invokeStatic(loader, new Object[0], returnOnError);
    }

    @Nullable
    public Object invokeStatic(@Nullable ClassLoader loader, Object parameters,
            @Nullable Object returnOnError) {
        return invokeStatic(loader, new Object[] { parameters }, returnOnError);
    }

    @Nullable
    public Object invokeStatic(@Nullable ClassLoader loader, Object[] parameters,
            @Nullable Object returnOnError) {
        Method method = getResolvedMethod(loader);
        if (method == null) {
            // warning has already been logged in getResolvedMethod()
            return returnOnError;
        }
        try {
            return invokeInternal(method, null, parameters);
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
            return returnOnError;
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
            method = resolveMethod(loader);
            resolvedMethods.put(key, method);
        }
        if (method == SENTINEL_METHOD) {
            return null;
        }
        return method;
    }

    private Method resolveMethod(@Nullable ClassLoader loader) {
        try {
            Class<?> resolvedClass = Class.forName(typeName, false, loader);
            if (parameterTypes != null) {
                return resolvedClass.getMethod(methodName, parameterTypes);
            } else if (parameterTypeNames == null) {
                throw new NullPointerException("Fields 'parameterTypes' and"
                        + " 'parameterTypeNames' cannot both be null (enforced by static factory"
                        + " methods)");
            } else {
                Class<?>[] parameterTypes = new Class<?>[parameterTypeNames.length];
                for (int i = 0; i < parameterTypeNames.length; i++) {
                    parameterTypes[i] = Class.forName(parameterTypeNames[i],
                            false,
                            loader);
                }
                return resolvedClass.getMethod(methodName, parameterTypes);
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
    private Object invokeInternal(Method method, @Nullable Object target, Object... parameters)
            throws IllegalAccessException, InvocationTargetException {
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
