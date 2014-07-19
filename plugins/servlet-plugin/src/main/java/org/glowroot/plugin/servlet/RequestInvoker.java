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
import java.util.Map;
import java.util.NoSuchElementException;

import com.google.common.collect.ImmutableMap;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.api.Logger;
import org.glowroot.api.LoggerFactory;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class RequestInvoker {

    private static final Logger logger = LoggerFactory.getLogger(RequestInvoker.class);

    @Nullable
    private final Method getSessionMethod;
    @Nullable
    private final Method getMethodMethod;
    @Nullable
    private final Method getRequestURIMethod;
    @Nullable
    private final Method getQueryStringMethod;
    @Nullable
    private final Method getHeaderMethod;
    @Nullable
    private final Method getHeadersMethod;
    @Nullable
    private final Method getHeaderNamesMethod;
    @Nullable
    private final Method getUserPrincipalMethod;
    @Nullable
    private final Method getParameterMapMethod;

    private final PrincipalInvoker principalInvoker;

    public RequestInvoker(Class<?> clazz) {
        Class<?> httpServletRequestClass = null;
        Class<?> servletRequestClass = null;
        try {
            httpServletRequestClass = Class.forName("javax.servlet.http.HttpServletRequest", false,
                    clazz.getClassLoader());
            servletRequestClass = Class.forName("javax.servlet.ServletRequest", false,
                    clazz.getClassLoader());
        } catch (ClassNotFoundException e) {
            logger.warn(e.getMessage(), e);
        }
        getSessionMethod = getMethod(httpServletRequestClass, "getSession", boolean.class);
        getMethodMethod = getMethod(httpServletRequestClass, "getMethod");
        getRequestURIMethod = getMethod(httpServletRequestClass, "getRequestURI");
        getQueryStringMethod = getMethod(httpServletRequestClass, "getQueryString");
        getHeaderMethod = getMethod(httpServletRequestClass, "getHeader", String.class);
        getHeadersMethod = getMethod(httpServletRequestClass, "getHeaders", String.class);
        getHeaderNamesMethod = getMethod(httpServletRequestClass, "getHeaderNames");
        getUserPrincipalMethod = getMethod(httpServletRequestClass, "getUserPrincipal");
        getParameterMapMethod = getMethod(servletRequestClass, "getParameterMap");

        principalInvoker = new PrincipalInvoker(clazz);
    }

    @Nullable
    public Object getSession(Object request) {
        if (getSessionMethod == null) {
            return null;
        }
        try {
            // passing "false" so it won't create a session if the request doesn't already have one
            return getSessionMethod.invoke(request, false);
        } catch (Throwable t) {
            logger.warn("error calling HttpServletRequest.getSession()", t);
            return null;
        }
    }

    public String getMethod(Object request) {
        if (getMethodMethod == null) {
            return "";
        }
        try {
            String method = (String) getMethodMethod.invoke(request);
            if (method == null) {
                return "";
            }
            return method;
        } catch (Throwable t) {
            logger.warn("error calling HttpServletRequest.getMethod()", t);
            return "<error calling HttpServletRequest.getMethod()>";
        }
    }

    public String getRequestURI(Object request) {
        if (getRequestURIMethod == null) {
            return "";
        }
        try {
            String requestURI = (String) getRequestURIMethod.invoke(request);
            if (requestURI == null) {
                return "";
            }
            return requestURI;
        } catch (Throwable t) {
            logger.warn("error calling HttpServletRequest.getRequestURI()", t);
            return "<error calling HttpServletRequest.getRequestURI()>";
        }
    }

    // don't convert null to empty, since null means no query string, while empty means
    // url ended with ? but nothing after that
    @Nullable
    public String getQueryString(Object request) {
        if (getQueryStringMethod == null) {
            return null;
        }
        try {
            return (String) getQueryStringMethod.invoke(request);
        } catch (Throwable t) {
            logger.warn("error calling HttpServletRequest.getQueryString()", t);
            return "<error calling HttpServletRequest.getQueryString()>";
        }
    }

    @Nullable
    public String getHeader(Object request, String name) {
        if (getHeaderMethod == null) {
            return null;
        }
        try {
            return (String) getHeaderMethod.invoke(request, name);
        } catch (Throwable t) {
            logger.warn("error calling HttpServletRequest.getHeader()", t);
            return "<error calling HttpServletRequest.getHeader()>";
        }
    }

    @SuppressWarnings("unchecked")
    public Enumeration<String> getHeaders(Object request, String name) {
        if (getHeadersMethod == null) {
            return EmptyStringEnumeration.INSTANCE;
        }
        try {
            Enumeration<String> headers =
                    (Enumeration<String>) getHeadersMethod.invoke(request, name);
            if (headers == null) {
                return EmptyStringEnumeration.INSTANCE;
            }
            return headers;
        } catch (Throwable t) {
            logger.warn("error calling HttpServletRequest.getHeaders()", t);
            return EmptyStringEnumeration.INSTANCE;
        }
    }

    @SuppressWarnings("unchecked")
    public Enumeration<String> getHeaderNames(Object request) {
        if (getHeaderNamesMethod == null) {
            return EmptyStringEnumeration.INSTANCE;
        }
        try {
            Enumeration<String> headerNames =
                    (Enumeration<String>) getHeaderNamesMethod.invoke(request);
            if (headerNames == null) {
                return EmptyStringEnumeration.INSTANCE;
            }
            return headerNames;
        } catch (Throwable t) {
            logger.warn("error calling HttpServletRequest.getHeaderNames()", t);
            return EmptyStringEnumeration.INSTANCE;
        }
    }

    @Nullable
    public String getUserPrincipalName(Object request) {
        if (getUserPrincipalMethod == null) {
            return null;
        }
        Object principal;
        try {
            principal = getUserPrincipalMethod.invoke(request);
        } catch (Throwable t) {
            logger.warn("error calling HttpServletRequest.getUserPrincipal()", t);
            return null;
        }
        if (principal == null) {
            return null;
        }
        return principalInvoker.getName(principal);
    }

    @SuppressWarnings("unchecked")
    public Map<String, String[]> getParameterMap(Object request) {
        if (getParameterMapMethod == null) {
            return ImmutableMap.of();
        }
        try {
            Map<String, String[]> parameterMap =
                    (Map<String, String[]>) getParameterMapMethod.invoke(request);
            if (parameterMap == null) {
                return ImmutableMap.of();
            }
            return parameterMap;
        } catch (Throwable t) {
            logger.warn("error calling ServletRequest.getParameterMap()", t);
            return ImmutableMap.of();
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

        private static final Enumeration<String> INSTANCE =
                new EmptyStringEnumeration();

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
