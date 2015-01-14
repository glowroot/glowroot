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
import java.security.Principal;
import java.util.Enumeration;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;

import org.glowroot.api.Logger;
import org.glowroot.api.LoggerFactory;
import org.glowroot.plugin.servlet.Invokers.EmptyStringEnumeration;

public class RequestInvoker {

    private static final Logger logger = LoggerFactory.getLogger(RequestInvoker.class);

    private final @Nullable Method getSessionMethod;
    private final @Nullable Method getMethodMethod;
    private final @Nullable Method getRequestURIMethod;
    private final @Nullable Method getQueryStringMethod;
    private final @Nullable Method getHeaderMethod;
    private final @Nullable Method getHeadersMethod;
    private final @Nullable Method getHeaderNamesMethod;
    private final @Nullable Method getUserPrincipalMethod;
    private final @Nullable Method getParameterMapMethod;

    public RequestInvoker(Class<?> clazz) {
        Class<?> httpServletRequestClass = getHttpServletRequestClass(clazz);
        Class<?> servletRequestClass = getServletRequestClass(clazz);
        getSessionMethod = Invokers.getMethod(httpServletRequestClass, "getSession", boolean.class);
        getMethodMethod = Invokers.getMethod(httpServletRequestClass, "getMethod");
        getRequestURIMethod = Invokers.getMethod(httpServletRequestClass, "getRequestURI");
        getQueryStringMethod = Invokers.getMethod(httpServletRequestClass, "getQueryString");
        getHeaderMethod = Invokers.getMethod(httpServletRequestClass, "getHeader", String.class);
        getHeadersMethod = Invokers.getMethod(httpServletRequestClass, "getHeaders", String.class);
        getHeaderNamesMethod = Invokers.getMethod(httpServletRequestClass, "getHeaderNames");
        getUserPrincipalMethod = Invokers.getMethod(httpServletRequestClass, "getUserPrincipal");
        getParameterMapMethod = Invokers.getMethod(servletRequestClass, "getParameterMap");
    }

    public @Nullable Object getSession(Object request) {
        // passing "false" so it won't create a session if the request doesn't already have one
        return Invokers.invoke(getSessionMethod, request, false, null);
    }

    public String getMethod(Object request) {
        return Invokers.invoke(getMethodMethod, request, "");
    }

    public String getRequestURI(Object request) {
        return Invokers.invoke(getRequestURIMethod, request, "");
    }

    // don't convert null to empty, since null means no query string, while empty means
    // url ended with ? but nothing after that
    public @Nullable String getQueryString(Object request) {
        return Invokers.invoke(getQueryStringMethod, request, null);
    }

    public @Nullable String getHeader(Object request, String name) {
        return Invokers.invoke(getHeaderMethod, request, name, null);
    }

    public Enumeration<String> getHeaders(Object request, String name) {
        return Invokers.invoke(getHeadersMethod, request, name, EmptyStringEnumeration.INSTANCE);
    }

    public Enumeration<String> getHeaderNames(Object request) {
        return Invokers.invoke(getHeaderNamesMethod, request, EmptyStringEnumeration.INSTANCE);
    }

    public @Nullable String getUserPrincipalName(Object request) {
        Principal principal = Invokers.invoke(getUserPrincipalMethod, request, null);
        if (principal == null) {
            return null;
        }
        return principal.getName();
    }

    public Map<String, String[]> getParameterMap(Object request) {
        return Invokers.invoke(getParameterMapMethod, request, ImmutableMap.<String, String[]>of());
    }

    @VisibleForTesting
    static @Nullable Class<?> getHttpServletRequestClass(Class<?> clazz) {
        try {
            return Class.forName("javax.servlet.http.HttpServletRequest", false,
                    clazz.getClassLoader());
        } catch (ClassNotFoundException e) {
            logger.warn(e.getMessage(), e);
        }
        return null;
    }

    @VisibleForTesting
    static @Nullable Class<?> getServletRequestClass(Class<?> clazz) {
        try {
            return Class.forName("javax.servlet.ServletRequest", false, clazz.getClassLoader());
        } catch (ClassNotFoundException e) {
            logger.warn(e.getMessage(), e);
        }
        return null;
    }
}
