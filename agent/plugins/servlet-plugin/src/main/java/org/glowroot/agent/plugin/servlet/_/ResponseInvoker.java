/*
 * Copyright 2014-2019 the original author or authors.
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
package org.glowroot.agent.plugin.servlet._;

import java.lang.reflect.Method;

import org.glowroot.agent.plugin.api.ClassInfo;
import org.glowroot.agent.plugin.api.checker.Nullable;
import org.glowroot.agent.plugin.api.util.Reflection;

public class ResponseInvoker {

    // ServletResponse.getContentType() was introduced in Servlet 2.4
    private final @Nullable Method getContentTypeMethod;
    // HttpServletResponse.getHeader() was introduced in Servlet 3.0
    private final @Nullable Method getHeaderMethod;
    // HttpServletResponse.getStatus() was introduced in Servlet 3.0
    private final @Nullable Method getStatusMethod;

    public ResponseInvoker(ClassInfo classInfo) {
        Class<?> servletResponseClass = Reflection
                .getClassWithWarnIfNotFound("javax.servlet.ServletResponse", classInfo.getLoader());
        getContentTypeMethod = Reflection.getMethod(servletResponseClass, "getContentType");
        Class<?> httpServletResponseClass = Reflection.getClassWithWarnIfNotFound(
                "javax.servlet.http.HttpServletResponse", classInfo.getLoader());
        getHeaderMethod = Reflection.getMethod(httpServletResponseClass, "getHeader", String.class);
        getStatusMethod = Reflection.getMethod(httpServletResponseClass, "getStatus");
    }

    public boolean hasGetContentTypeMethod() {
        return getContentTypeMethod != null;
    }

    public String getContentType(Object response) {
        return Reflection.invokeWithDefault(getContentTypeMethod, response, "");
    }

    public boolean hasGetHeaderMethod() {
        return getHeaderMethod != null;
    }

    public String getHeader(Object response, String name) {
        return Reflection.invokeWithDefault(getHeaderMethod, response, "", name);
    }

    public boolean hasGetStatusMethod() {
        return getStatusMethod != null;
    }

    public int getStatus(Object response) {
        return Reflection.invokeWithDefault(getStatusMethod, response, -1);
    }
}
