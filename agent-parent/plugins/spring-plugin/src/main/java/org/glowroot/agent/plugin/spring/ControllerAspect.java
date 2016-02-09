/*
 * Copyright 2016 the original author or authors.
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

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
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

    @Shim("javax.servlet.http.HttpServletRequest")
    public interface HttpServletRequest {
        @Nullable
        String getServletPath();
        @Nullable
        String getPathInfo();
    }

    @Pointcut(className = "org.springframework.web.servlet.DispatcherServlet",
            methodDeclaringClassName = "javax.servlet.Servlet", methodName = "service",
            methodParameterTypes = {"javax.servlet.ServletRequest",
                    "javax.servlet.ServletResponse"})
    public static class CaptureServletPathAdvice {
        @OnBefore
        public static @Nullable FastThreadLocal.Holder</*@Nullable*/ String> onBefore(
                @BindParameter @Nullable Object req) {
            if (req == null || !(req instanceof HttpServletRequest)) {
                return null;
            }
            HttpServletRequest request = (HttpServletRequest) req;
            String pathInfo = request.getPathInfo();
            String servletPath;
            if (pathInfo == null) {
                // pathInfo is null when the dispatcher servlet is mapped to "/" (not "/*") and
                // therefore it is replacing the default servlet and getServletPath() returns the
                // full path
                servletPath = "";
            } else {
                servletPath = request.getServletPath();
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

    @Pointcut(classAnnotation = "org.springframework.stereotype.Controller",
            methodAnnotation = "org.springframework.web.bind.annotation.RequestMapping",
            methodParameterTypes = {".."}, timerName = "spring controller")
    public static class ControllerAdvice {
        private static final TimerName timerName = Agent.getTimerName(ControllerAdvice.class);
        @OnBefore
        public static TraceEntry onBefore(ThreadContext context,
                @BindMethodMeta ControllerMethodMeta controllerMethodMeta) {
            String prefix = servletPath.get();
            if (prefix == null || prefix.isEmpty()) {
                context.setTransactionName(controllerMethodMeta.getPath());
            } else {
                context.setTransactionName(prefix + controllerMethodMeta.getPath());
            }
            return context.startTraceEntry(
                    MessageSupplier.from("spring controller: {}.{}()",
                            controllerMethodMeta.getDeclaredClassSimpleName(),
                            controllerMethodMeta.getMethodName()),
                    timerName);
        }
        @OnReturn
        public static void onReturn(@BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable throwable,
                @BindTraveler TraceEntry traceEntry) {
            traceEntry.endWithError(throwable);
        }
    }
}
