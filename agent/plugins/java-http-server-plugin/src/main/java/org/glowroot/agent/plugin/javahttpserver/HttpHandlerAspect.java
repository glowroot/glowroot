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
package org.glowroot.agent.plugin.javahttpserver;

import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.Agent;
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

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;

// this plugin is careful not to rely on request or session objects being thread-safe
public class HttpHandlerAspect {

    @Shim("com.sun.net.httpserver.HttpExchange")
    public interface HttpExchange {

        @Nullable
        URI getRequestURI();

        @Nullable
        String getRequestMethod();

        @Shim("com.sun.net.httpserver.Headers getRequestHeaders()")
        @Nullable
        Headers glowroot$getRequestHeaders();

        @Shim("com.sun.net.httpserver.Headers getResponseHeaders()")
        @Nullable
        Headers glowroot$getResponseHeaders();

        @Nullable
        InetSocketAddress getRemoteAddress();

    }

    @Shim("com.sun.net.httpserver.Headers")
    public interface Headers extends Map<String, List<String>> {

        @Nullable
        String getFirst(String key);

    }

    private static final FastThreadLocal</*@Nullable*/ String> sendError =
            new FastThreadLocal</*@Nullable*/ String>();

    @Pointcut(className = "com.sun.net.httpserver.HttpHandler", methodName = "handle",
            methodParameterTypes = {"com.sun.net.httpserver.HttpExchange"},
            nestingGroup = "outer-handler-or-filter", timerName = "http request")
    public static class HandleAdvice {

        private HandleAdvice() {
            throw new IllegalAccessError();
        }

        private static final TimerName timerName = Agent.getTimerName(HandleAdvice.class);

        @OnBefore
        public static @Nullable TraceEntry onBefore(OptionalThreadContext context,
                @BindParameter @Nullable HttpExchange exchange) {
            return onBeforeCommon(context, exchange, null);
        }

        @OnReturn
        public static void onReturn(@BindTraveler @Nullable TraceEntry traceEntry, @BindParameter @Nullable HttpExchange exchange) {
            if (traceEntry == null) {
                return;
            }
            FastThreadLocal.Holder</*@Nullable*/ String> errorMessageHolder = sendError.getHolder();
            String errorMessage = errorMessageHolder.get();
            setResponseHeaders(exchange, traceEntry.getMessageSupplier());
            if (errorMessage != null) {
                traceEntry.endWithError(errorMessage);
                errorMessageHolder.set(null);
            } else {
                traceEntry.end();
            }
        }

        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable TraceEntry traceEntry, @BindParameter @Nullable HttpExchange exchange) {
            if (traceEntry == null) {
                return;
            }
            // ignoring potential sendError since this seems worse
            sendError.set(null);
            setResponseHeaders(exchange, traceEntry.getMessageSupplier());
            traceEntry.endWithError(t);
        }

        private static void setResponseHeaders(@Nullable HttpExchange exchange, @Nullable Object messageSupplier) {
            if (exchange != null && messageSupplier instanceof HttpHandlerMessageSupplier) {
                ((HttpHandlerMessageSupplier) messageSupplier).setResponseHeaders(DetailCapture.captureResponseHeaders(exchange));
            }
        }

        private static @Nullable TraceEntry onBeforeCommon(OptionalThreadContext context,
                @Nullable HttpExchange exchange, @Nullable String transactionTypeOverride) {
            if (exchange == null) {
                // seems nothing sensible to do here other than ignore
                return null;
            }
            final String requestUri = getRequestURI(exchange.getRequestURI());
            final String requestQueryString = getRequestQueryString(exchange.getRequestURI());
            final String requestMethod = Strings.nullToEmpty(exchange.getRequestMethod());
            final ImmutableMap<String, Object> requestHeaders = DetailCapture.captureRequestHeaders(exchange);
            final String requestRemoteAddr = DetailCapture.captureRequestRemoteAddr(exchange);
            final String requestRemoteHost = DetailCapture.captureRequestRemoteHost(exchange);
            final HttpHandlerMessageSupplier messageSupplier = new HttpHandlerMessageSupplier(requestMethod, requestUri,
                        requestQueryString, requestHeaders, requestRemoteAddr, requestRemoteHost);
            String transactionType = "Web";
            boolean setWithCoreMaxPriority = false;
            final Headers headers = exchange.glowroot$getRequestHeaders();
            if (headers != null) {
                final String transactionTypeHeader = headers.getFirst("Glowroot-Transaction-Type");
                if (transactionTypeHeader != null && transactionTypeHeader.equals("Synthetic")) {
                    // Glowroot-Transaction-Type header currently only accepts "Synthetic", in order to
                    // prevent spamming of transaction types, which could cause some issues
                    transactionType = transactionTypeHeader;
                    setWithCoreMaxPriority = true;
                } else if (transactionTypeOverride != null) {
                    transactionType = transactionTypeOverride;
                }
            }
            final TraceEntry traceEntry = context.startTransaction(transactionType, requestUri,
                    messageSupplier, timerName);
            if (setWithCoreMaxPriority) {
                context.setTransactionType(transactionType, Priority.CORE_MAX);
            }
            // Glowroot-Transaction-Name header is useful for automated tests which want to send a
            // more specific name for the transaction
            if (headers != null) {
                final String transactionNameOverride = headers.getFirst("Glowroot-Transaction-Name");
                if (transactionNameOverride != null) {
                    context.setTransactionName(transactionNameOverride, Priority.CORE_MAX);
                }
            }
            return traceEntry;
        }

        private static String getRequestURI(@Nullable URI uri) {
            if (uri != null) {
                return Strings.nullToEmpty(uri.getPath());
            } else {
                return "";
            }
        }

        private static @Nullable String getRequestQueryString(@Nullable URI uri) {
            if (uri != null) {
                return uri.getQuery();
            } else {
                return null;
            }
        }
    }

    @Pointcut(className = "com.sun.net.httpserver.Filter", methodName = "doFilter",
            methodParameterTypes = {"com.sun.net.httpserver.HttpExchange", "com.sun.net.httpserver.Filter$Chain"},
            nestingGroup = "outer-handler-or-filter", timerName = "http request")
    public static class DoFilterAdvice {

        private DoFilterAdvice() {
            throw new IllegalAccessError();
        }

        @OnBefore
        public static @Nullable TraceEntry onBefore(OptionalThreadContext context,
                @BindParameter @Nullable HttpExchange exchange) {
            return HandleAdvice.onBeforeCommon(context, exchange, null);
        }

        @OnReturn
        public static void onReturn(@BindTraveler @Nullable TraceEntry traceEntry, @BindParameter @Nullable HttpExchange exchange) {
            HandleAdvice.onReturn(traceEntry, exchange);
        }

        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable TraceEntry traceEntry, @BindParameter @Nullable HttpExchange exchange) {
            HandleAdvice.onThrow(t, traceEntry, exchange);
        }
    }

    @Pointcut(className = "com.sun.net.httpserver.HttpExchange", methodName = "sendResponseHeaders",
            methodParameterTypes = {"int", "long"}, nestingGroup = "handler-inner-call")
    public static class SendResponseHeadersAdvice {

        private SendResponseHeadersAdvice() {
            throw new IllegalAccessError();
        }

        // using @IsEnabled like this avoids ThreadContext lookup for common case
        @IsEnabled
        public static boolean isEnabled(@BindParameter Integer statusCode) {
            return statusCode >= 500;
        }

        @OnAfter
        public static void onAfter(ThreadContext context, @BindParameter Integer statusCode) {
            FastThreadLocal.Holder</*@Nullable*/ String> errorMessageHolder = sendError.getHolder();
            if (errorMessageHolder.get() == null) {
                context.addErrorEntry("sendResponseHeaders, HTTP status code " + statusCode);
                errorMessageHolder.set("sendResponseHeaders, HTTP status code " + statusCode);
            }
        }
    }

    @Pointcut(className = "com.sun.net.httpserver.HttpExchange", methodName = "getPrincipal",
            methodParameterTypes = {}, methodReturnType = "com.sun.net.httpserver.HttpPrincipal",
            nestingGroup = "handler-inner-call")
    public static class GetPrincipalAdvice {

        private GetPrincipalAdvice() {
            throw new IllegalAccessError();
        }

        @OnReturn
        public static void onReturn(@BindReturn @Nullable Principal principal,
                ThreadContext context) {
            if (principal != null) {
                context.setTransactionUser(principal.getName(), Priority.CORE_PLUGIN);
            }
        }
    }
}
