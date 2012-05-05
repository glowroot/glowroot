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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.informantproject.api.Metric;
import org.informantproject.api.Optional;
import org.informantproject.api.PluginServices;
import org.informantproject.api.RootSpanDetail;
import org.informantproject.api.Span;
import org.informantproject.api.SpanContextMap;
import org.informantproject.api.SpanDetail;
import org.informantproject.api.weaving.Aspect;
import org.informantproject.api.weaving.InjectMethodArg;
import org.informantproject.api.weaving.InjectReturn;
import org.informantproject.api.weaving.InjectTarget;
import org.informantproject.api.weaving.InjectTraveler;
import org.informantproject.api.weaving.IsEnabled;
import org.informantproject.api.weaving.OnAfter;
import org.informantproject.api.weaving.OnBefore;
import org.informantproject.api.weaving.OnReturn;
import org.informantproject.api.weaving.Pointcut;

/**
 * Defines pointcuts and captures data on
 * {@link javax.servlet.http.HttpServlet#service(javax.servlet.ServletRequest, javax.servlet.ServletResponse)}
 * and
 * {@link javax.servlet.Filter#doFilter(javax.servlet.ServletRequest, javax.servlet.ServletResponse, javax.servlet.FilterChain)}
 * calls.
 * 
 * By default only calls to the top-most Filter and to the top-most Servlet are captured.
 * "isWarnOnSpanOutsideTrace" core configuration property can be used to enable capturing of nested
 * Filters and nested Servlets as well.
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

    private static final ThreadLocal<ServletSpanDetail> topLevelServletSpanDetail =
            new ThreadLocal<ServletSpanDetail>();

    @Pointcut(typeName = "javax.servlet.Servlet", methodName = "service", methodArgs = {
            "javax.servlet.ServletRequest", "javax.servlet.ServletResponse" },
            metricName = "http request")
    public static class ServletAdvice {
        private static final Metric metric = pluginServices.getMetric(ServletAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            // only enabled if it is not contained in another servlet or filter span
            return pluginServices.isEnabled() && topLevelServletSpanDetail.get() == null;
        }
        @OnBefore
        public static Span onBefore(@InjectMethodArg Object realRequest) {
            HttpServletRequest request = HttpServletRequest.from(realRequest);
            // request parameter map is collected in afterReturningRequestGetParameterPointcut()
            // session info is collected here if the request already has a session
            ServletSpanDetail spanDetail;
            // passing "false" so it won't create a session if the request doesn't already have one
            HttpSession session = request.getSession(false);
            if (session == null) {
                spanDetail = new ServletSpanDetail(request.getMethod(), request.getRequestURI(),
                        Optional.absent(String.class), null, null);
            } else {
                Optional<String> sessionUsernameAttributePath = ServletPluginPropertyUtils
                        .getSessionUsernameAttributePath();
                Optional<String> username = Optional.absent();
                if (sessionUsernameAttributePath.isPresent()) {
                    username = getSessionAttributeTextValue(session, sessionUsernameAttributePath
                            .get());
                }
                spanDetail = new ServletSpanDetail(request.getMethod(), request.getRequestURI(),
                        username, session.getId(), getSessionAttributes(session));
            }
            topLevelServletSpanDetail.set(spanDetail);
            return pluginServices.startRootSpan(metric, spanDetail);
        }
        @OnAfter
        public static void onAfter(@InjectTraveler Span span) {
            pluginServices.endSpan(span);
            topLevelServletSpanDetail.set(null);
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
        @OnAfter
        public static void onAfter(@InjectTraveler Span span) {
            ServletAdvice.onAfter(span);
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
        @OnAfter
        public static void onAfter(@InjectTraveler Span span) {
            ServletAdvice.onAfter(span);
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
                ServletSpanDetail spanDetail = topLevelServletSpanDetail.get();
                if (spanDetail != null && !spanDetail.isRequestParameterMapCaptured()) {
                    // this request is being traced and the request parameter map hasn't been
                    // captured yet
                    HttpServletRequest request = HttpServletRequest.from(realRequest);
                    spanDetail.captureRequestParameterMap(request.getParameterMap());
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
        public static void onReturn(@InjectReturn Object realSession) {
            HttpSession session = HttpSession.from(realSession);
            // either getSession(), getSession(true) or getSession(false) has triggered this
            // pointcut
            // after calls to the first two (no-arg, and passing true), a new session may have been
            // created (the third one -- passing false -- could be ignored but is harmless)
            ServletSpanDetail spanDetail = topLevelServletSpanDetail.get();
            if (spanDetail != null && session != null && session.isNew()) {
                spanDetail.setSessionIdUpdatedValue(session.getId());
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
            ServletSpanDetail spanDetail = getRootServletSpanDetail(session);
            if (spanDetail != null) {
                spanDetail.setSessionIdUpdatedValue("");
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
                @InjectMethodArg Object value) {

            HttpSession session = HttpSession.from(realSession);
            // name is non-null per HttpSession.setAttribute() javadoc, but value may be null
            // (which per the javadoc is the same as calling removeAttribute())
            ServletSpanDetail spanDetail = getRootServletSpanDetail(session);
            if (spanDetail != null) {
                updateUsernameIfApplicable(spanDetail, name, value, session);
                updateSessionAttributesIfApplicable(spanDetail, name, value, session);
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
        private static final Metric metric = pluginServices.getMetric(
                ContextInitializedAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Span onBefore(@InjectTarget Object listener) {
            if (pluginServices.getBooleanProperty(CAPTURE_STARTUP_PROPERTY_NAME)) {
                RootSpanDetail rootSpanDetail = new StartupRootSpanDetail(
                        "servlet context initialized (" + listener.getClass().getName() + ")");
                return pluginServices.startRootSpan(metric, rootSpanDetail);
            } else {
                return null;
            }
        }
        @OnAfter
        public static void onAfter(@InjectTraveler Span span) {
            if (span != null) {
                pluginServices.endSpan(span);
            }
        }
    }

    @Pointcut(typeName = "javax.servlet.Servlet", methodName = "init",
            methodArgs = { "javax.servlet.ServletConfig" }, metricName = "servlet startup")
    public static class ServletInitAdvice {
        private static final Metric metric = pluginServices.getMetric(ServletInitAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Span onBefore(@InjectTarget Object servlet) {
            if (pluginServices.getBooleanProperty(CAPTURE_STARTUP_PROPERTY_NAME)) {
                RootSpanDetail rootSpanDetail = new StartupRootSpanDetail("servlet init ("
                        + servlet.getClass().getName() + ")");
                return pluginServices.startRootSpan(metric, rootSpanDetail);
            } else {
                return null;
            }
        }
        @OnAfter
        public static void onAfter(@InjectTraveler Span span) {
            if (span != null) {
                pluginServices.endSpan(span);
            }
        }
    }

    @Pointcut(typeName = "javax.servlet.Filter", methodName = "init",
            methodArgs = { "javax.servlet.FilterConfig" }, metricName = "servlet startup")
    public static class FilterInitAdvice {
        private static final Metric metric = pluginServices.getMetric(FilterInitAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            return pluginServices.isEnabled();
        }
        @OnBefore
        public static Span onBefore(@InjectTarget Object filter) {
            if (pluginServices.getBooleanProperty(CAPTURE_STARTUP_PROPERTY_NAME)) {
                RootSpanDetail rootSpanDetail = new StartupRootSpanDetail("filter init ("
                        + filter.getClass().getName() + ")");
                return pluginServices.startRootSpan(metric, rootSpanDetail);
            } else {
                return null;
            }
        }
        @OnAfter
        public static void onAfter(@InjectTraveler Span span) {
            if (span != null) {
                pluginServices.endSpan(span);
            }
        }
    }

    private static void updateUsernameIfApplicable(ServletSpanDetail spanDetail, String name,
            Object value, HttpSession session) {

        if (value == null) {
            // if username value is set to null, don't clear it
            return;
        }
        Optional<String> sessionUsernameAttributePath = ServletPluginPropertyUtils
                .getSessionUsernameAttributePath();
        if (sessionUsernameAttributePath.isPresent()) {
            if (sessionUsernameAttributePath.get().equals(name)) {
                // it's unlikely, but possible, that toString() returns null
                spanDetail.setUsername(Optional.fromNullable(value.toString()));
            } else if (sessionUsernameAttributePath.get().startsWith(name + ".")) {
                Optional<String> val = getSessionAttributeTextValue(session,
                        sessionUsernameAttributePath.get());
                spanDetail.setUsername(val);
            }
        }
    }

    private static void updateSessionAttributesIfApplicable(ServletSpanDetail spanDetail,
            String name,
            Object value, HttpSession session) {

        if (ServletPluginPropertyUtils.isCaptureAllSessionAttributes()) {
            if (value == null) {
                spanDetail.putSessionAttributeChangedValue(name, Optional.absent(String.class));
            } else {
                // it's unlikely, but possible, that toString() returns null
                spanDetail.putSessionAttributeChangedValue(name, Optional.fromNullable(value
                        .toString()));
            }
        } else if (ServletPluginPropertyUtils.getSessionAttributeNames().contains(name)) {
            // update all session attributes (possibly nested) at or under the set attribute
            for (String path : ServletPluginPropertyUtils.getSessionAttributePaths()) {
                if (path.equals(name)) {
                    if (value == null) {
                        spanDetail.putSessionAttributeChangedValue(path, Optional.absent(
                                String.class));
                    } else {
                        // it's unlikely, but possible, that toString() returns null
                        spanDetail.putSessionAttributeChangedValue(path, Optional.fromNullable(
                                value.toString()));
                    }
                } else if (path.startsWith(name + ".")) {
                    if (value == null) {
                        // no need to navigate path since it will always be Optional.absent()
                        spanDetail.putSessionAttributeChangedValue(path, Optional.absent(
                                String.class));
                    } else {
                        Optional<String> val = getSessionAttributeTextValue(session, path);
                        spanDetail.putSessionAttributeChangedValue(path, val);
                    }
                }
            }
        }
    }

    private static ServletSpanDetail getRootServletSpanDetail(HttpSession session) {
        SpanDetail rootSpanDetail = pluginServices.getRootSpanDetail();
        if (!(rootSpanDetail instanceof ServletSpanDetail)) {
            return null;
        }
        ServletSpanDetail rootServletSpanDetail = (ServletSpanDetail) rootSpanDetail;
        String sessionId;
        if (rootServletSpanDetail.getSessionIdUpdatedValue() != null) {
            sessionId = rootServletSpanDetail.getSessionIdUpdatedValue();
        } else {
            sessionId = rootServletSpanDetail.getSessionIdInitialValue();
        }
        if (!session.getId().equals(sessionId)) {
            // the target session for this pointcut is not the same as the SpanDetail
            return null;
        }
        return rootServletSpanDetail;
    }

    private static Map<String, String> getSessionAttributes(HttpSession session) {
        Set<String> sessionAttributePaths = ServletPluginPropertyUtils.getSessionAttributePaths();
        if (sessionAttributePaths == null || sessionAttributePaths.isEmpty()) {
            return null;
        }
        if (ServletPluginPropertyUtils.isCaptureAllSessionAttributes()) {
            // special single value of "*" means dump all http session attributes
            Map<String, String> sessionAttributeMap = new HashMap<String, String>();
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
            Map<String, String> sessionAttributeMap = new HashMap<String, String>(
                    sessionAttributePaths.size());
            // dump only http session attributes in list
            for (String attributePath : sessionAttributePaths) {
                Optional<String> value = getSessionAttributeTextValue(session, attributePath);
                if (value.isPresent()) {
                    sessionAttributeMap.put(attributePath, value.get());
                }
            }
            return sessionAttributeMap;
        }
    }

    private static Optional<String> getSessionAttributeTextValue(HttpSession session,
            String attributePath) {

        if (attributePath.indexOf('.') == -1) {
            // fast path
            Object value = session.getAttribute(attributePath);
            if (value == null) {
                return Optional.absent();
            } else {
                return Optional.of(value.toString());
            }
        } else {
            String[] path = attributePath.split("\\.");
            Object curr = session.getAttribute(path[0]);
            Optional<?> value = PathUtil.getValue(curr, path, 1);
            if (value.isPresent()) {
                return Optional.of(value.get().toString());
            } else {
                return Optional.absent();
            }
        }
    }

    private static final class StartupRootSpanDetail implements RootSpanDetail {
        private final String description;
        private StartupRootSpanDetail(String description) {
            this.description = description;
        }
        public String getDescription() {
            return description;
        }
        public SpanContextMap getContextMap() {
            return null;
        }
        public Optional<String> getUsername() {
            return Optional.absent();
        }
    }

    private static class BooleanThreadLocal extends ThreadLocal<Boolean> {
        @Override
        protected Boolean initialValue() {
            return false;
        }
    }
}
