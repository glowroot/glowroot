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
package org.informantproject.plugin.servlet;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Enumeration;
import java.util.concurrent.ExecutionException;

import org.informantproject.api.Logger;
import org.informantproject.api.LoggerFactory;
import org.informantproject.shaded.google.common.cache.Cache;
import org.informantproject.shaded.google.common.cache.CacheBuilder;
import org.informantproject.shaded.google.common.cache.CacheLoader;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class HttpSession {

    private static final Logger logger = LoggerFactory.getLogger(HttpServletRequest.class);

    private static final Cache<ClassLoader, ScopedMethods> methodCache = CacheBuilder.newBuilder()
            .weakKeys().build(new CacheLoader<ClassLoader, ScopedMethods>() {
                @Override
                public ScopedMethods load(ClassLoader classLoader) throws Exception {
                    return new ScopedMethods(classLoader);
                }
            });

    private final Object realSession;
    private final ScopedMethods methods;

    private HttpSession(Object realSession) {
        this.realSession = realSession;
        try {
            methods = methodCache.get(realSession.getClass().getClassLoader());
        } catch (ExecutionException e) {
            logger.error("Fatal error occurred: " + e.getMessage(), e);
            throw new IllegalStateException(e);
        }
    }

    String getId() {
        try {
            return (String) methods.getIdMethod.invoke(realSession);
        } catch (IllegalArgumentException e) {
            logger.error("Fatal error occurred: " + e.getMessage(), e);
            throw new IllegalStateException(e);
        } catch (IllegalAccessException e) {
            logger.error("Fatal error occurred: " + e.getMessage(), e);
            throw new IllegalStateException(e);
        } catch (InvocationTargetException e) {
            logger.error("Fatal error occurred: " + e.getMessage(), e);
            throw new IllegalStateException(e);
        }
    }

    boolean isNew() {
        try {
            return (Boolean) methods.isNewMethod.invoke(realSession);
        } catch (IllegalArgumentException e) {
            logger.error("Fatal error occurred: " + e.getMessage(), e);
            throw new IllegalStateException(e);
        } catch (IllegalAccessException e) {
            logger.error("Fatal error occurred: " + e.getMessage(), e);
            throw new IllegalStateException(e);
        } catch (InvocationTargetException e) {
            logger.error("Fatal error occurred: " + e.getMessage(), e);
            throw new IllegalStateException(e);
        }
    }

    Enumeration<?> getAttributeNames() {
        try {
            return (Enumeration<?>) methods.getAttributeNamesMethod.invoke(realSession);
        } catch (IllegalArgumentException e) {
            logger.error("Fatal error occurred: " + e.getMessage(), e);
            throw new IllegalStateException(e);
        } catch (IllegalAccessException e) {
            logger.error("Fatal error occurred: " + e.getMessage(), e);
            throw new IllegalStateException(e);
        } catch (InvocationTargetException e) {
            logger.error("Fatal error occurred: " + e.getMessage(), e);
            throw new IllegalStateException(e);
        }
    }

    Object getAttribute(String name) {
        try {
            return methods.getAttributeMethod.invoke(realSession, name);
        } catch (IllegalArgumentException e) {
            logger.error("Fatal error occurred: " + e.getMessage(), e);
            throw new IllegalStateException(e);
        } catch (IllegalAccessException e) {
            logger.error("Fatal error occurred: " + e.getMessage(), e);
            throw new IllegalStateException(e);
        } catch (InvocationTargetException e) {
            logger.error("Fatal error occurred: " + e.getMessage(), e);
            throw new IllegalStateException(e);
        }
    }

    static HttpSession from(Object realSession) {
        return realSession == null ? null : new HttpSession(realSession);
    }

    private static final class ScopedMethods {

        private final Method getIdMethod;
        private final Method isNewMethod;
        private final Method getAttributeNamesMethod;
        private final Method getAttributeMethod;

        private ScopedMethods(ClassLoader classLoader) throws ClassNotFoundException,
                SecurityException, NoSuchMethodException {

            Class<?> httpServletRequestClass = classLoader
                    .loadClass("javax.servlet.http.HttpSession");
            getIdMethod = httpServletRequestClass.getMethod("getId");
            isNewMethod = httpServletRequestClass.getMethod("isNew");
            getAttributeNamesMethod = httpServletRequestClass.getMethod("getAttributeNames");
            getAttributeMethod = httpServletRequestClass.getMethod("getAttribute", String.class);
        }
    }
}
