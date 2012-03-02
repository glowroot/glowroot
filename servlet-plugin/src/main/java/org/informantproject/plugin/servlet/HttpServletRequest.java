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

import java.util.Map;

import org.informantproject.api.UnresolvedMethod;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class HttpServletRequest {

    private static final UnresolvedMethod getSessionOneArgMethod = new UnresolvedMethod(
            "javax.servlet.http.HttpServletRequest", "getSession", boolean.class);
    private static final UnresolvedMethod getMethodMethod = new UnresolvedMethod(
            "javax.servlet.http.HttpServletRequest", "getMethod");
    private static final UnresolvedMethod getRequestURIMethod = new UnresolvedMethod(
            "javax.servlet.http.HttpServletRequest", "getRequestURI");
    private static final UnresolvedMethod getParameterMapMethod = new UnresolvedMethod(
            "javax.servlet.http.HttpServletRequest", "getParameterMap");
    private static final UnresolvedMethod getAttributeMethod = new UnresolvedMethod(
            "javax.servlet.http.HttpServletRequest", "getAttribute", String.class);

    private final Object realRequest;

    private HttpServletRequest(Object realRequest) {
        this.realRequest = realRequest;
    }

    HttpSession getSession(boolean create) {
        return HttpSession.from(getSessionOneArgMethod.invoke(realRequest, create));
    }

    String getMethod() {
        return (String) getMethodMethod.invoke(realRequest);
    }

    String getRequestURI() {
        return (String) getRequestURIMethod.invoke(realRequest);
    }

    Map<?, ?> getParameterMap() {
        return (Map<?, ?>) getParameterMapMethod.invoke(realRequest);
    }

    Object getAttribute(String name) {
        return getAttributeMethod.invoke(realRequest, name);
    }

    static HttpServletRequest from(Object realRequest) {
        return realRequest == null ? null : new HttpServletRequest(realRequest);
    }
}
