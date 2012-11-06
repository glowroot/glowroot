/**
 * Copyright 2011-2012 the original author or authors.
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

import io.informant.api.ErrorMessage;
import io.informant.api.MessageSupplier;
import io.informant.api.Metric;
import io.informant.api.PluginServices;
import io.informant.api.PointcutStackTrace;
import io.informant.api.Span;
import io.informant.api.weaving.Aspect;
import io.informant.api.weaving.InjectMethodArg;
import io.informant.api.weaving.InjectReturn;
import io.informant.api.weaving.InjectTarget;
import io.informant.api.weaving.InjectThrowable;
import io.informant.api.weaving.InjectTraveler;
import io.informant.api.weaving.IsEnabled;
import io.informant.api.weaving.OnAfter;
import io.informant.api.weaving.OnBefore;
import io.informant.api.weaving.OnReturn;
import io.informant.api.weaving.OnThrow;
import io.informant.api.weaving.Pointcut;
import io.informant.shaded.google.common.base.Objects;
import io.informant.shaded.google.common.collect.ImmutableMap;

import java.util.Enumeration;
import java.util.Map.Entry;
import java.util.Set;

import javax.annotation.Nullable;

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
@Aspect
public class ServletAspect {

    private static final PluginServices pluginServices = PluginServices
            .get("io.informant.plugins:servlet-plugin");

    private static final ThreadLocal<ServletMessageSupplier> topLevel =
            new ThreadLocal<ServletMessageSupplier>();

    // the life of this thread local is tied to the life of the topLevel thread local
    // it is only created if the topLevel thread local exists, and it is cleared when topLevel
    // thread local is cleared
    private static final ThreadLocal<ErrorMessage> sendError = new ThreadLocal<ErrorMessage>();

    @Pointcut(typeName = "javax.servlet.Servlet", methodName = "service",
            methodArgs = { "javax.servlet.ServletRequest", "javax.servlet.ServletResponse" },
            metricName = "http request")
    public static class ServletAdvice {
        private static final Metric metric = pluginServices.getMetric(ServletAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            // only enabled if it is not contained in another servlet or filter span
            return pluginServices.isEnabled() && topLevel.get() == null;
        }
        @OnBefore
        public static Span onBefore(@InjectMethodArg Object realRequest) {
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
            Span span = pluginServices.startTrace(messageSupplier, metric);
            if (session != null) {
                String sessionUserIdAttributePath = ServletPluginProperties
                        .sessionUserIdAttributePath();
                if (sessionUserIdAttributePath != null) {
                    // capture user id now, don't use a lazy supplier
                    String userId = getSessionAttributeTextValue(session,
                            sessionUserIdAttributePath);
                    pluginServices.setUserId(userId);
                }
            }
            return span;
        }
        @OnThrow
        public static void onThrow(@InjectThrowable Throwable t, @InjectTraveler Span span) {
            // ignoring potential sendError since this seems worse
            span.endWithError(ErrorMessage.from(t));
            sendError.remove();
            topLevel.remove();
        }
        @OnReturn
        public static void onReturn(@InjectTraveler Span span) {
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
        public static Span onBefore(@InjectMethodArg Object realRequest) {
            return ServletAdvice.onBefore(realRequest);
        }
        @OnThrow
        public static void onThrow(@InjectThrowable Throwable t, @InjectTraveler Span span) {
            ServletAdvice.onThrow(t, span);
        }
        @OnReturn
        public static void onReturn(@InjectTraveler Span span) {
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
        public static Span onBefore(@InjectMethodArg Object realRequest) {
            return ServletAdvice.onBefore(realRequest);
        }
        @OnThrow
        public static void onThrow(@InjectThrowable Throwable t, @InjectTraveler Span span) {
            ServletAdvice.onThrow(t, span);
        }
        @OnReturn
        public static void onReturn(@InjectTraveler Span span) {
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
        public static void onAfter(@InjectTarget Object realRequest) {
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
        public static void onReturn(@InjectReturn @Nullable Object realSession) {
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
        public static void onBefore(@InjectTarget Object realSession) {
            HttpSession session = HttpSession.from(realSession);
            ServletMessageSupplier messageSupplier = getServletMessageSupplier(session);
            if (messageSupplier != null) {
                messageSupplier.setSessionIdUpdatedValue("");
            }
        }
    }

    // TODO support deprecated HttpSession.putValue()

    @Pointcut(typeName = "javax.servlet.http.HttpSession", methodName = "setAttribute",
            methodArgs = { "java.lang.String", "java.lang.Object" })
    public static class SetAttributeAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnAfter
        public static void onAfter(@InjectTarget Object realSession, @InjectMethodArg String name,
                @InjectMethodArg @Nullable Object value) {

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
        public static void onAfter(@InjectTarget Object realSession, @InjectMethodArg String name) {
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
        private static final Metric metric = pluginServices
                .getMetric(ContextInitializedAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled() && ServletPluginProperties.captureStartup();
        }
        @OnBefore
        @Nullable
        public static Span onBefore(@InjectTarget Object listener) {
            return pluginServices.startTrace(MessageSupplier.from("servlet context initialized"
                    + " ({{listener}})", listener.getClass().getName()), metric);
        }
        @OnThrow
        public static void onThrow(@InjectThrowable Throwable t, @InjectTraveler Span span) {
            span.endWithError(ErrorMessage.from(t));
        }
        @OnReturn
        public static void onReturn(@InjectTraveler Span span) {
            span.end();
        }
    }

    @Pointcut(typeName = "javax.servlet.Servlet", methodName = "init",
            methodArgs = { "javax.servlet.ServletConfig" }, metricName = "servlet startup")
    public static class ServletInitAdvice {
        private static final Metric metric = pluginServices.getMetric(ServletInitAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled() && ServletPluginProperties.captureStartup();
        }
        @OnBefore
        public static Span onBefore(@InjectTarget Object servlet) {
            return pluginServices.startTrace(MessageSupplier.from("servlet init ({{filter}})",
                    servlet.getClass().getName()), metric);
        }
        @OnThrow
        public static void onThrow(@InjectThrowable Throwable t, @InjectTraveler Span span) {
            span.endWithError(ErrorMessage.from(t));
        }
        @OnReturn
        public static void onReturn(@InjectTraveler Span span) {
            span.end();
        }
    }

    @Pointcut(typeName = "javax.servlet.Filter", methodName = "init",
            methodArgs = { "javax.servlet.FilterConfig" }, metricName = "servlet startup")
    public static class FilterInitAdvice {
        private static final Metric metric = pluginServices.getMetric(FilterInitAdvice.class);

        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled() && ServletPluginProperties.captureStartup();
        }
        @OnBefore
        public static Span onBefore(@InjectTarget Object filter) {
            return pluginServices.startTrace(MessageSupplier.from("filter init ({{filter}})",
                    filter.getClass().getName()), metric);
        }
        @OnThrow
        public static void onThrow(@InjectThrowable Throwable t, @InjectTraveler Span span) {
            span.endWithError(ErrorMessage.from(t));
        }
        @OnReturn
        public static void onReturn(@InjectTraveler Span span) {
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
        public static void onAfter(Integer statusCode) {
            // only capture 5xx server errors
            if (statusCode >= 500 && topLevel.get() != null) {
                sendError.set(ErrorMessage.from("sendError, HTTP status code " + statusCode,
                        new PointcutStackTrace(SendErrorAdvice.class)));
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
        if (sessionUserIdAttributePath != null) {
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

        if (ServletPluginProperties.sessionAttributeNames().contains(name)
                || ServletPluginProperties.sessionAttributeNames().contains("*")) {
            // update all session attributes (possibly nested) at or under the set attribute
            for (String path : ServletPluginProperties.sessionAttributePaths()) {
                if (path.equals(name) || path.equals("*")) {
                    if (value == null) {
                        messageSupplier.putSessionAttributeChangedValue(name, null);
                    } else {
                        messageSupplier.putSessionAttributeChangedValue(name, value.toString());
                    }
                } else if (path.startsWith(name + ".")) {
                    if (path.endsWith(".*")) {
                        path = path.substring(0, path.length() - 2);
                        Object val = getSessionAttribute(session, path);
                        if (val == null) {
                            messageSupplier.putSessionAttributeChangedValue(path, null);
                        } else {
                            for (Entry<String, String> entry : Beans.propertiesAsText(val)
                                    .entrySet()) {
                                messageSupplier.putSessionAttributeChangedValue(
                                        path + "." + entry.getKey(), entry.getValue());
                            }
                        }
                    } else if (value == null) {
                        // no need to navigate path since it will always be null
                        messageSupplier.putSessionAttributeChangedValue(path, null);
                    } else {
                        String val = getSessionAttributeTextValue(session, path);
                        messageSupplier.putSessionAttributeChangedValue(path, val);
                    }
                }
            }
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
        Set<String> sessionAttributePaths = ServletPluginProperties.sessionAttributePaths();
        if (sessionAttributePaths.isEmpty()) {
            return null;
        }
        ImmutableMap.Builder<String, String> sessionAttributeMap = ImmutableMap.builder();
        // dump only http session attributes in list
        for (String attributePath : sessionAttributePaths) {
            if (attributePath.equals("*")) {
                for (Enumeration<?> e = session.getAttributeNames(); e.hasMoreElements();) {
                    String attributeName = (String) e.nextElement();
                    Object value = session.getAttribute(attributeName);
                    // value shouldn't be null, but its (remotely) possible that a concurrent
                    // request for the same session just removed the attribute
                    String valueString = value == null ? "" : value.toString();
                    // taking no chances on value.toString() possibly returning null
                    sessionAttributeMap.put(attributeName, Objects.firstNonNull(valueString, ""));
                }
            } else if (attributePath.endsWith(".*")) {
                attributePath = attributePath.substring(0, attributePath.length() - 2);
                Object value = getSessionAttribute(session, attributePath);
                if (value != null) {
                    for (Entry<String, String> entry : Beans.propertiesAsText(value).entrySet()) {
                        sessionAttributeMap.put(attributePath + "." + entry.getKey(),
                                entry.getValue());
                    }
                }
            } else {
                String value = getSessionAttributeTextValue(session, attributePath);
                if (value != null) {
                    sessionAttributeMap.put(attributePath, value);
                }
            }
        }
        return sessionAttributeMap.build();
    }

    @Nullable
    private static String getSessionAttributeTextValue(HttpSession session, String attributePath) {
        Object value = getSessionAttribute(session, attributePath);
        return (value == null) ? null : value.toString();
    }

    @Nullable
    private static Object getSessionAttribute(HttpSession session,
            String attributePath) {

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
