/*
 * Copyright 2011-2019 the original author or authors.
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
import java.util.Collections;
import java.util.Map;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.AuxThreadContext;
import org.glowroot.agent.plugin.api.OptionalThreadContext;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.ThreadContext.Priority;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.checker.Nullable;
import org.glowroot.agent.plugin.api.util.FastThreadLocal;
import org.glowroot.agent.plugin.api.weaving.BindClassMeta;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindReceiver;
import org.glowroot.agent.plugin.api.weaving.BindReturn;
import org.glowroot.agent.plugin.api.weaving.BindThrowable;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.OnThrow;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.servlet._.RequestHostAndPortDetail;
import org.glowroot.agent.plugin.servlet._.RequestInvoker;
import org.glowroot.agent.plugin.servlet._.ResponseInvoker;
import org.glowroot.agent.plugin.servlet._.SendError;
import org.glowroot.agent.plugin.servlet._.ServletMessageSupplier;
import org.glowroot.agent.plugin.servlet._.ServletPluginProperties;
import org.glowroot.agent.plugin.servlet._.ServletPluginProperties.SessionAttributePath;
import org.glowroot.agent.plugin.servlet._.Strings;

// this plugin is careful not to rely on request or session objects being thread-safe
public class ServletAspect {

    @Pointcut(className = "javax.servlet.Servlet", methodName = "service",
            methodParameterTypes = {"javax.servlet.ServletRequest",
                    "javax.servlet.ServletResponse"},
            nestingGroup = "outer-servlet-or-filter", timerName = "http request")
    public static class ServiceAdvice {
        private static final TimerName timerName = Agent.getTimerName(ServiceAdvice.class);
        @OnBefore
        public static @Nullable TraceEntry onBefore(OptionalThreadContext context,
                @BindParameter @Nullable ServletRequest req,
                @BindClassMeta RequestInvoker requestInvoker) {
            return onBeforeCommon(context, req, null, requestInvoker);
        }
        @OnReturn
        public static void onReturn(OptionalThreadContext context,
                @BindTraveler @Nullable TraceEntry traceEntry,
                @SuppressWarnings("unused") @BindParameter @Nullable ServletRequest req,
                @BindParameter @Nullable ServletResponse res,
                @BindClassMeta ResponseInvoker responseInvoker) {
            if (traceEntry == null) {
                return;
            }
            if (!(res instanceof HttpServletResponse)) {
                // seems nothing sensible to do here other than ignore
                return;
            }
            ServletMessageSupplier messageSupplier =
                    (ServletMessageSupplier) context.getServletRequestInfo();
            if (messageSupplier != null && responseInvoker.hasGetStatusMethod()) {
                messageSupplier.setResponseCode(responseInvoker.getStatus(res));
            }
            FastThreadLocal.Holder</*@Nullable*/ String> errorMessageHolder =
                    SendError.getErrorMessageHolder();
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
                @BindTraveler @Nullable TraceEntry traceEntry,
                @SuppressWarnings("unused") @BindParameter @Nullable ServletRequest req,
                @BindParameter @Nullable ServletResponse res) {
            if (traceEntry == null) {
                return;
            }
            if (res == null || !(res instanceof HttpServletResponse)) {
                // seems nothing sensible to do here other than ignore
                return;
            }
            ServletMessageSupplier messageSupplier =
                    (ServletMessageSupplier) context.getServletRequestInfo();
            if (messageSupplier != null) {
                // container will set this unless headers are already flushed
                messageSupplier.setResponseCode(500);
            }
            // ignoring potential sendError since this seems worse
            SendError.clearErrorMessage();
            traceEntry.endWithError(t);
            context.setServletRequestInfo(null);
        }
        private static @Nullable TraceEntry onBeforeCommon(OptionalThreadContext context,
                @Nullable ServletRequest req, @Nullable String transactionTypeOverride,
                RequestInvoker requestInvoker) {
            if (context.getServletRequestInfo() != null) {
                return null;
            }
            if (!(req instanceof HttpServletRequest)) {
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
            HttpSession session = request.getSession(false);
            String requestUri = Strings.nullToEmpty(request.getRequestURI());
            // don't convert null to empty, since null means no query string, while empty means
            // url ended with ? but nothing after that
            String requestQueryString = request.getQueryString();
            String requestMethod = Strings.nullToEmpty(request.getMethod());
            String requestContextPath = Strings.nullToEmpty(request.getContextPath());
            String requestServletPath = Strings.nullToEmpty(request.getServletPath());
            String requestPathInfo = request.getPathInfo();
            Map<String, Object> requestHeaders = DetailCapture.captureRequestHeaders(request);
            RequestHostAndPortDetail requestHostAndPortDetail =
                    DetailCapture.captureRequestHostAndPortDetail(request, requestInvoker);
            if (session == null) {
                messageSupplier = new ServletMessageSupplier(requestMethod, requestContextPath,
                        requestServletPath, requestPathInfo, requestUri, requestQueryString,
                        requestHeaders, requestHostAndPortDetail,
                        Collections.<String, String>emptyMap());
            } else {
                Map<String, String> sessionAttributes = HttpSessions.getSessionAttributes(session);
                messageSupplier = new ServletMessageSupplier(requestMethod, requestContextPath,
                        requestServletPath, requestPathInfo, requestUri, requestQueryString,
                        requestHeaders, requestHostAndPortDetail, sessionAttributes);
            }
            String user = null;
            if (session != null) {
                SessionAttributePath userAttributePath =
                        ServletPluginProperties.userAttributePath();
                if (userAttributePath != null) {
                    // capture user now, don't use a lazy supplier
                    Object val = HttpSessions.getSessionAttribute(session, userAttributePath);
                    user = val == null ? null : val.toString();
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
                @BindParameter @Nullable ServletRequest req,
                @BindClassMeta RequestInvoker requestInvoker) {
            return ServiceAdvice.onBeforeCommon(context, req, null, requestInvoker);
        }
        @OnReturn
        public static void onReturn(OptionalThreadContext context,
                @BindTraveler @Nullable TraceEntry traceEntry,
                @BindParameter @Nullable ServletRequest req,
                @BindParameter @Nullable ServletResponse res,
                @BindClassMeta ResponseInvoker responseInvoker) {
            ServiceAdvice.onReturn(context, traceEntry, req, res, responseInvoker);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t, OptionalThreadContext context,
                @BindTraveler @Nullable TraceEntry traceEntry,
                @BindParameter @Nullable ServletRequest req,
                @BindParameter @Nullable ServletResponse res) {
            ServiceAdvice.onThrow(t, context, traceEntry, req, res);
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
                @BindParameter @Nullable ServletRequest req,
                @BindClassMeta RequestInvoker requestInvoker) {
            return ServiceAdvice.onBeforeCommon(context, req, null, requestInvoker);
        }
        @OnReturn
        public static void onReturn(OptionalThreadContext context,
                @BindTraveler @Nullable TraceEntry traceEntry,
                @SuppressWarnings("unused") @BindParameter @Nullable String target,
                @SuppressWarnings("unused") @BindParameter @Nullable Object baseRequest,
                @BindParameter @Nullable ServletRequest req,
                @BindParameter @Nullable ServletResponse res,
                @BindClassMeta ResponseInvoker responseInvoker) {
            ServiceAdvice.onReturn(context, traceEntry, req, res, responseInvoker);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t, OptionalThreadContext context,
                @BindTraveler @Nullable TraceEntry traceEntry,
                @SuppressWarnings("unused") @BindParameter @Nullable String target,
                @SuppressWarnings("unused") @BindParameter @Nullable Object baseRequest,
                @BindParameter @Nullable ServletRequest req,
                @BindParameter @Nullable ServletResponse res) {
            ServiceAdvice.onThrow(t, context, traceEntry, req, res);
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
                @BindParameter @Nullable ServletRequest req,
                @BindClassMeta RequestInvoker requestInvoker) {
            return ServiceAdvice.onBeforeCommon(context, req, "WireMock", requestInvoker);
        }
        @OnReturn
        public static void onReturn(OptionalThreadContext context,
                @BindTraveler @Nullable TraceEntry traceEntry,
                @BindParameter @Nullable ServletRequest req,
                @BindParameter @Nullable ServletResponse res,
                @BindClassMeta ResponseInvoker responseInvoker) {
            ServiceAdvice.onReturn(context, traceEntry, req, res, responseInvoker);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t, OptionalThreadContext context,
                @BindTraveler @Nullable TraceEntry traceEntry,
                @BindParameter @Nullable ServletRequest req,
                @BindParameter @Nullable ServletResponse res) {
            ServiceAdvice.onThrow(t, context, traceEntry, req, res);
        }
    }

    @Pointcut(className = "javax.servlet.http.HttpServletResponse", methodName = "sendError",
            methodParameterTypes = {"int", ".."}, nestingGroup = "servlet-inner-call")
    public static class SendErrorAdvice {
        // wait until after because sendError throws IllegalStateException if the response has
        // already been committed
        @OnAfter
        public static void onAfter(ThreadContext context, @BindParameter int statusCode) {
            ServletMessageSupplier messageSupplier =
                    (ServletMessageSupplier) context.getServletRequestInfo();
            if (messageSupplier != null) {
                messageSupplier.setResponseCode(statusCode);
            }
            if (captureAsError(statusCode)) {
                FastThreadLocal.Holder</*@Nullable*/ String> errorMessageHolder =
                        SendError.getErrorMessageHolder();
                if (errorMessageHolder.get() == null) {
                    context.addErrorEntry("sendError, HTTP status code " + statusCode);
                    errorMessageHolder.set("sendError, HTTP status code " + statusCode);
                }
            }
        }

        private static boolean captureAsError(int statusCode) {
            return statusCode >= 500
                    || ServletPluginProperties.traceErrorOn4xxResponseCode() && statusCode >= 400;
        }
    }

    @Pointcut(className = "javax.servlet.http.HttpServletResponse", methodName = "sendRedirect",
            methodParameterTypes = {"java.lang.String"}, nestingGroup = "servlet-inner-call")
    public static class SendRedirectAdvice {
        // wait until after because sendError throws IllegalStateException if the response has
        // already been committed
        @OnAfter
        public static void onAfter(ThreadContext context,
                @BindReceiver HttpServletResponse response,
                @BindParameter @Nullable String location,
                @BindClassMeta ResponseInvoker responseInvoker) {
            ServletMessageSupplier messageSupplier =
                    (ServletMessageSupplier) context.getServletRequestInfo();
            if (messageSupplier != null) {
                messageSupplier.setResponseCode(302);
                if (responseInvoker.hasGetHeaderMethod()) {
                    // get the header as set by the container (e.g. after it converts relative to
                    // absolute path)
                    String header = responseInvoker.getHeader(response, "Location");
                    messageSupplier.addResponseHeader("Location", header);
                } else if (location != null) {
                    messageSupplier.addResponseHeader("Location", location);
                }
            }
        }
    }

    @Pointcut(className = "javax.servlet.http.HttpServletResponse", methodName = "setStatus",
            methodParameterTypes = {"int", ".."}, nestingGroup = "servlet-inner-call")
    public static class SetStatusAdvice {
        // wait until after because sendError throws IllegalStateException if the response has
        // already been committed
        @OnAfter
        public static void onAfter(ThreadContext context, @BindParameter int statusCode) {
            ServletMessageSupplier messageSupplier =
                    (ServletMessageSupplier) context.getServletRequestInfo();
            if (messageSupplier != null) {
                messageSupplier.setResponseCode(statusCode);
            }
            if (SendErrorAdvice.captureAsError(statusCode)) {
                FastThreadLocal.Holder</*@Nullable*/ String> errorMessageHolder =
                        SendError.getErrorMessageHolder();
                if (errorMessageHolder.get() == null) {
                    context.addErrorEntry("setStatus, HTTP status code " + statusCode);
                    errorMessageHolder.set("setStatus, HTTP status code " + statusCode);
                }
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
