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
package org.informantproject.plugin.servlet;

import java.util.Enumeration;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import org.informantproject.api.Message;
import org.informantproject.api.MessageSuppliers;
import org.informantproject.api.Metric;
import org.informantproject.api.PluginServices;
import org.informantproject.api.Span;
import org.informantproject.api.Supplier;
import org.informantproject.api.SupplierOfNullable;
import org.informantproject.api.weaving.Aspect;
import org.informantproject.api.weaving.InjectMethodArg;
import org.informantproject.api.weaving.InjectReturn;
import org.informantproject.api.weaving.InjectTarget;
import org.informantproject.api.weaving.InjectThrowable;
import org.informantproject.api.weaving.InjectTraveler;
import org.informantproject.api.weaving.IsEnabled;
import org.informantproject.api.weaving.OnAfter;
import org.informantproject.api.weaving.OnBefore;
import org.informantproject.api.weaving.OnReturn;
import org.informantproject.api.weaving.OnThrow;
import org.informantproject.api.weaving.Pointcut;
import org.informantproject.shaded.google.common.collect.Maps;

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

    private static final String CAPTURE_STARTUP_PROPERTY_NAME = "captureStartup";

    private static final PluginServices pluginServices = PluginServices
            .get("org.informantproject.plugins:servlet-plugin");

    private static final ThreadLocal<ServletMessageSupplier> topLevelServletMessageSupplier =
            new ThreadLocal<ServletMessageSupplier>();

    @Pointcut(typeName = "javax.servlet.Servlet", methodName = "service",
            methodArgs = { "javax.servlet.ServletRequest", "javax.servlet.ServletResponse" },
            metricName = "http request")
    public static class ServletAdvice {
        private static final Metric metric = pluginServices.getMetric(ServletAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            // only enabled if it is not contained in another servlet or filter span
            return pluginServices.isEnabled() && topLevelServletMessageSupplier.get() == null;
        }
        @OnBefore
        public static Span onBefore(@InjectMethodArg Object realRequest) {
            HttpServletRequest request = HttpServletRequest.from(realRequest);
            // request parameter map is collected in afterReturningRequestGetParameterPointcut()
            // session info is collected here if the request already has a session
            ServletMessageSupplier messageSupplier;
            // passing "false" so it won't create a session if the request doesn't already have one
            HttpSession session = request.getSession(false);
            if (session == null) {
                messageSupplier = new ServletMessageSupplier(request.getMethod(),
                        request.getRequestURI(), null, null);
            } else {
                messageSupplier = new ServletMessageSupplier(request.getMethod(),
                        request.getRequestURI(), session.getId(), getSessionAttributes(session));
            }
            topLevelServletMessageSupplier.set(messageSupplier);
            Span span = pluginServices.startTrace(messageSupplier, metric);
            if (session != null) {
                String sessionUsernameAttributePath = ServletPluginProperties
                        .sessionUsernameAttributePath();
                if (sessionUsernameAttributePath != null) {
                    // capture username now, don't use a lazy supplier
                    String username = getSessionAttributeTextValue(session,
                            sessionUsernameAttributePath);
                    pluginServices.setUsername(SupplierOfNullable.ofInstance(username));
                }
            }
            return span;
        }
        @OnThrow
        public static void onThrow(@InjectThrowable Throwable t, @InjectTraveler Span span) {
            span.endWithError(t);
            topLevelServletMessageSupplier.set(null);
        }
        @OnReturn
        public static void onReturn(@InjectTraveler Span span,
                @InjectMethodArg @SuppressWarnings("unused") Object realRequest,
                @InjectMethodArg Object realResponse) {

            span.end();
            topLevelServletMessageSupplier.set(null);

            HttpServletResponse response = HttpServletResponse.from(realResponse);
            int responseStatus = response.getStatus();
            pluginServices.putTraceAttribute("Response status code",
                    Integer.toString(responseStatus));
        }
    }

    @Pointcut(typeName = "javax.servlet.http.HttpServlet", methodName = "/do.*/", methodArgs = {
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
        public static void onReturn(@InjectTraveler Span span, @InjectMethodArg Object realRequest,
                @InjectMethodArg Object realResponse) {

            ServletAdvice.onReturn(span, realRequest, realResponse);
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
        public static void onReturn(@InjectTraveler Span span, @InjectMethodArg Object realRequest,
                @InjectMethodArg Object realResponse) {

            ServletAdvice.onReturn(span, realRequest, realResponse);
        }
    }

    /*
     * ================== Http Servlet Request Parameters ==================
     */

    private static final ThreadLocal<Boolean> inRequestGetParameterPointcut =
            new BooleanThreadLocal();

    @Pointcut(typeName = "javax.servlet.ServletRequest", methodName = "/getParameter.*/",
            methodArgs = { ".." })
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
                ServletMessageSupplier messageSupplier = topLevelServletMessageSupplier.get();
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
            ServletMessageSupplier messageSupplier = topLevelServletMessageSupplier.get();
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
            ServletMessageSupplier messageSupplier = getRootServletMessageSupplier(session);
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
            ServletMessageSupplier messageSupplier = getRootServletMessageSupplier(session);
            if (messageSupplier != null) {
                updateUsernameIfApplicable(name, value, session);
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
            return pluginServices.isEnabled()
                    && pluginServices.getBooleanProperty(CAPTURE_STARTUP_PROPERTY_NAME);
        }
        @OnBefore
        @Nullable
        public static Span onBefore(@InjectTarget Object listener) {
            return pluginServices.startTrace(MessageSuppliers.of("servlet context initialized"
                    + " ({{listener}})", listener.getClass().getName()), metric);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler Span span) {
            span.end();
        }
    }

    @Pointcut(typeName = "javax.servlet.Servlet", methodName = "init",
            methodArgs = { "javax.servlet.ServletConfig" }, metricName = "servlet startup")
    public static class ServletInitAdvice {
        private static final Metric metric = pluginServices.getMetric(ServletInitAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled()
                    && pluginServices.getBooleanProperty(CAPTURE_STARTUP_PROPERTY_NAME);
        }
        @OnBefore
        public static Span onBefore(@InjectTarget Object servlet) {
            return pluginServices.startTrace(MessageSuppliers.of("servlet init ({{filter}})",
                    servlet.getClass().getName()), metric);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler Span span) {
            span.end();
        }
    }

    @Pointcut(typeName = "javax.servlet.Filter", methodName = "init",
            methodArgs = { "javax.servlet.FilterConfig" }, metricName = "servlet startup")
    public static class FilterInitAdvice {
        private static final Metric metric = pluginServices.getMetric(FilterInitAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled()
                    && pluginServices.getBooleanProperty(CAPTURE_STARTUP_PROPERTY_NAME);
        }
        @OnBefore
        public static Span onBefore(@InjectTarget Object filter) {
            return pluginServices.startTrace(MessageSuppliers.of("filter init ({{filter}})",
                    filter.getClass().getName()), metric);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler Span span) {
            span.end();
        }
    }

    /*
     * ================== Response Status Code ==================
     */

    @Pointcut(typeName = "javax.servlet.http.HttpServletResponse", methodName = "setStatus",
            methodArgs = { "int" })
    public static class SetStatusAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static void onBefore(Integer sc) {
            if (sc >= 400) {
                pluginServices.addErrorSpan(
                        MessageSuppliers.of("HttpServletResponse.setStatus(" + sc + ")"),
                        new Throwable());
            }
        }
    }

    private static void updateUsernameIfApplicable(String name, @Nullable Object value,
            HttpSession session) {

        if (value == null) {
            // if username value is set to null, don't clear it
            return;
        }
        String sessionUsernameAttributePath = ServletPluginProperties
                .sessionUsernameAttributePath();
        if (sessionUsernameAttributePath != null) {
            // capture username now, don't use a lazy supplier
            if (sessionUsernameAttributePath.equals(name)) {
                pluginServices.setUsername(SupplierOfNullable.ofInstance(value.toString()));
            } else if (sessionUsernameAttributePath.startsWith(name + ".")) {
                String username = getSessionAttributeTextValue(session,
                        sessionUsernameAttributePath);
                if (username != null) {
                    // if username is absent, don't clear it by setting SupplierOfNullable.of(null)
                    pluginServices.setUsername(SupplierOfNullable.ofInstance(username));
                }
            }
        }
    }

    private static void updateSessionAttributesIfApplicable(ServletMessageSupplier messageSupplier,
            String name, @Nullable Object value, HttpSession session) {

        if (ServletPluginProperties.captureAllSessionAttributes()) {
            if (value == null) {
                messageSupplier.putSessionAttributeChangedValue(name, null);
            } else {
                messageSupplier.putSessionAttributeChangedValue(name, value.toString());
            }
        } else if (ServletPluginProperties.sessionAttributeNames().contains(name)) {
            // update all session attributes (possibly nested) at or under the set attribute
            for (String path : ServletPluginProperties.sessionAttributePaths()) {
                if (path.equals(name)) {
                    if (value == null) {
                        messageSupplier.putSessionAttributeChangedValue(path, null);
                    } else {
                        messageSupplier.putSessionAttributeChangedValue(path, value.toString());
                    }
                } else if (path.startsWith(name + ".")) {
                    if (value == null) {
                        // no need to navigate path since it will always be Optional.absent()
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
    private static ServletMessageSupplier getRootServletMessageSupplier(HttpSession session) {
        Supplier<Message> rootMessageSupplier = pluginServices.getRootMessageSupplier();
        if (!(rootMessageSupplier instanceof ServletMessageSupplier)) {
            return null;
        }
        ServletMessageSupplier rootServletMessageSupplier =
                (ServletMessageSupplier) rootMessageSupplier;
        String sessionId;
        if (rootServletMessageSupplier.getSessionIdUpdatedValue() != null) {
            sessionId = rootServletMessageSupplier.getSessionIdUpdatedValue();
        } else {
            sessionId = rootServletMessageSupplier.getSessionIdInitialValue();
        }
        if (session.getId().equals(sessionId)) {
            return rootServletMessageSupplier;
        } else {
            // the target session for this pointcut is not the same as the MessageSupplier
            return null;
        }
    }

    @Nullable
    private static Map<String, String> getSessionAttributes(HttpSession session) {
        Set<String> sessionAttributePaths = ServletPluginProperties.sessionAttributePaths();
        if (sessionAttributePaths.isEmpty()) {
            return null;
        }
        if (ServletPluginProperties.captureAllSessionAttributes()) {
            // special single value of "*" means dump all http session attributes
            Map<String, String> sessionAttributeMap = Maps.newHashMap();
            for (Enumeration<?> e = session.getAttributeNames(); e.hasMoreElements();) {
                String attributeName = (String) e.nextElement();
                Object value = session.getAttribute(attributeName);
                // value shouldn't be null, but its (remotely) possible that a concurrent request
                // for the same session just removed the attribute
                String valueString = value == null ? null : value.toString();
                sessionAttributeMap.put(attributeName, valueString);
            }
            return sessionAttributeMap;
        } else {
            Map<String, String> sessionAttributeMap = Maps
                    .newHashMapWithExpectedSize(sessionAttributePaths.size());
            // dump only http session attributes in list
            for (String attributePath : sessionAttributePaths) {
                String value = getSessionAttributeTextValue(session, attributePath);
                if (value != null) {
                    sessionAttributeMap.put(attributePath, value);
                }
            }
            return sessionAttributeMap;
        }
    }

    @Nullable
    private static String getSessionAttributeTextValue(HttpSession session, String attributePath) {
        if (attributePath.indexOf('.') == -1) {
            // fast path
            Object value = session.getAttribute(attributePath);
            if (value == null) {
                return null;
            } else {
                return value.toString();
            }
        } else {
            String[] path = attributePath.split("\\.");
            Object curr = session.getAttribute(path[0]);
            Object value = Beans.value(curr, path, 1);
            if (value == null) {
                return null;
            } else {
                return value.toString();
            }
        }
    }

    private static class BooleanThreadLocal extends ThreadLocal<Boolean> {
        @Override
        protected Boolean initialValue() {
            return false;
        }
    }
}
