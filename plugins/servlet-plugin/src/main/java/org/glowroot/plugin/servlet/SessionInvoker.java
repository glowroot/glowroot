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

import javax.annotation.Nullable;

import org.glowroot.api.Logger;
import org.glowroot.api.LoggerFactory;
import org.glowroot.plugin.servlet.Invokers.EmptyStringEnumeration;

public class SessionInvoker {

    private static final Logger logger = LoggerFactory.getLogger(SessionInvoker.class);

    private final @Nullable Method getAttributeMethod;
    private final @Nullable Method getAttributeNamesMethod;

    public SessionInvoker(Class<?> clazz) {
        Class<?> httpSessionClass = null;
        try {
            httpSessionClass = Class.forName("javax.servlet.http.HttpSession", false,
                    clazz.getClassLoader());
        } catch (ClassNotFoundException e) {
            logger.warn(e.getMessage(), e);
        }
        getAttributeMethod = Invokers.getMethod(httpSessionClass, "getAttribute", String.class);
        getAttributeNamesMethod = Invokers.getMethod(httpSessionClass, "getAttributeNames");
    }

    public @Nullable Object getAttribute(Object session, String name) {
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
}
