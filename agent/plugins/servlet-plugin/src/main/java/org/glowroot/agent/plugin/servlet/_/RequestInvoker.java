/*
 * Copyright 2018-2019 the original author or authors.
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

public class RequestInvoker {

    // these ServletRequest methods were introduced in Servlet 3.0
    private final @Nullable Method getRemotePortMethod;
    private final @Nullable Method getLocalAddrMethod;
    private final @Nullable Method getLocalNameMethod;
    private final @Nullable Method getLocalPortMethod;

    public RequestInvoker(ClassInfo classInfo) {
        Class<?> servletRequestClass = Reflection
                .getClassWithWarnIfNotFound("javax.servlet.ServletRequest", classInfo.getLoader());
        getRemotePortMethod = Reflection.getMethod(servletRequestClass, "getRemotePort");
        getLocalAddrMethod = Reflection.getMethod(servletRequestClass, "getLocalAddr");
        getLocalNameMethod = Reflection.getMethod(servletRequestClass, "getLocalName");
        getLocalPortMethod = Reflection.getMethod(servletRequestClass, "getLocalPort");
    }

    public int getRemotePort(Object request) {
        return Reflection.invokeWithDefault(getRemotePortMethod, request, -1);
    }

    public String getLocalAddr(Object request) {
        return Reflection.invokeWithDefault(getLocalAddrMethod, request, "");
    }

    public String getLocalName(Object request) {
        return Reflection.invokeWithDefault(getLocalNameMethod, request, "");
    }

    public int getLocalPort(Object request) {
        return Reflection.invokeWithDefault(getLocalPortMethod, request, -1);
    }
}
