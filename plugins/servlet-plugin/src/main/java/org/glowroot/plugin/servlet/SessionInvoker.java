/*
 * Copyright 2014 the original author or authors.
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
package org.glowroot.plugin.servlet;

import java.lang.reflect.Method;
import java.util.Enumeration;
import java.util.NoSuchElementException;

import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.api.Logger;
import org.glowroot.api.LoggerFactory;

public class SessionInvoker {

    private static final Logger logger = LoggerFactory.getLogger(SessionInvoker.class);

    @Nullable
    private final Method getIdMethod;
    @Nullable
    private final Method isNewMethod;
    @Nullable
    private final Method getAttributeMethod;
    @Nullable
    private final Method getAttributeNamesMethod;

    public SessionInvoker(Class<?> clazz) {
        Class<?> httpSessionClass = null;
        try {
            httpSessionClass = Class.forName("javax.servlet.http.HttpSession", false,
                    clazz.getClassLoader());
        } catch (ClassNotFoundException e) {
            logger.warn(e.getMessage(), e);
        }
        getIdMethod = getMethod(httpSessionClass, "getId");
        isNewMethod = getMethod(httpSessionClass, "isNew");
        getAttributeMethod = getMethod(httpSessionClass, "getAttribute", String.class);
        getAttributeNamesMethod = getMethod(httpSessionClass, "getAttributeNames");
    }

    public String getId(Object session) {
        if (getIdMethod == null) {
            return "";
        }
        try {
            String id = (String) getIdMethod.invoke(session);
            if (id == null) {
                return "";
            }
            return id;
        } catch (Throwable t) {
            logger.warn("error calling HttpSession.getId()", t);
            return "";
        }
    }

    public boolean isNew(Object session) {
        if (isNewMethod == null) {
            return false;
        }
        try {
            Boolean isNew = (Boolean) isNewMethod.invoke(session);
            if (isNew == null) {
                logger.warn("method unexpectedly returned null: HttpSession.isNew()");
                return false;
            }
            return isNew;
        } catch (Throwable t) {
            logger.warn("error calling HttpSession.isNew()", t);
            return false;
        }
    }

    @Nullable
    public Object getAttribute(Object session, String name) {
        if (getAttributeMethod == null) {
            return null;
        }
        try {
            return getAttributeMethod.invoke(session, name);
        } catch (Throwable t) {
            logger.warn("error calling HttpSession.getAttribute()", t);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public Enumeration<String> getAttributeNames(Object session) {
        if (getAttributeNamesMethod == null) {
            return EmptyStringEnumeration.INSTANCE;
        }
        try {
            Enumeration<String> attributeNames =
                    (Enumeration<String>) getAttributeNamesMethod.invoke(session);
            if (attributeNames == null) {
                return EmptyStringEnumeration.INSTANCE;
            }
            return attributeNames;
        } catch (Throwable t) {
            logger.warn("error calling HttpSession.getAttributeNames()", t);
            return EmptyStringEnumeration.INSTANCE;
        }
    }

    @Nullable
    private static Method getMethod(@Nullable Class<?> clazz, String methodName,
            Class<?>... parameterTypes) {
        if (clazz == null) {
            return null;
        }
        try {
            return clazz.getMethod(methodName, parameterTypes);
        } catch (SecurityException e) {
            logger.warn(e.getMessage(), e);
            return null;
        } catch (NoSuchMethodException e) {
            logger.warn(e.getMessage(), e);
            return null;
        }
    }

    private static class EmptyStringEnumeration implements Enumeration<String> {

        private static final Enumeration<String> INSTANCE = new EmptyStringEnumeration();

        @Override
        public boolean hasMoreElements() {
            return false;
        }

        @Override
        public String nextElement() {
            throw new NoSuchElementException();
        }
    }
}
