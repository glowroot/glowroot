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
package io.informant.api;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Map;

import checkers.nullness.quals.Nullable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.MapMaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Calling methods on 3rd party libraries from a plugin can be difficult. Plugins are loaded by the
 * system class loader ({@link ClassLoader#getSystemClassLoader()}). This means plugins don't have
 * visibility to classes loaded by custom class loaders, which is especially problematic in Java EE
 * environments where custom class loaders are used to scope each running application.
 * 
 * This class is designed to make these calls easy and reasonably efficient. It is designed to be
 * cached in a static field for the life of the jvm, e.g.
 * 
 * <pre>
 * &#064;Pointcut(typeName = &quot;org.apache.jasper.JspCompilationContext&quot;,
 *         methodName = &quot;compile&quot;)
 * class ServletAdvice {
 * 
 *     private static final UnresolvedMethod getJspFileMethod = UnresolvedMethod
 *             .from(&quot;org.apache.jasper.JspCompilationContext&quot;, &quot;getJspFile&quot;);
 * 
 *     &#064;OnBefore
 *     public static Span onBefore(@BindTarget Object context) {
 *         String jspFile = (String) getJspFileMethod.invoke(context, &quot;&lt;unknown&gt;&quot;);
 *         ...
 *     }
 * }
 * </pre>
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
    @Nullable
    private final ImmutableList<Class<?>> parameterTypes;
    @Nullable
    private final ImmutableList<String> parameterTypeNames;

    // for performance sensitive areas do not use guava's LoadingCache due to volatile write (via
    // incrementing an AtomicInteger) at the end of get() in LocalCache$Segment.postReadCleanup()
    //
    // weak keys and weak values to prevent retention of class loaders (weak values since Method
    // retains its ClassLoader)
    private final Map<ClassLoader, Method> resolvedMethods = new MapMaker().weakKeys().weakValues()
            .makeMap();

    /**
     * Create an {@code UnresolvedMethod} for the specified {@code typeName} and {@code methodName}.
     * 
     * @param typeName
     * @param methodName
     * @return an {@code UnresolvedMethod} for the specified {@code typeName} and {@code methodName}
     */
    public static UnresolvedMethod from(String typeName, String methodName) {
        checkNotNull(typeName);
        checkNotNull(methodName);
        return new UnresolvedMethod(typeName, methodName, ImmutableList.<Class<?>>of(), null);
    }

    /**
     * Create an {@code UnresolvedMethod} for the specified {@code typeName}, {@code methodName} and
     * {@code parameterTypes}.
     * 
     * @param typeName
     * @param methodName
     * @return an {@code UnresolvedMethod} for the specified {@code typeName}, {@code methodName}
     *         and {@code parameterTypes}.
     */
    public static UnresolvedMethod from(String typeName, String methodName,
            Class<?>... parameterTypes) {
        checkNotNull(typeName);
        checkNotNull(methodName);
        checkNotNull(parameterTypes);
        return new UnresolvedMethod(typeName, methodName, ImmutableList.copyOf(parameterTypes),
                null);
    }

    /**
     * Create an {@code UnresolvedMethod} for the specified {@code typeName}, {@code methodName} and
     * {@code parameterTypeNames}.
     * 
     * @param typeName
     * @param methodName
     * @param parameterTypeNames
     * @return an {@code UnresolvedMethod} for the specified {@code typeName}, {@code methodName}
     *         and {@code parameterTypeNames}.
     */
    public static UnresolvedMethod from(String typeName, String methodName,
            String... parameterTypeNames) {
        checkNotNull(typeName);
        checkNotNull(methodName);
        checkNotNull(parameterTypeNames);
        return new UnresolvedMethod(typeName, methodName, null,
                ImmutableList.copyOf(parameterTypeNames));
    }

    private UnresolvedMethod(String typeName, String methodName,
            @Nullable ImmutableList<Class<?>> parameterTypes,
            @Nullable ImmutableList<String> parameterTypeNames) {
        if (parameterTypes == null && parameterTypeNames == null) {
            throw new IllegalStateException("Constructor args 'parameterTypes' and"
                    + " 'parameterTypeNames' cannot both be null (enforced by static factory"
                    + " methods)");
        }
        this.typeName = typeName;
        this.methodName = methodName;
        this.parameterTypes = parameterTypes;
        this.parameterTypeNames = parameterTypeNames;
    }

    /**
     * Invokes the no-arg instance method represented by this {@code UnresolvedMethod} on the
     * specified {@code target}.
     * 
     * If any kind of error is encountered (including but not limited to
     * {@link ClassNotFoundException}, {@link NoSuchMethodException},
     * {@link InvocationTargetException}), the supplied {@code returnOnError} is returned.
     * 
     * @param target
     * @param returnOnError
     * @return the result of dispatching the no-arg instance method represented by this
     *         {@code UnresolvedMethod} on the specified {@code target}
     */
    @Nullable
    public Object invoke(Object target, @Nullable Object returnOnError) {
        return invoke(target, new Object[0], returnOnError);
    }

    /**
     * Invokes the instance method represented by this {@code UnresolvedMethod} on the specified
     * {@code target} with the single specified {@code parameter}.
     * 
     * If any kind of error is encountered (including but not limited to
     * {@link ClassNotFoundException}, {@link NoSuchMethodException},
     * {@link InvocationTargetException}), the supplied {@code returnOnError} is returned.
     * 
     * @param target
     * @param parameter
     * @param returnOnError
     * @return the result of dispatching the instance method represented by this
     *         {@code UnresolvedMethod} on the specified {@code target} with the single specified
     *         {@code parameter}
     */
    @Nullable
    public Object invoke(Object target, Object parameter, @Nullable Object returnOnError) {
        return invoke(target, new Object[] {parameter}, returnOnError);
    }

    /**
     * Invokes the instance method represented by this {@code UnresolvedMethod} on the specified
     * {@code target} with the specified {@code parameters}.
     * 
     * If any kind of error is encountered (including but not limited to
     * {@link ClassNotFoundException}, {@link NoSuchMethodException},
     * {@link InvocationTargetException}), the supplied {@code returnOnError} is returned.
     * 
     * @param target
     * @param parameters
     * @param returnOnError
     * @return the result of dispatching the instance method represented by this
     *         {@code UnresolvedMethod} on the specified {@code target} with the specified
     *         {@code parameters}
     */
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
            logger.debug("error invoking method, falling back to returnOnError value", t);
            return returnOnError;
        }
    }

    /**
     * Invokes the no-arg static method represented by this {@code UnresolvedMethod}. The class is
     * resolved in the specified {@code loader}.
     * 
     * If any kind of error is encountered (including but not limited to
     * {@link ClassNotFoundException}, {@link NoSuchMethodException},
     * {@link InvocationTargetException}), the supplied {@code returnOnError} is returned.
     * 
     * @param loader
     * @param returnOnError
     * @return the result of dispatching the no-arg static method represented by this
     *         {@code UnresolvedMethod}
     */
    @Nullable
    public Object invokeStatic(@Nullable ClassLoader loader, @Nullable Object returnOnError) {
        return invokeStatic(loader, new Object[0], returnOnError);
    }

    /**
     * Invokes the static method represented by this {@code UnresolvedMethod} with the single
     * specified {@code parameter}. The class is resolved in the specified {@code loader}.
     * 
     * If any kind of error is encountered (including but not limited to
     * {@link ClassNotFoundException}, {@link NoSuchMethodException},
     * {@link InvocationTargetException}), the supplied {@code returnOnError} is returned.
     * 
     * @param loader
     * @param parameters
     * @param returnOnError
     * @return the result of dispatching the static method represented by this
     *         {@code UnresolvedMethod} with the single specified {@code parameter}
     */
    @Nullable
    public Object invokeStatic(@Nullable ClassLoader loader, Object parameters,
            @Nullable Object returnOnError) {
        return invokeStatic(loader, new Object[] {parameters}, returnOnError);
    }

    /**
     * Invokes the static method represented by this {@code UnresolvedMethod} with the specified
     * {@code parameters}. The class is resolved in the specified {@code loader}.
     * 
     * If any kind of error is encountered (including but not limited to
     * {@link ClassNotFoundException}, {@link NoSuchMethodException},
     * {@link InvocationTargetException}), the supplied {@code returnOnError} is returned.
     * 
     * @param loader
     * @param parameters
     * @param returnOnError
     * @return the result of dispatching the static method represented by this
     *         {@code UnresolvedMethod} with the specified {@code parameters}
     */
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
            logger.debug("error invoking method, falling back to returnOnError value", t);
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
        if (method.equals(SENTINEL_METHOD)) {
            return null;
        }
        return method;
    }

    private Method resolveMethod(@Nullable ClassLoader loader) {
        try {
            Class<?> resolvedClass = Class.forName(typeName, false, loader);
            if (parameterTypes != null) {
                return resolvedClass.getMethod(methodName,
                        Iterables.toArray(parameterTypes, Class.class));
            } else if (parameterTypeNames == null) {
                throw new IllegalStateException("Fields 'parameterTypes' and"
                        + " 'parameterTypeNames' cannot both be null (enforced by static factory"
                        + " methods)");
            } else {
                Class<?>[] resolvedParameterTypes = new Class<?>[parameterTypeNames.size()];
                for (int i = 0; i < parameterTypeNames.size(); i++) {
                    resolvedParameterTypes[i] = Class.forName(parameterTypeNames.get(i),
                            false, loader);
                }
                return resolvedClass.getMethod(methodName, resolvedParameterTypes);
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
