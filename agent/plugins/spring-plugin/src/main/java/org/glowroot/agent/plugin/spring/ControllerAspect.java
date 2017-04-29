/*
 * Copyright 2016-2017 the original author or authors.
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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.ThreadContext.Priority;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.config.BooleanProperty;
import org.glowroot.agent.plugin.api.util.FastThreadLocal;
import org.glowroot.agent.plugin.api.weaving.BindMethodMeta;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindThrowable;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.OnThrow;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.api.weaving.Shim;

// TODO optimize away servletPath thread local, e.g. store servlet path in thread context via
// servlet plugin and retrieve here
public class ControllerAspect {

    private static final FastThreadLocal</*@Nullable*/ String> servletPath =
            new FastThreadLocal</*@Nullable*/ String>();

    private static final BooleanProperty useAltTransactionNaming =
            Agent.getConfigService("spring").getBooleanProperty("useAltTransactionNaming");

    private static final ConcurrentMap<String, String> normalizedPatterns =
            new ConcurrentHashMap<String, String>();

    @Shim("javax.servlet.http.HttpServletRequest")
    public interface HttpServletRequest {
        @Nullable
        String getContextPath();
        @Nullable
        String getServletPath();
        @Nullable
        String getPathInfo();
    }

    @Shim("org.springframework.web.servlet.mvc.method.RequestMappingInfo")
    public interface RequestMappingInfo {
        @Shim("org.springframework.web.servlet.mvc.condition.PatternsRequestCondition"
                + " getPatternsCondition()")
        @Nullable
        PatternsRequestCondition glowroot$getPatternsCondition();
    }

    @Shim("org.springframework.web.servlet.mvc.condition.PatternsRequestCondition")
    public interface PatternsRequestCondition {
        @Nullable
        Set<String> getPatterns();
    }

    @Pointcut(className = "javax.servlet.Servlet",
            subTypeRestriction = "org.springframework.web.servlet.DispatcherServlet",
            methodName = "service", methodParameterTypes = {"javax.servlet.ServletRequest",
                    "javax.servlet.ServletResponse"})
    public static class CaptureServletPathAdvice {
        @OnBefore
        public static @Nullable FastThreadLocal.Holder</*@Nullable*/ String> onBefore(
                @BindParameter @Nullable Object req) {
            if (req == null || !(req instanceof HttpServletRequest)) {
                return null;
            }
            HttpServletRequest request = (HttpServletRequest) req;
            String contextPath = request.getContextPath();
            if (contextPath == null) {
                contextPath = "";
            }
            String pathInfo = request.getPathInfo();
            String servletPath;
            if (pathInfo == null) {
                // pathInfo is null when the dispatcher servlet is mapped to "/" (not "/*") and
                // therefore it is replacing the default servlet and getServletPath() returns the
                // full path
                servletPath = contextPath;
            } else {
                servletPath = contextPath + request.getServletPath();
            }
            FastThreadLocal.Holder</*@Nullable*/ String> holder =
                    ControllerAspect.servletPath.getHolder();
            holder.set(servletPath);
            return holder;
        }
        @OnAfter
        public static void onAfter(
                @BindTraveler @Nullable FastThreadLocal.Holder</*@Nullable*/ String> holder) {
            if (holder != null) {
                holder.set(null);
            }
        }
    }

    @Pointcut(className = "org.springframework.web.servlet.handler.AbstractHandlerMethodMapping",
            methodName = "handleMatch", methodParameterTypes = {"java.lang.Object",
                    "java.lang.String", "javax.servlet.http.HttpServletRequest"})
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
            PatternsRequestCondition patternCondition =
                    ((RequestMappingInfo) mapping).glowroot$getPatternsCondition();
            if (patternCondition == null) {
                return;
            }
            Set<String> patterns = patternCondition.getPatterns();
            if (patterns == null || patterns.isEmpty()) {
                return;
            }
            String prefix = servletPath.get();
            String pattern = patterns.iterator().next();
            if (pattern == null || pattern.isEmpty()) {
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
                    "java.lang.String", "javax.servlet.http.HttpServletRequest"})
    public static class UrlHandlerMappingAdvice {
        @OnBefore
        public static void onBefore(ThreadContext context,
                @BindParameter @Nullable String bestMatchingPattern) {
            if (useAltTransactionNaming.value()) {
                return;
            }
            String prefix = servletPath.get();
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
}
