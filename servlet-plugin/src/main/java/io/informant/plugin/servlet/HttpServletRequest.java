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
package io.informant.plugin.servlet;

import io.informant.api.UnresolvedMethod;
import io.informant.shaded.google.common.collect.ImmutableMap;

import java.util.Map;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// HttpServletRequest wrapper does not make assumptions about the @Nullable properties of the
// underlying javax.servlet.http.HttpServletRequest since it's just an interface and could
// theoretically return null even where it seems to not make sense
@NotThreadSafe
class HttpServletRequest {

    private static final UnresolvedMethod getSessionOneArgMethod = UnresolvedMethod.from(
            "javax.servlet.http.HttpServletRequest", "getSession",
            boolean.class);
    private static final UnresolvedMethod getMethodMethod = UnresolvedMethod.from(
            "javax.servlet.http.HttpServletRequest", "getMethod");
    private static final UnresolvedMethod getRequestURIMethod = UnresolvedMethod.from(
            "javax.servlet.http.HttpServletRequest", "getRequestURI");
    private static final UnresolvedMethod getParameterMapMethod = UnresolvedMethod.from(
            "javax.servlet.http.HttpServletRequest", "getParameterMap");
    private static final UnresolvedMethod getAttributeMethod = UnresolvedMethod.from(
            "javax.servlet.ServletRequest", "getAttribute");

    private final Object realRequest;

    static HttpServletRequest from(Object realRequest) {
        return new HttpServletRequest(realRequest);
    }

    private HttpServletRequest(Object realRequest) {
        this.realRequest = realRequest;
    }

    @Nullable
    HttpSession getSession(boolean create) {
        Object realSession = getSessionOneArgMethod.invokeWithDefaultOnError(realRequest, create,
                null);
        if (realSession == null) {
            return null;
        } else {
            return HttpSession.from(realSession);
        }
    }

    @Nullable
    String getMethod() {
        return getMethodMethod.invokeWithDefaultOnError(realRequest,
                "<error calling HttpServletRequest.getMethod()>");
    }

    @Nullable
    String getRequestURI() {
        return getRequestURIMethod.invokeWithDefaultOnError(realRequest,
                "<error calling HttpServletRequest.getRequestURI()>");
    }

    Map<?, ?> getParameterMap() {
        Map<?, ?> parameterMap = getParameterMapMethod.invokeWithDefaultOnError(
                realRequest, ImmutableMap.of());
        if (parameterMap == null) {
            return ImmutableMap.of();
        } else {
            return parameterMap;
        }
    }

    @Nullable
    String getAttribute(String name) {
        return getAttributeMethod.invokeWithDefaultOnError(realRequest, name,
                "<error calling ServletRequest.getAttribute()>");
    }
}
