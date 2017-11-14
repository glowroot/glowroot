/*
 * Copyright 2011-2017 the original author or authors.
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
package org.glowroot.agent.plugin.servlet;

import java.security.Principal;
import java.util.Enumeration;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.AuxThreadContext;
import org.glowroot.agent.plugin.api.OptionalThreadContext;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.ThreadContext.Priority;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.util.FastThreadLocal;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindReturn;
import org.glowroot.agent.plugin.api.weaving.BindThrowable;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.IsEnabled;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.OnThrow;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.api.weaving.Shim;

// this plugin is careful not to rely on request or session objects being thread-safe
public class ServletAspect {

    private static final FastThreadLocal</*@Nullable*/ String> sendError =
            new FastThreadLocal</*@Nullable*/ String>();

    @Shim("javax.servlet.http.HttpServletRequest")
    public interface HttpServletRequest {

        @Shim("javax.servlet.http.HttpSession getSession(boolean)")
        @Nullable
        HttpSession glowroot$getSession(boolean create);

        @Nullable
        String getMethod();

        @Nullable
        String getContextPath();

        @Nullable
        String getServletPath();

        @Nullable
        String getPathInfo();

        @Nullable
        String getRequestURI();

        @Nullable
        String getQueryString();

        @Nullable
        Enumeration</*@Nullable*/ String> getHeaderNames();

        @Nullable
        Enumeration</*@Nullable*/ String> getHeaders(String name);

        @Nullable
        String getHeader(String name);

        @Nullable
        Map</*@Nullable*/ String, /*@Nullable*/ String /*@Nullable*/ []> getParameterMap();

        @Nullable
        Enumeration<? extends /*@Nullable*/ Object> getParameterNames();

        @Nullable
        String /*@Nullable*/ [] getParameterValues(String name);

        @Nullable
        Object getAttribute(String name);

        void removeAttribute(String name);

        @Nullable
        String getRemoteAddr();

        @Nullable
        String getRemoteHost();
    }

    @Shim("javax.servlet.http.HttpSession")
    public interface HttpSession {

        @Nullable
        Object getAttribute(String name);

        @Nullable
        Enumeration<? extends /*@Nullable*/ Object> getAttributeNames();

        @Nullable
        String getId();
    }

    @Pointcut(className = "javax.servlet.Servlet", methodName = "service",
            methodParameterTypes = {"javax.servlet.ServletRequest",
                    "javax.servlet.ServletResponse"},
            nestingGroup = "outer-servlet-or-filter", timerName = "http request")
    public static class ServiceAdvice {
        private static final TimerName timerName = Agent.getTimerName(ServiceAdvice.class);
        @OnBefore
        public static @Nullable TraceEntry onBefore(OptionalThreadContext context,
                @BindParameter @Nullable Object req) {
            return onBeforeCommon(context, req, null);
        }
        @OnReturn
        public static void onReturn(OptionalThreadContext context,
                @BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry == null) {
                return;
            }
            FastThreadLocal.Holder</*@Nullable*/ String> errorMessageHolder = sendError.getHolder();
            String errorMessage = errorMessageHolder.get();
            if (errorMessage != null) {
                traceEntry.endWithError(errorMessage);
                errorMessageHolder.set(null);
            } else {
                traceEntry.end();
            }
            context.setServletRequestInfo(null);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t, OptionalThreadContext context,
                @BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry == null) {
                return;
            }
            // ignoring potential sendError since this seems worse
            sendError.set(null);
            traceEntry.endWithError(t);
            context.setServletRequestInfo(null);
        }
        private static @Nullable TraceEntry onBeforeCommon(OptionalThreadContext context,
                @Nullable Object req, @Nullable String transactionTypeOverride) {
            if (context.getServletRequestInfo() != null) {
                return null;
            }
            if (req == null || !(req instanceof HttpServletRequest)) {
                // seems nothing sensible to do here other than ignore
                return null;
            }
            HttpServletRequest request = (HttpServletRequest) req;
            AuxThreadContext auxContextObj = (AuxThreadContext) request
                    .getAttribute(AsyncServletAspect.GLOWROOT_AUX_CONTEXT_REQUEST_ATTRIBUTE);
            if (auxContextObj != null) {
                request.removeAttribute(AsyncServletAspect.GLOWROOT_AUX_CONTEXT_REQUEST_ATTRIBUTE);
                AuxThreadContext auxContext = auxContextObj;
                return auxContext.startAndMarkAsyncTransactionComplete();
            }
            // request parameter map is collected in GetParameterAdvice
            // session info is collected here if the request already has a session
            ServletMessageSupplier messageSupplier;
            HttpSession session = request.glowroot$getSession(false);
            String requestUri = Strings.nullToEmpty(request.getRequestURI());
            // don't convert null to empty, since null means no query string, while empty means
            // url ended with ? but nothing after that
            String requestQueryString = request.getQueryString();
            String requestMethod = Strings.nullToEmpty(request.getMethod());
            String requestContextPath = Strings.nullToEmpty(request.getContextPath());
            String requestServletPath = Strings.nullToEmpty(request.getServletPath());
            String requestPathInfo = request.getPathInfo();
            ImmutableMap<String, Object> requestHeaders =
                    DetailCapture.captureRequestHeaders(request);
            String requestRemoteAddr = DetailCapture.captureRequestRemoteAddr(request);
            String requestRemoteHost = DetailCapture.captureRequestRemoteHost(request);
            if (session == null) {
                messageSupplier = new ServletMessageSupplier(requestMethod, requestContextPath,
                        requestServletPath, requestPathInfo, requestUri, requestQueryString,
                        requestHeaders, requestRemoteAddr, requestRemoteHost,
                        ImmutableMap.<String, String>of());
            } else {
                ImmutableMap<String, String> sessionAttributes =
                        HttpSessions.getSessionAttributes(session);
                messageSupplier = new ServletMessageSupplier(requestMethod, requestContextPath,
                        requestServletPath, requestPathInfo, requestUri, requestQueryString,
                        requestHeaders, requestRemoteAddr, requestRemoteHost, sessionAttributes);
            }
            String user = null;
            if (session != null) {
                String sessionUserAttributePath =
                        ServletPluginProperties.sessionUserAttributePath();
                if (!sessionUserAttributePath.isEmpty()) {
                    // capture user now, don't use a lazy supplier
                    user = HttpSessions.getSessionAttributeTextValue(session,
                            sessionUserAttributePath);
                }
            }
            String transactionType;
            boolean setWithCoreMaxPriority = false;
            String transactionTypeHeader = request.getHeader("Glowroot-Transaction-Type");
            if ("Synthetic".equals(transactionTypeHeader)) {
                // Glowroot-Transaction-Type header currently only accepts "Synthetic", in order to
                // prevent spamming of transaction types, which could cause some issues
                transactionType = transactionTypeHeader;
                setWithCoreMaxPriority = true;
            } else if (transactionTypeOverride != null) {
                transactionType = transactionTypeOverride;
            } else {
                transactionType = "Web";
            }
            TraceEntry traceEntry = context.startTransaction(transactionType, requestUri,
                    messageSupplier, timerName);
            if (setWithCoreMaxPriority) {
                context.setTransactionType(transactionType, Priority.CORE_MAX);
            }
            context.setServletRequestInfo(messageSupplier);
            // Glowroot-Transaction-Name header is useful for automated tests which want to send a
            // more specific name for the transaction
            String transactionNameOverride = request.getHeader("Glowroot-Transaction-Name");
            if (transactionNameOverride != null) {
                context.setTransactionName(transactionNameOverride, Priority.CORE_MAX);
            }
            if (user != null) {
                context.setTransactionUser(user, Priority.CORE_PLUGIN);
            }
            return traceEntry;
        }
    }

    @Pointcut(className = "javax.servlet.Filter", methodName = "doFilter",
            methodParameterTypes = {"javax.servlet.ServletRequest", "javax.servlet.ServletResponse",
                    "javax.servlet.FilterChain"},
            nestingGroup = "outer-servlet-or-filter", timerName = "http request")
    public static class DoFilterAdvice {
        @OnBefore
        public static @Nullable TraceEntry onBefore(OptionalThreadContext context,
                @BindParameter @Nullable Object request) {
            return ServiceAdvice.onBeforeCommon(context, request, null);
        }
        @OnReturn
        public static void onReturn(OptionalThreadContext context,
                @BindTraveler @Nullable TraceEntry traceEntry) {
            ServiceAdvice.onReturn(context, traceEntry);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t, OptionalThreadContext context,
                @BindTraveler @Nullable TraceEntry traceEntry) {
            ServiceAdvice.onThrow(t, context, traceEntry);
        }
    }

    @Pointcut(className = "org.eclipse.jetty.server.Handler"
            + "|wiremock.org.eclipse.jetty.server.Handler",
            subTypeRestriction = "/(?!org\\.eclipse\\.jetty.)"
                    + "(?!wiremock.org\\.eclipse\\.jetty.).*/",
            methodName = "handle",
            methodParameterTypes = {"java.lang.String",
                    "org.eclipse.jetty.server.Request|wiremock.org.eclipse.jetty.server.Request",
                    "javax.servlet.http.HttpServletRequest",
                    "javax.servlet.http.HttpServletResponse"},
            nestingGroup = "outer-servlet-or-filter", timerName = "http request")
    public static class JettyHandlerAdvice {
        @OnBefore
        public static @Nullable TraceEntry onBefore(OptionalThreadContext context,
                @SuppressWarnings("unused") @BindParameter @Nullable String target,
                @SuppressWarnings("unused") @BindParameter @Nullable Object baseRequest,
                @BindParameter @Nullable Object request) {
            return ServiceAdvice.onBeforeCommon(context, request, null);
        }
        @OnReturn
        public static void onReturn(OptionalThreadContext context,
                @BindTraveler @Nullable TraceEntry traceEntry) {
            ServiceAdvice.onReturn(context, traceEntry);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t, OptionalThreadContext context,
                @BindTraveler @Nullable TraceEntry traceEntry) {
            ServiceAdvice.onThrow(t, context, traceEntry);
        }
    }

    // this pointcut makes sure to only set the transaction type to WireMock if WireMock is the
    // first servlet encountered
    @Pointcut(className = "javax.servlet.Servlet",
            subTypeRestriction = "com.github.tomakehurst.wiremock.jetty9"
                    + ".JettyHandlerDispatchingServlet",
            methodName = "service",
            methodParameterTypes = {"javax.servlet.ServletRequest",
                    "javax.servlet.ServletResponse"},
            nestingGroup = "outer-servlet-or-filter", timerName = "http request", order = -1)
    public static class WireMockAdvice {
        @OnBefore
        public static @Nullable TraceEntry onBefore(OptionalThreadContext context,
                @BindParameter @Nullable Object request) {
            return ServiceAdvice.onBeforeCommon(context, request, "WireMock");
        }
        @OnReturn
        public static void onReturn(OptionalThreadContext context,
                @BindTraveler @Nullable TraceEntry traceEntry) {
            ServiceAdvice.onReturn(context, traceEntry);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t, OptionalThreadContext context,
                @BindTraveler @Nullable TraceEntry traceEntry) {
            ServiceAdvice.onThrow(t, context, traceEntry);
        }
    }

    @Pointcut(className = "javax.servlet.http.HttpServletResponse", methodName = "sendError",
            methodParameterTypes = {"int", ".."}, nestingGroup = "servlet-inner-call")
    public static class SendErrorAdvice {
        @OnAfter
        public static void onAfter(ThreadContext context, @BindParameter Integer statusCode) {
            FastThreadLocal.Holder</*@Nullable*/ String> errorMessageHolder = sendError.getHolder();
            if (captureAsError(statusCode) && errorMessageHolder.get() == null) {
                context.addErrorEntry("sendError, HTTP status code " + statusCode);
                errorMessageHolder.set("sendError, HTTP status code " + statusCode);
            }
        }

        private static boolean captureAsError(int statusCode) {
            return statusCode >= 500
                    || ServletPluginProperties.traceErrorOn4xxResponseCode() && statusCode >= 400;
        }
    }

    @Pointcut(className = "javax.servlet.http.HttpServletResponse", methodName = "setStatus",
            methodParameterTypes = {"int", ".."}, nestingGroup = "servlet-inner-call")
    public static class SetStatusAdvice {
        // using @IsEnabled like this avoids ThreadContext lookup for common case
        @IsEnabled
        public static boolean isEnabled(@BindParameter Integer statusCode) {
            return SendErrorAdvice.captureAsError(statusCode);
        }
        @OnAfter
        public static void onAfter(ThreadContext context, @BindParameter Integer statusCode) {
            FastThreadLocal.Holder</*@Nullable*/ String> errorMessageHolder = sendError.getHolder();
            if (errorMessageHolder.get() == null) {
                context.addErrorEntry("setStatus, HTTP status code " + statusCode);
                errorMessageHolder.set("setStatus, HTTP status code " + statusCode);
            }
        }
    }

    @Pointcut(className = "javax.servlet.http.HttpServletRequest", methodName = "getUserPrincipal",
            methodParameterTypes = {}, methodReturnType = "java.security.Principal",
            nestingGroup = "servlet-inner-call")
    public static class GetUserPrincipalAdvice {
        @OnReturn
        public static void onReturn(@BindReturn @Nullable Principal principal,
                ThreadContext context) {
            if (principal != null) {
                context.setTransactionUser(principal.getName(), Priority.CORE_PLUGIN);
            }
        }
    }

    @Pointcut(className = "javax.servlet.http.HttpServletRequest", methodName = "getSession",
            methodParameterTypes = {}, nestingGroup = "servlet-inner-call")
    public static class GetSessionAdvice {
        @OnReturn
        public static void onReturn(@BindReturn @Nullable HttpSession session,
                ThreadContext context) {
            if (session == null) {
                return;
            }
            if (ServletPluginProperties.sessionUserAttributeIsId()) {
                context.setTransactionUser(session.getId(), Priority.CORE_PLUGIN);
            }
            if (ServletPluginProperties.captureSessionAttributeNamesContainsId()) {
                ServletMessageSupplier messageSupplier =
                        (ServletMessageSupplier) context.getServletRequestInfo();
                if (messageSupplier != null) {
                    messageSupplier.putSessionAttributeChangedValue(
                            ServletPluginProperties.HTTP_SESSION_ID_ATTR, session.getId());
                }
            }
        }
    }

    @Pointcut(className = "javax.servlet.http.HttpServletRequest", methodName = "getSession",
            methodParameterTypes = {"boolean"}, nestingGroup = "servlet-inner-call")
    public static class GetSessionOneArgAdvice {
        @OnReturn
        public static void onReturn(@BindReturn @Nullable HttpSession session,
                ThreadContext context) {
            GetSessionAdvice.onReturn(session, context);
        }
    }

    @Pointcut(className = "javax.servlet.Servlet", methodName = "init",
            methodParameterTypes = {"javax.servlet.ServletConfig"})
    public static class ServiceInitAdvice {
        @OnBefore
        public static void onBefore() {
            ContainerStartup.initPlatformMBeanServer();
        }
    }
}
