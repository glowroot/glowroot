/**
 * Copyright 2011-2013 the original author or authors.
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

import io.informant.api.CompletedSpan;
import io.informant.api.ErrorMessage;
import io.informant.api.MessageSupplier;
import io.informant.api.MetricName;
import io.informant.api.PluginServices;
import io.informant.api.Span;
import io.informant.api.weaving.BindMethodArg;
import io.informant.api.weaving.BindReturn;
import io.informant.api.weaving.BindTarget;
import io.informant.api.weaving.BindThrowable;
import io.informant.api.weaving.BindTraveler;
import io.informant.api.weaving.IsEnabled;
import io.informant.api.weaving.OnAfter;
import io.informant.api.weaving.OnBefore;
import io.informant.api.weaving.OnReturn;
import io.informant.api.weaving.OnThrow;
import io.informant.api.weaving.Pointcut;
import io.informant.shaded.google.common.base.Strings;
import io.informant.shaded.google.common.collect.ImmutableMap;

import java.util.Enumeration;
import java.util.Map.Entry;
import java.util.Set;

import checkers.nullness.quals.Nullable;

/**
 * Defines pointcuts and captures data on
 * {@link javax.servlet.http.HttpServlet#service(javax.servlet.ServletRequest, javax.servlet.ServletResponse)}
 * and
 * {@link javax.servlet.Filter#doFilter(javax.servlet.ServletRequest, javax.servlet.ServletResponse, javax.servlet.FilterChain)}
 * calls.
 * 
 * By default only calls to the top-most Filter and to the top-most Servlet are captured.
 * 
 * This plugin is careful not to rely on request or session objects being thread-safe.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
// TODO add support for async servlets (servlet 3.0)
public class ServletAspect {

    private static final PluginServices pluginServices =
            PluginServices.get("io.informant.plugins:servlet-plugin");

    private static final ThreadLocal</*@Nullable*/ServletMessageSupplier> topLevel =
            new ThreadLocal</*@Nullable*/ServletMessageSupplier>();

    // the life of this thread local is tied to the life of the topLevel thread local
    // it is only created if the topLevel thread local exists, and it is cleared when topLevel
    // thread local is cleared
    private static final ThreadLocal</*@Nullable*/ErrorMessage> sendError =
            new ThreadLocal</*@Nullable*/ErrorMessage>();

    @Pointcut(typeName = "javax.servlet.Servlet", methodName = "service",
            methodArgs = { "javax.servlet.ServletRequest", "javax.servlet.ServletResponse" },
            metricName = "http request")
    public static class ServletAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(ServletAdvice.class);
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
            // passing "false" so it won't create a session if the request doesn't already have
            // one
            HttpSession session = request.getSession(false);
            if (session == null) {
                messageSupplier = new ServletMessageSupplier(request.getMethod(),
                        request.getRequestURI(), null, null);
            } else {
                messageSupplier = new ServletMessageSupplier(request.getMethod(),
                        request.getRequestURI(), session.getId(), getSessionAttributes(session));
            }
            topLevel.set(messageSupplier);
            Span span = pluginServices.startTrace(messageSupplier, metricName);
            if (session != null) {
                String sessionUserIdAttributePath = ServletPluginProperties
                        .sessionUserIdAttributePath();
                if (!sessionUserIdAttributePath.equals("")) {
                    // capture user id now, don't use a lazy supplier
                    String userId = getSessionAttributeTextValue(session,
                            sessionUserIdAttributePath);
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
            "javax.servlet.http.HttpServletRequest", "javax.servlet.http.HttpServletResponse" },
            metricName = "http request")
    public static class HttpServletAdvice extends ServletAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return ServletAdvice.isEnabled();
        }
        @OnBefore
        public static Span onBefore(@BindMethodArg Object realRequest) {
            return ServletAdvice.onBefore(realRequest);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t, @BindTraveler Span span) {
            ServletAdvice.onThrow(t, span);
        }
        @OnReturn
        public static void onReturn(@BindTraveler Span span) {
            ServletAdvice.onReturn(span);
        }
    }

    @Pointcut(typeName = "javax.servlet.Filter", methodName = "doFilter", methodArgs = {
            "javax.servlet.ServletRequest", "javax.servlet.ServletResponse",
            "javax.servlet.FilterChain" }, metricName = "http request")
    public static class FilterAdvice extends ServletAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return ServletAdvice.isEnabled();
        }
        @OnBefore
        public static Span onBefore(@BindMethodArg Object realRequest) {
            return ServletAdvice.onBefore(realRequest);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t, @BindTraveler Span span) {
            ServletAdvice.onThrow(t, span);
        }
        @OnReturn
        public static void onReturn(@BindTraveler Span span) {
            ServletAdvice.onReturn(span);
        }
    }

    /*
     * ================== Http Servlet Request Parameters ==================
     */

    private static final ThreadLocal<Boolean> inRequestGetParameterPointcut =
            new BooleanThreadLocal();

    @Pointcut(typeName = "javax.servlet.ServletRequest", methodName = "getParameter*",
            methodArgs = { ".." }, captureNested = false)
    public static class GetParameterAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnAfter
        public static void onAfter(@BindTarget Object realRequest) {
            if (inRequestGetParameterPointcut.get()) {
                return;
            }
            inRequestGetParameterPointcut.set(true);
            // only now is it safe to get parameters (if parameters are retrieved before this, it
            // could prevent a servlet from choosing to read the underlying stream instead of using
            // the getParameter* methods) see SRV.3.1.1 "When Parameters Are Available"
            try {
                ServletMessageSupplier messageSupplier = topLevel.get();
                if (messageSupplier != null && !messageSupplier.isRequestParameterMapCaptured()) {
                    // this request is being traced and the request parameter map hasn't been
                    // captured yet
                    HttpServletRequest request = HttpServletRequest.from(realRequest);
                    messageSupplier.captureRequestParameterMap(request.getParameterMap());
                }
            } finally {
                // taking no chances on re-setting thread local (thus the second try/finally)
                inRequestGetParameterPointcut.set(false);
            }
        }
    }

    /*
     * ================== Http Session Attributes ==================
     */

    @Pointcut(typeName = "javax.servlet.http.HttpServletRequest", methodName = "getSession",
            methodArgs = { ".." })
    public static class GetSessionAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnReturn
        public static void onReturn(@BindReturn @Nullable Object realSession) {
            if (realSession == null) {
                return;
            }
            HttpSession session = HttpSession.from(realSession);
            // either getSession(), getSession(true) or getSession(false) has triggered this
            // pointcut
            // after calls to the first two (no-arg, and passing true), a new session may have been
            // created (the third one -- passing false -- could be ignored but is harmless)
            ServletMessageSupplier messageSupplier = topLevel.get();
            if (messageSupplier != null && session.isNew()) {
                messageSupplier.setSessionIdUpdatedValue(session.getId());
            }
        }
    }

    @Pointcut(typeName = "javax.servlet.http.HttpSession", methodName = "invalidate")
    public static class SessionInvalidateAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static void onBefore(@BindTarget Object realSession) {
            HttpSession session = HttpSession.from(realSession);
            ServletMessageSupplier messageSupplier = getServletMessageSupplier(session);
            if (messageSupplier != null) {
                messageSupplier.setSessionIdUpdatedValue("");
            }
        }
    }

    @Pointcut(typeName = "javax.servlet.http.HttpSession", methodName = "setAttribute|putValue",
            methodArgs = { "java.lang.String", "java.lang.Object" })
    public static class SetAttributeAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnAfter
        public static void onAfter(@BindTarget Object realSession,
                @BindMethodArg @Nullable String name,
                @BindMethodArg @Nullable Object value) {
            if (name == null) {
                // theoretically possible, so just ignore
                return;
            }
            HttpSession session = HttpSession.from(realSession);
            // name is non-null per HttpSession.setAttribute() javadoc, but value may be null
            // (which per the javadoc is the same as calling removeAttribute())
            ServletMessageSupplier messageSupplier = getServletMessageSupplier(session);
            if (messageSupplier != null) {
                updateUserIdIfApplicable(name, value, session);
                updateSessionAttributesIfApplicable(messageSupplier, name, value, session);
            }
        }
    }

    @Pointcut(typeName = "javax.servlet.http.HttpSession", methodName = "removeAttribute",
            methodArgs = { "java.lang.String" })
    public static class RemoveAttributeAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnAfter
        public static void onAfter(@BindTarget Object realSession,
                @BindMethodArg @Nullable String name) {
            // calling HttpSession.setAttribute() with null value is the same as calling
            // removeAttribute(), per the setAttribute() javadoc
            SetAttributeAdvice.onAfter(realSession, name, null);
        }
    }

    /*
     * ================== Startup ==================
     */

    @Pointcut(typeName = "javax.servlet.ServletContextListener", methodName = "contextInitialized",
            methodArgs = { "javax.servlet.ServletContextEvent" }, metricName = "servlet startup")
    public static class ContextInitializedAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(ContextInitializedAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled() && ServletPluginProperties.captureStartup();
        }
        @OnBefore
        @Nullable
        public static Span onBefore(@BindTarget Object listener) {
            return pluginServices.startTrace(MessageSupplier.from("servlet context initialized"
                    + " ({})", listener.getClass().getName()), metricName);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t, @BindTraveler Span span) {
            span.endWithError(ErrorMessage.from(t));
        }
        @OnReturn
        public static void onReturn(@BindTraveler Span span) {
            span.end();
        }
    }

    @Pointcut(typeName = "javax.servlet.Servlet", methodName = "init",
            methodArgs = { "javax.servlet.ServletConfig" }, metricName = "servlet startup")
    public static class ServletInitAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(ServletInitAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled() && ServletPluginProperties.captureStartup();
        }
        @OnBefore
        public static Span onBefore(@BindTarget Object servlet) {
            return pluginServices.startTrace(MessageSupplier.from("servlet init ({})",
                    servlet.getClass().getName()), metricName);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t, @BindTraveler Span span) {
            span.endWithError(ErrorMessage.from(t));
        }
        @OnReturn
        public static void onReturn(@BindTraveler Span span) {
            span.end();
        }
    }

    @Pointcut(typeName = "javax.servlet.Filter", methodName = "init",
            methodArgs = { "javax.servlet.FilterConfig" }, metricName = "servlet startup")
    public static class FilterInitAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(FilterInitAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled() && ServletPluginProperties.captureStartup();
        }
        @OnBefore
        public static Span onBefore(@BindTarget Object filter) {
            return pluginServices.startTrace(MessageSupplier.from("filter init ({})",
                    filter.getClass().getName()), metricName);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t, @BindTraveler Span span) {
            span.endWithError(ErrorMessage.from(t));
        }
        @OnReturn
        public static void onReturn(@BindTraveler Span span) {
            span.end();
        }
    }

    /*
     * ================== Response Status Code ==================
     */

    @Pointcut(typeName = "javax.servlet.http.HttpServletResponse", methodName = "sendError",
            methodArgs = { "int", ".." })
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

    private static void updateUserIdIfApplicable(String name, @Nullable Object value,
            HttpSession session) {
        if (value == null) {
            // if user id value is set to null, don't clear it
            return;
        }
        String sessionUserIdAttributePath = ServletPluginProperties.sessionUserIdAttributePath();
        if (!sessionUserIdAttributePath.equals("")) {
            // capture user id now, don't use a lazy supplier
            if (sessionUserIdAttributePath.equals(name)) {
                pluginServices.setUserId(value.toString());
            } else if (sessionUserIdAttributePath.startsWith(name + ".")) {
                String userId = getSessionAttributeTextValue(session, sessionUserIdAttributePath);
                if (userId != null) {
                    // if user id is null, don't clear it by setting Suppliers.ofInstance(null)
                    pluginServices.setUserId(userId);
                }
            }
        }
    }

    private static void updateSessionAttributesIfApplicable(ServletMessageSupplier messageSupplier,
            String name, @Nullable Object value, HttpSession session) {
        if (ServletPluginProperties.captureSessionAttributeNames().contains(name)
                || ServletPluginProperties.captureSessionAttributeNames().contains("*")) {
            // update all session attributes (possibly nested) at or under the set attribute
            for (String capturePath : ServletPluginProperties.captureSessionAttributePaths()) {
                if (capturePath.equals(name) || capturePath.equals("*")) {
                    updateSessionAttribute(messageSupplier, name, value);
                } else if (capturePath.startsWith(name + ".")) {
                    updateNestedSessionAttributes(messageSupplier, capturePath, value,
                            session);
                }
            }
        }
    }

    private static void updateSessionAttribute(ServletMessageSupplier messageSupplier, String name,
            @Nullable Object value) {
        if (value == null) {
            messageSupplier.putSessionAttributeChangedValue(name, null);
        } else {
            messageSupplier.putSessionAttributeChangedValue(name, value.toString());
        }
    }

    private static void updateNestedSessionAttributes(ServletMessageSupplier messageSupplier,
            String capturePath, @Nullable Object value, HttpSession session) {
        if (capturePath.endsWith(".*")) {
            String capturePathBase = capturePath.substring(0, capturePath.length() - 2);
            Object val = getSessionAttribute(session, capturePathBase);
            if (val == null) {
                messageSupplier.putSessionAttributeChangedValue(capturePathBase, null);
            } else {
                for (Entry<String, String> entry : Beans.propertiesAsText(val)
                        .entrySet()) {
                    messageSupplier.putSessionAttributeChangedValue(
                            capturePathBase + "." + entry.getKey(), entry.getValue());
                }
            }
        } else if (value == null) {
            // no need to navigate path since it will always be null
            messageSupplier.putSessionAttributeChangedValue(capturePath, null);
        } else {
            String val = getSessionAttributeTextValue(session, capturePath);
            messageSupplier.putSessionAttributeChangedValue(capturePath, val);
        }
    }

    @Nullable
    private static ServletMessageSupplier getServletMessageSupplier(HttpSession session) {
        ServletMessageSupplier servletMessageSupplier = topLevel.get();
        if (servletMessageSupplier == null) {
            // this thread is not executing a servlet request, e.g. this could be a background
            // thread that is updating http session attributes
            return null;
        }
        String sessionId;
        if (servletMessageSupplier.getSessionIdUpdatedValue() != null) {
            sessionId = servletMessageSupplier.getSessionIdUpdatedValue();
        } else {
            sessionId = servletMessageSupplier.getSessionIdInitialValue();
        }
        if (session.getId().equals(sessionId)) {
            return servletMessageSupplier;
        } else {
            // the target session for this pointcut is not the same as the thread's
            // ServletMessageSupplier, e.g. this could be a request that is updating attributes on
            // a different http session
            return null;
        }
    }

    @Nullable
    private static ImmutableMap<String, String> getSessionAttributes(HttpSession session) {
        Set<String> capturePaths = ServletPluginProperties.captureSessionAttributePaths();
        if (capturePaths.isEmpty()) {
            return null;
        }
        ImmutableMap.Builder<String, String> captureMap = ImmutableMap.builder();
        // dump only http session attributes in list
        for (String capturePath : capturePaths) {
            if (capturePath.equals("*")) {
                for (Enumeration<?> e = session.getAttributeNames(); e.hasMoreElements();) {
                    String attributeName = (String) e.nextElement();
                    Object value = session.getAttribute(attributeName);
                    // value shouldn't be null, but its (remotely) possible that a concurrent
                    // request for the same session just removed the attribute
                    String valueString = value == null ? "" : value.toString();
                    // taking no chances on value.toString() possibly returning null
                    captureMap.put(attributeName, Strings.nullToEmpty(valueString));
                }
            } else if (capturePath.endsWith(".*")) {
                capturePath = capturePath.substring(0, capturePath.length() - 2);
                Object value = getSessionAttribute(session, capturePath);
                if (value != null) {
                    for (Entry<String, String> entry : Beans.propertiesAsText(value).entrySet()) {
                        captureMap.put(capturePath + "." + entry.getKey(), entry.getValue());
                    }
                }
            } else {
                String value = getSessionAttributeTextValue(session, capturePath);
                if (value != null) {
                    captureMap.put(capturePath, value);
                }
            }
        }
        return captureMap.build();
    }

    @Nullable
    private static String getSessionAttributeTextValue(HttpSession session, String attributePath) {
        Object value = getSessionAttribute(session, attributePath);
        return (value == null) ? null : value.toString();
    }

    @Nullable
    private static Object getSessionAttribute(HttpSession session, String attributePath) {
        if (attributePath.indexOf('.') == -1) {
            // fast path
            return session.getAttribute(attributePath);
        } else {
            String[] path = attributePath.split("\\.");
            Object curr = session.getAttribute(path[0]);
            return Beans.value(curr, path, 1);
        }
    }

    private static class BooleanThreadLocal extends ThreadLocal<Boolean> {
        @Override
        protected Boolean initialValue() {
            return false;
        }
    }
}
