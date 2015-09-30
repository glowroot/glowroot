/*
 * Copyright 2011-2015 the original author or authors.
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
import org.glowroot.agent.plugin.api.config.ConfigService;
import org.glowroot.agent.plugin.api.transaction.TimerName;
import org.glowroot.agent.plugin.api.transaction.TraceEntry;
import org.glowroot.agent.plugin.api.transaction.TransactionService;
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

// only the calls to the top-most Filter and to the top-most Servlet are captured
//
// this plugin is careful not to rely on request or session objects being thread-safe
public class ServletAspect {

    private static final TransactionService transactionService = Agent.getTransactionService();
    private static final ConfigService configService = Agent.getConfigService("servlet");

    private static final FastThreadLocal</*@Nullable*/ServletMessageSupplier> topLevel =
            new FastThreadLocal</*@Nullable*/ServletMessageSupplier>();

    // the life of this thread local is tied to the life of the topLevel thread local
    // it is only created if the topLevel thread local exists, and it is cleared when topLevel
    // thread local is cleared
    private static final FastThreadLocal</*@Nullable*/String> sendError =
            new FastThreadLocal</*@Nullable*/String>();

    @Shim("javax.servlet.http.HttpServletRequest")
    public interface HttpServletRequest {

        @Shim("javax.servlet.http.HttpSession getSession(boolean)")
        @Nullable
        HttpSession glowrootShimGetSession(boolean create);

        @Nullable
        String getRequestURI();

        @Nullable
        String getQueryString();

        @Nullable
        String getMethod();

        @Nullable
        Enumeration<String> getHeaderNames();

        @Nullable
        Enumeration<String> getHeaders(String name);

        @Nullable
        String getHeader(String name);

        @Nullable
        Map<String, String[]> getParameterMap();
    }

    @Shim("javax.servlet.http.HttpSession")
    public interface HttpSession {

        @Nullable
        Object getAttribute(String attributePath);

        @Nullable
        Enumeration<?> getAttributeNames();
    }

    @Pointcut(className = "javax.servlet.Servlet", methodName = "service",
            methodParameterTypes = {"javax.servlet.ServletRequest",
                    "javax.servlet.ServletResponse"},
            timerName = "http request")
    public static class ServiceAdvice {
        private static final TimerName timerName =
                transactionService.getTimerName(ServiceAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            // only enabled if it is not contained in another servlet or filter
            return topLevel.get() == null && configService.isEnabled();
        }
        @OnBefore
        public static @Nullable TraceEntry onBefore(@BindParameter @Nullable Object req) {
            if (req == null || !(req instanceof HttpServletRequest)) {
                // seems nothing sensible to do here other than ignore
                return null;
            }
            HttpServletRequest request = (HttpServletRequest) req;
            // request parameter map is collected in GetParameterAdvice
            // session info is collected here if the request already has a session
            ServletMessageSupplier messageSupplier;
            HttpSession session = request.glowrootShimGetSession(false);
            String requestUri = Strings.nullToEmpty(request.getRequestURI());
            // don't convert null to empty, since null means no query string, while empty means
            // url ended with ? but nothing after that
            String requestQueryString = request.getQueryString();
            String requestMethod = Strings.nullToEmpty(request.getMethod());
            ImmutableMap<String, Object> requestHeaders =
                    DetailCapture.captureRequestHeaders(request);
            if (session == null) {
                messageSupplier = new ServletMessageSupplier(requestMethod, requestUri,
                        requestQueryString, requestHeaders, ImmutableMap.<String, String>of());
            } else {
                ImmutableMap<String, String> sessionAttributes =
                        HttpSessions.getSessionAttributes(session);
                messageSupplier = new ServletMessageSupplier(requestMethod, requestUri,
                        requestQueryString, requestHeaders, sessionAttributes);
            }
            topLevel.set(messageSupplier);
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
            TraceEntry traceEntry = transactionService.startTransaction("Servlet", requestUri,
                    messageSupplier, timerName);
            // Glowroot-Transaction-Name header is useful for automated tests which want to send a
            // more specific name for the transaction
            String transactionNameOverride = request.getHeader("Glowroot-Transaction-Name");
            if (transactionNameOverride != null) {
                // using setTransactionName() instead of passing this into startTransaction() so
                // that it will be the first override and other overrides won't replace it
                transactionService.setTransactionName(transactionNameOverride);
            }
            if (user != null) {
                transactionService.setTransactionUser(user);
            }
            return traceEntry;
        }
        @OnReturn
        public static void onReturn(@BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry == null) {
                return;
            }
            String errorMessage = sendError.get();
            if (errorMessage != null) {
                traceEntry.endWithError(errorMessage);
                sendError.set(null);
            } else {
                traceEntry.end();
            }
            topLevel.set(null);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry == null) {
                return;
            }
            // ignoring potential sendError since this seems worse
            sendError.set(null);
            traceEntry.endWithError(t);
            topLevel.set(null);
        }
    }

    @Pointcut(className = "javax.servlet.http.HttpServlet", methodName = "do*",
            methodParameterTypes = {"javax.servlet.http.HttpServletRequest",
                    "javax.servlet.http.HttpServletResponse"},
            timerName = "http request")
    public static class DoMethodsAdvice extends ServiceAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return ServiceAdvice.isEnabled();
        }
        @OnBefore
        public static @Nullable TraceEntry onBefore(@BindParameter @Nullable Object request) {
            return ServiceAdvice.onBefore(request);
        }
        @OnReturn
        public static void onReturn(@BindTraveler @Nullable TraceEntry traceEntry) {
            ServiceAdvice.onReturn(traceEntry);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable TraceEntry traceEntry) {
            ServiceAdvice.onThrow(t, traceEntry);
        }
    }

    @Pointcut(className = "javax.servlet.Filter",
            methodName = "doFilter", methodParameterTypes = {"javax.servlet.ServletRequest",
                    "javax.servlet.ServletResponse", "javax.servlet.FilterChain"},
            timerName = "http request")
    public static class DoFilterAdvice extends ServiceAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return ServiceAdvice.isEnabled();
        }
        @OnBefore
        public static @Nullable TraceEntry onBefore(@BindParameter @Nullable Object request) {
            return ServiceAdvice.onBefore(request);
        }
        @OnReturn
        public static void onReturn(@BindTraveler @Nullable TraceEntry traceEntry) {
            ServiceAdvice.onReturn(traceEntry);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable TraceEntry traceEntry) {
            ServiceAdvice.onThrow(t, traceEntry);
        }
    }

    @Pointcut(className = "javax.servlet.http.HttpServletResponse", methodName = "sendError",
            methodParameterTypes = {"int", ".."})
    public static class SendErrorAdvice {
        @OnAfter
        public static void onAfter(@BindParameter Integer statusCode) {
            // only capture 5xx server errors
            if (statusCode >= 500 && topLevel.get() != null && sendError.get() == null) {
                transactionService.addErrorEntry("sendError, HTTP status code " + statusCode);
                sendError.set("sendError, HTTP status code " + statusCode);
            }
        }
    }

    // not using ignoreSelfNested since only needed in uncommon case of 5xx status codes
    // (at which time it is checked below)
    @Pointcut(className = "javax.servlet.http.HttpServletResponse", methodName = "setStatus",
            methodParameterTypes = {"int", ".."})
    public static class SetStatusAdvice {
        @OnAfter
        public static void onAfter(@BindParameter Integer statusCode) {
            // only capture 5xx server errors
            if (statusCode >= 500 && topLevel.get() != null && sendError.get() == null) {
                transactionService.addErrorEntry("setStatus, HTTP status code " + statusCode);
                sendError.set("setStatus, HTTP status code " + statusCode);
            }
        }
    }

    @Pointcut(className = "javax.servlet.http.HttpServletRequest", methodName = "getUserPrincipal",
            methodParameterTypes = {}, methodReturnType = "java.security.Principal")
    public static class GetUserPrincipalAdvice {
        @OnReturn
        public static void onReturn(@BindReturn Principal principal) {
            if (principal != null) {
                transactionService.setTransactionUser(principal.getName());
            }
        }
    }

    static @Nullable ServletMessageSupplier getServletMessageSupplier() {
        return topLevel.get();
    }
}
