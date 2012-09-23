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

import java.lang.reflect.InvocationTargetException;
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

    private final String unresolvedClass;
    private final String name;
    @Nullable
    private final Class<?>[] parameterTypes;
    @Nullable
    private final String[] unresolvedParameterTypes;

    // for performance sensitive areas do not use guava's LoadingCache due to volatile write (via
    // incrementing an AtomicInteger) at the end of get() in LocalCache$Segment.postReadCleanup()
    //
    // TODO weak keys and weak values out of concern for possible retention cycle between
    // ClassLoader and Method
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
    public Object invoke(Object target, Object... parameters) {
        Method method = getResolvedMethod(target.getClass().getClassLoader());
        return invoke(method, target, parameters);
    }

    @Nullable
    public Object invokeStatic(ClassLoader loader, Object... parameters) {
        Method method = getResolvedMethod(loader);
        return invoke(method, null, parameters);
    }

    private Method getResolvedMethod(ClassLoader loader) {
        Method method = resolvedMethods.get(loader);
        if (method == null) {
            // just a cache, ok if two threads happen to load and store in parallel
            method = loadResolvedMethod(loader);
            resolvedMethods.put(loader, method);
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
            // TODO bug in a plugin shouldn't be fatal to informant
            logger.error("fatal error occurred: " + e.getMessage(), e.getCause());
            throw new IllegalStateException("Fatal error occurred: "
                    + e.getMessage(), e.getCause());
        } catch (SecurityException e) {
            // TODO bug in a plugin shouldn't be fatal to informant
            logger.error("fatal error occurred: " + e.getMessage(), e.getCause());
            throw new IllegalStateException("Fatal error occurred: "
                    + e.getMessage(), e.getCause());
        } catch (NoSuchMethodException e) {
            // TODO bug in a plugin shouldn't be fatal to informant
            logger.error("fatal error occurred: " + e.getMessage(), e.getCause());
            throw new IllegalStateException("Fatal error occurred: "
                    + e.getMessage(), e.getCause());
        }
    }

    @Nullable
    private Object invoke(Method method, @Nullable Object target, Object... parameters) {
        try {
            return method.invoke(target, parameters);
        } catch (IllegalArgumentException e) {
            // TODO bug in a plugin shouldn't be fatal to informant
            logger.error("fatal error occurred: " + e.getMessage(), e);
            throw new IllegalStateException("Fatal error occurred: " + e.getMessage(), e);
        } catch (IllegalAccessException e) {
            // TODO bug in a plugin shouldn't be fatal to informant
            logger.error("fatal error occurred: " + e.getMessage(), e);
            throw new IllegalStateException("Fatal error occurred: " + e.getMessage(), e);
        } catch (InvocationTargetException e) {
            // TODO bug in a plugin shouldn't be fatal to informant
            logger.error("fatal error occurred: " + e.getMessage(), e);
            throw new IllegalStateException("Fatal error occurred: " + e.getMessage(), e);
        }
    }

}
