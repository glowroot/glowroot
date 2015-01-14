/*
 * Copyright 2014-2015 the original author or authors.
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

import com.google.common.annotations.VisibleForTesting;

import org.glowroot.api.Logger;
import org.glowroot.api.LoggerFactory;
import org.glowroot.plugin.servlet.Invokers.EmptyStringEnumeration;

public class SessionInvoker {

    private static final Logger logger = LoggerFactory.getLogger(SessionInvoker.class);

    private final @Nullable Method getAttributeMethod;
    private final @Nullable Method getAttributeNamesMethod;

    public SessionInvoker(Class<?> clazz) {
        Class<?> httpSessionClass = getHttpSessionClass(clazz);
        getAttributeMethod = Invokers.getMethod(httpSessionClass, "getAttribute", String.class);
        getAttributeNamesMethod = Invokers.getMethod(httpSessionClass, "getAttributeNames");
    }

    public @Nullable Object getAttribute(Object session, String name) {
        return Invokers.invoke(getAttributeMethod, session, name, null);
    }

    public Enumeration<String> getAttributeNames(Object session) {
        return Invokers.invoke(getAttributeNamesMethod, session, EmptyStringEnumeration.INSTANCE);
    }

    @VisibleForTesting
    static @Nullable Class<?> getHttpSessionClass(Class<?> clazz) {
        try {
            return Class.forName("javax.servlet.http.HttpSession", false, clazz.getClassLoader());
        } catch (ClassNotFoundException e) {
            logger.warn(e.getMessage(), e);
        }
        return null;
    }
}
