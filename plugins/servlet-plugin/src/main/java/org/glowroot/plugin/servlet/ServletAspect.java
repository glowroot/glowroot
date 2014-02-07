/*
 * Copyright 2011-2014 the original author or authors.
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

import checkers.nullness.quals.Nullable;
import com.google.common.base.Strings;

import org.glowroot.api.CompletedSpan;
import org.glowroot.api.ErrorMessage;
import org.glowroot.api.MetricName;
import org.glowroot.api.PluginServices;
import org.glowroot.api.Span;
import org.glowroot.api.weaving.BindMethodArg;
import org.glowroot.api.weaving.BindThrowable;
import org.glowroot.api.weaving.BindTraveler;
import org.glowroot.api.weaving.IsEnabled;
import org.glowroot.api.weaving.OnAfter;
import org.glowroot.api.weaving.OnBefore;
import org.glowroot.api.weaving.OnReturn;
import org.glowroot.api.weaving.OnThrow;
import org.glowroot.api.weaving.Pointcut;

/**
 * Defines pointcuts and captures data on
 * {@link javax.servlet.http.HttpServlet#service(javax.servlet.ServletRequest, javax.servlet.ServletResponse)}
 * and
 * {@link javax.servlet.Filter#doFilter(javax.servlet.ServletRequest, javax.servlet.ServletResponse, javax.servlet.FilterChain)}
 * calls.
 * 
 * By default only the calls to the top-most Filter and to the top-most Servlet are captured.
 * 
 * This plugin is careful not to rely on request or session objects being thread-safe.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
// TODO add support for async servlets (servlet 3.0)
public class ServletAspect {

    private static final PluginServices pluginServices = PluginServices.get("servlet");

    private static final ThreadLocal</*@Nullable*/ServletMessageSupplier> topLevel =
            new ThreadLocal</*@Nullable*/ServletMessageSupplier>();

    // the life of this thread local is tied to the life of the topLevel thread local
    // it is only created if the topLevel thread local exists, and it is cleared when topLevel
    // thread local is cleared
    private static final ThreadLocal</*@Nullable*/ErrorMessage> sendError =
            new ThreadLocal</*@Nullable*/ErrorMessage>();

    @Pointcut(typeName = "javax.servlet.Servlet", methodName = "service",
            methodArgs = {"javax.servlet.ServletRequest", "javax.servlet.ServletResponse"},
            metricName = "http request")
    public static class ServiceAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(ServiceAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            // only enabled if it is not contained in another servlet or filter span
            return pluginServices.isEnabled() && topLevel.get() == null;
        }
        @OnBefore
        public static Span onBefore(@BindMethodArg Object realRequest) {
            HttpServletRequest request = HttpServletRequest.from(realRequest);
            // request parameter map is collected in GetParameterAdvice
            // session info is collected here if the request already has a session
            ServletMessageSupplier messageSupplier;
            // passing "false" so it won't create a session if the request doesn't already have one
            HttpSession session = request.getSession(false);
            String requestUri = Strings.nullToEmpty(request.getRequestURI());
            if (session == null) {
                messageSupplier = new ServletMessageSupplier(requestUri, null, null);
            } else {
                messageSupplier = new ServletMessageSupplier(requestUri, session.getId(),
                        session.getSessionAttributes());
            }
            topLevel.set(messageSupplier);
            Span span = pluginServices.startTrace(requestUri, messageSupplier, metricName);
            if (session != null) {
                String sessionUserIdAttributePath =
                        ServletPluginProperties.sessionUserIdAttributePath();
                if (!sessionUserIdAttributePath.equals("")) {
                    // capture user id now, don't use a lazy supplier
                    String userId =
                            session.getSessionAttributeTextValue(sessionUserIdAttributePath);
                    pluginServices.setUserId(userId);
                }
            }
            return span;
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t, @BindTraveler Span span) {
            // ignoring potential sendError since this seems worse
            sendError.remove();
            span.endWithError(ErrorMessage.from(t));
            topLevel.remove();
        }
        @OnReturn
        public static void onReturn(@BindTraveler Span span) {
            ErrorMessage errorMessage = sendError.get();
            if (errorMessage != null) {
                span.endWithError(errorMessage);
                sendError.remove();
            } else {
                span.end();
            }
            topLevel.remove();
        }
    }

    @Pointcut(typeName = "javax.servlet.http.HttpServlet", methodName = "do*", methodArgs = {
            "javax.servlet.http.HttpServletRequest", "javax.servlet.http.HttpServletResponse"},
            metricName = "http request")
    public static class DoMethodsAdvice extends ServiceAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return ServiceAdvice.isEnabled();
        }
        @OnBefore
        public static Span onBefore(@BindMethodArg Object realRequest) {
            return ServiceAdvice.onBefore(realRequest);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t, @BindTraveler Span span) {
            ServiceAdvice.onThrow(t, span);
        }
        @OnReturn
        public static void onReturn(@BindTraveler Span span) {
            ServiceAdvice.onReturn(span);
        }
    }

    @Pointcut(typeName = "javax.servlet.Filter", methodName = "doFilter", methodArgs = {
            "javax.servlet.ServletRequest", "javax.servlet.ServletResponse",
            "javax.servlet.FilterChain"}, metricName = "http request")
    public static class DoFilterAdvice extends ServiceAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return ServiceAdvice.isEnabled();
        }
        @OnBefore
        public static Span onBefore(@BindMethodArg Object realRequest) {
            return ServiceAdvice.onBefore(realRequest);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t, @BindTraveler Span span) {
            ServiceAdvice.onThrow(t, span);
        }
        @OnReturn
        public static void onReturn(@BindTraveler Span span) {
            ServiceAdvice.onReturn(span);
        }
    }

    @Pointcut(typeName = "javax.servlet.http.HttpServletResponse", methodName = "sendError",
            methodArgs = {"int", ".."}, captureNested = false)
    public static class SendErrorAdvice {
        @OnAfter
        public static void onAfter(@BindMethodArg Integer statusCode) {
            // only capture 5xx server errors
            if (statusCode >= 500 && topLevel.get() != null) {
                ErrorMessage errorMessage = ErrorMessage.from("sendError, HTTP status code "
                        + statusCode);
                CompletedSpan span = pluginServices.addErrorSpan(errorMessage);
                span.captureSpanStackTrace();
                sendError.set(errorMessage);
            }
        }
    }

    @Nullable
    static ServletMessageSupplier getServletMessageSupplier() {
        return topLevel.get();
    }
}
