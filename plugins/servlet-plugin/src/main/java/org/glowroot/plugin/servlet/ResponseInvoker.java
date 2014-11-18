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

import javax.annotation.Nullable;

import org.glowroot.api.Logger;
import org.glowroot.api.LoggerFactory;

public class ResponseInvoker {

    private static final Logger logger = LoggerFactory.getLogger(ResponseInvoker.class);

    private final @Nullable Method getContentTypeMethod;

    public ResponseInvoker(Class<?> clazz) {
        Class<?> servletResponseClass = null;
        try {
            servletResponseClass = Class.forName("javax.servlet.ServletResponse", false,
                    clazz.getClassLoader());
        } catch (ClassNotFoundException e) {
            logger.warn(e.getMessage(), e);
        }
        getContentTypeMethod = getMethod(servletResponseClass, "getContentType");
    }

    public @Nullable String getContentType(Object response) {
        if (getContentTypeMethod == null) {
            // this method only exists since Servlet 2.4 (e.g. since Tomcat 5.5.x)
            // intentionally returning null here to be detected by caller
            return null;
        }
        try {
            String contentType = (String) getContentTypeMethod.invoke(response);
            if (contentType == null) {
                return "";
            }
            return contentType;
        } catch (Throwable t) {
            logger.warn("error calling ServletResponse.getContentType()", t);
            return "<error calling ServletResponse.getContentType()>";
        }
    }

    private static @Nullable Method getMethod(@Nullable Class<?> clazz, String methodName,
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
}
