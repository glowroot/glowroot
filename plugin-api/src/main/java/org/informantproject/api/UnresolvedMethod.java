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
import java.util.concurrent.ExecutionException;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

/**
 * Designed to be statically cached. A single UnresolvedMethod instance works across multiple class
 * loaders.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class UnresolvedMethod {

    private static final Logger logger = LoggerFactory.getLogger(UnresolvedMethod.class);

    private final String unresolvedClass;
    private final String name;
    private final Class<?>[] parameterTypes;
    private final String[] unresolvedParameterTypes;

    // weak keys and weak values out of concern for possible retention cycle between ClassLoader and
    // Method
    private final LoadingCache<ClassLoader, Method> resolvedMethods = CacheBuilder.newBuilder()
            .weakKeys().weakValues().build(new CacheLoader<ClassLoader, Method>() {
                @Override
                public Method load(ClassLoader classLoader) throws ClassNotFoundException,
                        NoSuchMethodException, SecurityException {
                    Class<?> resolvedClass = classLoader.loadClass(unresolvedClass);
                    if (parameterTypes != null) {
                        return resolvedClass.getMethod(name, parameterTypes);
                    } else {
                        Class<?>[] parameterTypes = new Class<?>[unresolvedParameterTypes.length];
                        for (int i = 0; i < unresolvedParameterTypes.length; i++) {
                            parameterTypes[i] = classLoader.loadClass(unresolvedParameterTypes[i]);
                        }
                        return resolvedClass.getMethod(name, parameterTypes);
                    }
                }
            });

    public UnresolvedMethod(String unresolvedClass, String name) {
        this.unresolvedClass = unresolvedClass;
        this.name = name;
        parameterTypes = new Class<?>[0];
        unresolvedParameterTypes = null;
    }

    public UnresolvedMethod(String unresolvedClass, String name, Class<?>... parameterTypes) {
        this.unresolvedClass = unresolvedClass;
        this.name = name;
        this.parameterTypes = parameterTypes;
        unresolvedParameterTypes = null;
    }

    public UnresolvedMethod(String unresolvedClass, String name,
            String... unresolvedParameterTypes) {

        this.unresolvedClass = unresolvedClass;
        this.name = name;
        this.unresolvedParameterTypes = unresolvedParameterTypes;
        parameterTypes = null;
    }

    public Object invoke(Object target, Object... parameters) {
        Method method;
        try {
            method = resolvedMethods.get(target.getClass().getClassLoader());
        } catch (ExecutionException e) {
            // TODO bug in a plugin shouldn't be fatal to informant
            logger.error("fatal error occurred: " + e.getMessage(), e.getCause());
            throw new IllegalStateException("Fatal error occurred: " + e.getMessage(),
                    e.getCause());
        }
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

    public Object invokeStatic(ClassLoader classLoader, Object... parameters) {
        Method method;
        try {
            method = resolvedMethods.get(classLoader);
        } catch (ExecutionException e) {
            // TODO bug in a plugin shouldn't be fatal to informant
            logger.error("fatal error occurred: " + e.getMessage(), e.getCause());
            throw new IllegalStateException("Fatal error occurred: " + e.getMessage(),
                    e.getCause());
        }
        try {
            return method.invoke(null, parameters);
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
