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
package org.glowroot.agent.plugin.jaxrs;

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.ThreadContext;
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
public class ResourceAspect {

    private static final FastThreadLocal</*@Nullable*/ String> servletPath =
            new FastThreadLocal</*@Nullable*/ String>();

    private static final BooleanProperty useAltTransactionNaming =
            Agent.getConfigService("jaxrs").getBooleanProperty("useAltTransactionNaming");

    @Shim("javax.servlet.http.HttpServletRequest")
    public interface HttpServletRequest {
        @Nullable
        String getServletPath();
        @Nullable
        String getPathInfo();
    }

    @Pointcut(className = "org.glassfish.jersey.servlet.ServletContainer",
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
                    ResourceAspect.servletPath.getHolder();
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

    @Pointcut(classAnnotation = "javax.ws.rs.Path",
            methodAnnotation = "javax.ws.rs.Path|javax.ws.rs.DELETE|javax.ws.rs.GET"
                    + "|javax.ws.rs.HEAD|javax.ws.rs.OPTIONS|javax.ws.rs.POST|javax.ws.rs.PUT",
            methodParameterTypes = {".."}, timerName = "jaxrs resource")
    public static class ResourceAdvice {
        private static final TimerName timerName = Agent.getTimerName(ResourceAdvice.class);
        @OnBefore
        public static TraceEntry onBefore(ThreadContext context,
                @BindMethodMeta ResourceMethodMeta resourceMethodMeta) {
            String prefix = servletPath.get();
            if (useAltTransactionNaming.value()) {
                context.setTransactionName(resourceMethodMeta.getAltTransactionName());
            } else {
                if (prefix == null || prefix.isEmpty()) {
                    context.setTransactionName(resourceMethodMeta.getPath());
                } else {
                    context.setTransactionName(prefix + resourceMethodMeta.getPath());
                }
            }
            return context.startTraceEntry(MessageSupplier.from("jaxrs resource: {}.{}()",
                    resourceMethodMeta.getResourceClassName(), resourceMethodMeta.getMethodName()),
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
