/*
 * Copyright 2016-2018 the original author or authors.
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
package org.glowroot.agent.plugin.spring;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.OptionalThreadContext;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.ThreadContext.Priority;
import org.glowroot.agent.plugin.api.ThreadContext.ServletRequestInfo;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.checker.Nullable;
import org.glowroot.agent.plugin.api.config.BooleanProperty;
import org.glowroot.agent.plugin.api.util.FastThreadLocal;
import org.glowroot.agent.plugin.api.util.FastThreadLocal.Holder;
import org.glowroot.agent.plugin.api.weaving.BindMethodMeta;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindReceiver;
import org.glowroot.agent.plugin.api.weaving.BindThrowable;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.Mixin;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.OnThrow;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.api.weaving.Shim;

public class ControllerAspect {

    private static final BooleanProperty useAltTransactionNaming =
            Agent.getConfigService("spring").getBooleanProperty("useAltTransactionNaming");

    private static final ConcurrentMap<String, String> normalizedPatterns =
            new ConcurrentHashMap<String, String>();

    private static final FastThreadLocal</*@Nullable*/ URI> webSocketUri =
            new FastThreadLocal</*@Nullable*/ URI>();

    private static final FastThreadLocal</*@Nullable*/ String> webSocketTransactionName =
            new FastThreadLocal</*@Nullable*/ String>();

    @Shim("org.springframework.web.servlet.mvc.method.RequestMappingInfo")
    public interface RequestMappingInfo {
        @Shim("org.springframework.web.servlet.mvc.condition.PatternsRequestCondition"
                + " getPatternsCondition()")
        @Nullable
        PatternsRequestCondition glowroot$getPatternsCondition();

        @Shim("org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition"
                + " getPathPatternsCondition()")
        @Nullable
        PathPatternsRequestCondition glowroot$getPathPatternsCondition();
    }

    @Shim("org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition")
    public interface PathPatternsRequestCondition {
        @Nullable
        Set<String> getPatternValues();
    }

    @Shim("org.springframework.web.servlet.mvc.condition.PatternsRequestCondition")
    public interface PatternsRequestCondition {
        @Nullable
        Set<String> getPatterns();
    }

    @Shim("org.springframework.web.socket.WebSocketSession")
    public interface WebSocketSession {
        @Nullable
        URI getUri();
    }

    @Shim("org.springframework.messaging.handler.invocation.AbstractMethodMessageHandler")
    public interface AbstractMethodMessageHandler {
        @Shim("java.lang.String getDestination(org.springframework.messaging.Message)")
        @Nullable
        String glowroot$getDestination(Object message);
    }

    @Shim("org.springframework.messaging.simp.SimpMessageMappingInfo")
    public interface SimpMessageMappingInfo {
        @Shim("org.springframework.messaging.handler.DestinationPatternsMessageCondition"
                + " getDestinationConditions()")
        @Nullable
        DestinationPatternsMessageCondition glowroot$getDestinationConditions();
    }

    @Shim("org.springframework.messaging.handler.DestinationPatternsMessageCondition")
    public interface DestinationPatternsMessageCondition {
        @Nullable
        Set<String> getPatterns();
    }

    // the field and method names are verbose since they will be mixed in to existing classes
    @Mixin({"org.springframework.messaging.support.ExecutorSubscribableChannel$SendTask",
            "org.springframework.messaging.support.ExecutorSubscribableChannel$1"})
    public static class WithWebSocketUriImpl implements WithWebSocketUriMixin {

        private transient @Nullable URI glowroot$webSocketUri;

        @Override
        public @Nullable URI glowroot$getWebSocketUri() {
            return glowroot$webSocketUri;
        }

        @Override
        public void glowroot$setWebSocketUri(@Nullable URI uri) {
            this.glowroot$webSocketUri = uri;
        }
    }

    // the field and method names are verbose since they will be mixed in to existing classes
    public interface WithWebSocketUriMixin {

        @Nullable
        URI glowroot$getWebSocketUri();

        void glowroot$setWebSocketUri(@Nullable URI uri);
    }

    @Pointcut(className = "org.springframework.web.servlet.handler.AbstractHandlerMethodMapping",
            methodName = "handleMatch", methodParameterTypes = {"java.lang.Object",
                    "java.lang.String", "javax.servlet.http.HttpServletRequest|jakarta.servlet.http.HttpServletRequest"})
    public static class HandlerMethodMappingAdvice {
        @OnBefore
        public static void onBefore(ThreadContext context,
                @BindParameter @Nullable Object mapping) {
            if (useAltTransactionNaming.value()) {
                return;
            }
            if (!(mapping instanceof RequestMappingInfo)) {
                return;
            }
            String pattern = getPattern((RequestMappingInfo) mapping);
            if (pattern == null) {
                return;
            }
            String prefix = getServletPath(context.getServletRequestInfo());
            if (pattern.isEmpty()) {
                context.setTransactionName(prefix, Priority.CORE_PLUGIN);
                return;
            }
            String normalizedPattern = normalizedPatterns.get(pattern);
            if (normalizedPattern == null) {
                normalizedPattern = pattern.replaceAll("\\{[^}]*\\}", "*");
                normalizedPatterns.put(pattern, normalizedPattern);
            }
            if (prefix == null || prefix.isEmpty()) {
                context.setTransactionName(normalizedPattern, Priority.CORE_PLUGIN);
            } else {
                context.setTransactionName(prefix + normalizedPattern, Priority.CORE_PLUGIN);
            }
        }
    }

    @Pointcut(className = "org.springframework.web.servlet.handler.AbstractUrlHandlerMapping",
            methodName = "exposePathWithinMapping", methodParameterTypes = {"java.lang.String",
                    "java.lang.String", "javax.servlet.http.HttpServletRequest|jakarta.servlet.http.HttpServletRequest"})
    public static class UrlHandlerMappingAdvice {
        @OnBefore
        public static void onBefore(ThreadContext context,
                @BindParameter @Nullable String bestMatchingPattern) {
            if (useAltTransactionNaming.value()) {
                return;
            }
            String prefix = getServletPath(context.getServletRequestInfo());
            if (bestMatchingPattern == null || bestMatchingPattern.isEmpty()) {
                context.setTransactionName(prefix, Priority.CORE_PLUGIN);
                return;
            }
            String normalizedPattern = normalizedPatterns.get(bestMatchingPattern);
            if (normalizedPattern == null) {
                normalizedPattern = bestMatchingPattern.replaceAll("\\{[^}]*\\}", "*");
                normalizedPatterns.put(bestMatchingPattern, normalizedPattern);
            }
            if (prefix == null || prefix.isEmpty()) {
                context.setTransactionName(normalizedPattern, Priority.CORE_PLUGIN);
            } else {
                context.setTransactionName(prefix + normalizedPattern, Priority.CORE_PLUGIN);
            }
        }
    }

    @Pointcut(classAnnotation = "org.springframework.stereotype.Controller"
            + "|org.springframework.web.bind.annotation.RestController",
            methodAnnotation = "org.springframework.web.bind.annotation.RequestMapping",
            methodParameterTypes = {".."}, timerName = "spring controller")
    public static class ControllerAdvice {
        private static final TimerName timerName = Agent.getTimerName(ControllerAdvice.class);
        @OnBefore
        public static TraceEntry onBefore(ThreadContext context,
                @BindMethodMeta ControllerMethodMeta controllerMethodMeta) {
            if (useAltTransactionNaming.value()) {
                context.setTransactionName(controllerMethodMeta.getAltTransactionName(),
                        Priority.CORE_PLUGIN);
            }
            return context.startTraceEntry(MessageSupplier.create("spring controller: {}.{}()",
                    controllerMethodMeta.getControllerClassName(),
                    controllerMethodMeta.getMethodName()), timerName);
        }
        @OnReturn
        public static void onReturn(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler TraceEntry traceEntry) {
            traceEntry.endWithError(t);
        }
    }

    @Pointcut(className = "org.springframework.web.socket.WebSocketHandler",
            methodName = "handleMessage",
            methodParameterTypes = {"org.springframework.web.socket.WebSocketSession", ".."})
    public static class HandleMessageAdvice {
        @OnBefore
        public static Holder</*@Nullable*/ URI> onBefore(@BindParameter WebSocketSession session) {
            Holder</*@Nullable*/ URI> holder = webSocketUri.getHolder();
            holder.set(session.getUri());
            return holder;
        }
        @OnAfter
        public static void onAfter(@BindTraveler Holder</*@Nullable*/ URI> holder) {
            holder.set(null);
        }
    }

    @Pointcut(className = "org.springframework.messaging.support.ExecutorSubscribableChannel$*",
            superTypeRestriction = "java.lang.Runnable", methodName = "<init>",
            methodParameterTypes = {".."})
    public static class SendTaskInitAdvice {
        @OnReturn
        public static void onReturn(@BindReceiver WithWebSocketUriMixin withWebSocketUri) {
            withWebSocketUri.glowroot$setWebSocketUri(webSocketUri.get());
        }
    }

    @Pointcut(className = "org.springframework.messaging.support.ExecutorSubscribableChannel$*",
            superTypeRestriction = "java.lang.Runnable", methodName = "run",
            methodParameterTypes = {})
    public static class SendTaskRunAdvice {
        @OnBefore
        public static Holder</*@Nullable*/ URI> onBefore(
                @BindReceiver WithWebSocketUriMixin withWebSocketUri) {
            Holder</*@Nullable*/ URI> holder = webSocketUri.getHolder();
            holder.set(withWebSocketUri.glowroot$getWebSocketUri());
            return holder;
        }
        @OnAfter
        public static void onAfter(@BindTraveler Holder</*@Nullable*/ URI> holder) {
            holder.set(null);
        }
    }

    @Pointcut(className = "org.springframework.messaging.simp.annotation.support"
            + ".SimpAnnotationMethodMessageHandler", methodName = "handleMatch",
            methodParameterTypes = {"java.lang.Object",
                    "org.springframework.messaging.handler.HandlerMethod", "java.lang.String",
                    "org.springframework.messaging.Message"})
    public static class WebSocketMappingAdvice {
        @OnBefore
        public static @Nullable Holder</*@Nullable*/ String> onBefore(
                @BindParameter @Nullable Object mapping,
                @SuppressWarnings("unused") @BindParameter @Nullable Object handlerMethod,
                @BindParameter @Nullable String lookupDestination,
                @BindParameter @Nullable Object message,
                @BindReceiver AbstractMethodMessageHandler messageHandler) {
            if (useAltTransactionNaming.value()) {
                return null;
            }
            if (!(mapping instanceof SimpMessageMappingInfo)) {
                return null;
            }
            DestinationPatternsMessageCondition patternCondition =
                    ((SimpMessageMappingInfo) mapping).glowroot$getDestinationConditions();
            if (patternCondition == null) {
                return null;
            }
            Set<String> patterns = patternCondition.getPatterns();
            if (patterns == null || patterns.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            URI uri = webSocketUri.get();
            if (uri != null) {
                sb.append(uri);
            }
            if (lookupDestination != null && message != null) {
                String destination = messageHandler.glowroot$getDestination(message);
                if (destination != null) {
                    sb.append(destination.substring(0,
                            destination.length() - lookupDestination.length()));
                }
            }
            String pattern = patterns.iterator().next();
            Holder</*@Nullable*/ String> holder = webSocketTransactionName.getHolder();
            if (pattern == null || pattern.isEmpty()) {
                holder.set(sb.toString());
                return holder;
            }
            String normalizedPattern = normalizedPatterns.get(pattern);
            if (normalizedPattern == null) {
                normalizedPattern = pattern.replaceAll("\\{[^}]*\\}", "*");
                normalizedPatterns.put(pattern, normalizedPattern);
            }
            sb.append(normalizedPattern);
            holder.set(sb.toString());
            return holder;
        }
        @OnAfter
        public static void onAfter(@BindTraveler @Nullable Holder</*@Nullable*/ String> holder) {
            if (holder != null) {
                holder.set(null);
            }
        }
    }

    @Pointcut(classAnnotation = "org.springframework.stereotype.Controller",
            methodAnnotation = "org.springframework.messaging.handler.annotation.MessageMapping",
            methodParameterTypes = {".."}, timerName = "spring websocket controller")
    public static class MessageMappingAdvice {
        private static final TimerName timerName = Agent.getTimerName(ControllerAdvice.class);
        @OnBefore
        public static TraceEntry onBefore(OptionalThreadContext context,
                @BindMethodMeta ControllerMethodMeta controllerMethodMeta) {
            String transactionName;
            if (useAltTransactionNaming.value()) {
                transactionName = controllerMethodMeta.getAltTransactionName();
            } else {
                transactionName = webSocketTransactionName.get();
                if (transactionName == null) {
                    transactionName = "<unknown>"; // ???
                }
            }
            return context.startTransaction("Web", transactionName,
                    MessageSupplier.create("spring websocket controller: {}.{}()",
                            controllerMethodMeta.getControllerClassName(),
                            controllerMethodMeta.getMethodName()),
                    timerName);
        }
        @OnReturn
        public static void onReturn(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler TraceEntry traceEntry) {
            traceEntry.endWithError(t);
        }
    }

    private static String getServletPath(@Nullable ServletRequestInfo servletRequestInfo) {
        if (servletRequestInfo == null) {
            return "";
        }
        if (servletRequestInfo.getPathInfo() == null) {
            // pathInfo is null when the servlet is mapped to "/" (not "/*") and therefore it is
            // replacing the default servlet and getServletPath() returns the full path
            return servletRequestInfo.getContextPath();
        } else {
            return servletRequestInfo.getContextPath() + servletRequestInfo.getServletPath();
        }
    }

    private static String getPattern(RequestMappingInfo mapping) {
        PatternsRequestCondition patternCondition =
                mapping.glowroot$getPatternsCondition();
        if (patternCondition != null) {
            Set<String> patterns = patternCondition.getPatterns();
            if (patterns == null || patterns.isEmpty()) {
                return null;
            }
            return patterns.iterator().next();
        }
        PathPatternsRequestCondition pathPatternCondition =
                mapping.glowroot$getPathPatternsCondition();
        if (pathPatternCondition == null) {
            return null;
        }
        Set<String> patterns = pathPatternCondition.getPatternValues();
        if (patterns == null || patterns.isEmpty()) {
            return null;
        }
        return patterns.iterator().next();
    }
}
