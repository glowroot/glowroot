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

import org.glowroot.agent.plugin.api.*;
import org.glowroot.agent.plugin.api.ThreadContext.Priority;
import org.glowroot.agent.plugin.api.config.BooleanProperty;
import org.glowroot.agent.plugin.api.weaving.*;

// TODO optimize away servletPath thread local, e.g. store servlet path in thread context via
// servlet plugin and retrieve here
public class ResourceAspect {

    @SuppressWarnings("nullness:type.argument.type.incompatible")
    private static final ThreadLocal<RequestInfo> requestInfoHolder =
            new ThreadLocal<RequestInfo>() {
                @Override
                protected RequestInfo initialValue() {
                    return new RequestInfo();
                }
            };

    private static final BooleanProperty useAltTransactionNaming =
            Agent.getConfigService("jaxrs").getBooleanProperty("useAltTransactionNaming");

    @Shim("javax.servlet.http.HttpServletRequest")
    public interface HttpServletRequest {
        @Nullable
        String getMethod();
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
        public static @Nullable RequestInfo onBefore(@BindParameter @Nullable Object req) {
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
            RequestInfo requestInfo = requestInfoHolder.get();
            requestInfo.method = request.getMethod();
            requestInfo.servletPath = servletPath;
            return requestInfo;
        }
        @OnAfter
        public static void onAfter(
                @BindTraveler @Nullable RequestInfo requestInfo) {
            if (requestInfo != null) {
                requestInfo.method = null;
                requestInfo.servletPath = null;
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

            if (resourceMethodMeta.isAsynchronous()) {
                context.setTransactionAsync();
            }

            if (useAltTransactionNaming.value()) {
                context.setTransactionName(resourceMethodMeta.getAltTransactionName(),
                        Priority.CORE_PLUGIN);
            } else {
                RequestInfo requestInfo = requestInfoHolder.get();
                String transactionName = getTransactionName(requestInfo.method,
                        requestInfo.servletPath, resourceMethodMeta.getPath());
                context.setTransactionName(transactionName, Priority.CORE_PLUGIN);
            }
            return context.startTraceEntry(MessageSupplier.from("jaxrs resource: {}.{}()",
                    resourceMethodMeta.getResourceClassName(), resourceMethodMeta.getMethodName()),
                    timerName);
        }
        @OnReturn
        public static void onReturn(ThreadContext context, @BindTraveler TraceEntry traceEntry) {
            traceEntry.end();
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable throwable,
                @BindTraveler TraceEntry traceEntry) {
            traceEntry.endWithError(throwable);
        }
        private static String getTransactionName(@Nullable String method,
                @Nullable String servletPath, String resourcePath) {
            if (method != null) {
                if (servletPath == null || servletPath.isEmpty()) {
                    return method + " " + resourcePath;
                } else {
                    return method + " " + servletPath + resourcePath;
                }
            } else {
                if (servletPath == null || servletPath.isEmpty()) {
                    return resourcePath;
                } else {
                    return servletPath + resourcePath;
                }
            }
        }
    }

    @Pointcut(className = "javax.ws.rs.container.AsyncResponse",
            methodName = "resume",
            methodParameterTypes = {".."}, timerName = "jaxrs async response")
    public static class AsyncResponseAdvice {
        private static final TimerName timerName = Agent.getTimerName(AsyncResponseAdvice.class);
        @OnBefore
        public static TraceEntry onBefore(ThreadContext context) {
            return context.startTraceEntry(MessageSupplier.from("jaxrs async response"), timerName);
        }

        @OnAfter
        public static void onAfter(ThreadContext context, @BindTraveler TraceEntry traceEntry) {
            context.completeAsyncTransaction();
            traceEntry.end();
        }

        @OnThrow
        public static void onThrow(@BindThrowable Throwable throwable,
                                   @BindTraveler TraceEntry traceEntry) {
            traceEntry.endWithError(throwable);
        }

    }

    private static class RequestInfo {
        private @Nullable String method;
        private @Nullable String servletPath;
    }


    /////////////////////////////////////////////////////////////////////////////////////////////
    //
    // Put this here for test purposes. Should be removed, and there should be a test that covers two plugins.
    // the method names are verbose to avoid conflict since they will become methods in all classes
    // that extend java.lang.Runnable and/or java.util.concurrent.Callable
    public interface RunnableCallableMixin {

        @Nullable
        AuxThreadContext glowroot$getAuxThreadContext();

        void glowroot$setAuxAsyncContext(@Nullable AuxThreadContext auxThreadContext);
    }

    // the field and method names are verbose to avoid conflict since they will become fields
    // and methods in all classes that extend java.lang.Runnable and/or
    // java.util.concurrent.Callable
    @Mixin({"java.lang.Runnable", "java.util.concurrent.Callable"})
    public abstract static class RunnableImpl implements RunnableCallableMixin {

        private volatile @Nullable AuxThreadContext glowroot$auxThreadContext;

        @Override
        public @Nullable AuxThreadContext glowroot$getAuxThreadContext() {
            return glowroot$auxThreadContext;
        }

        @Override
        public void glowroot$setAuxAsyncContext(@Nullable AuxThreadContext auxThreadContext) {
            this.glowroot$auxThreadContext = auxThreadContext;
        }
    }


    @Pointcut(className = "java.util.concurrent.Executor", methodName = "execute",
            methodParameterTypes = {"java.lang.Runnable"}, nestingGroup = "executor")
    public static class ExecuteAdvice {

        @IsEnabled
        public static boolean isEnabled(@BindParameter Object runnableCallable) {
            // this class may have been loaded before class file transformer was added to jvm
            return runnableCallable instanceof RunnableCallableMixin;
        }

        @OnBefore
        public static void onBefore(ThreadContext context, @BindParameter Object runnableCallable) {
            if (context.isTransactionAsync()) {
                RunnableCallableMixin runnableCallableMixin = (RunnableCallableMixin) runnableCallable;
                AuxThreadContext asyncContext = context.createAuxThreadContext();
                runnableCallableMixin.glowroot$setAuxAsyncContext(asyncContext);
            }
        }
    }

    @Pointcut(className = "java.lang.Runnable", methodName = "run", methodParameterTypes = {})
    public static class RunnableAdvice {
        @OnBefore
        public static @Nullable TraceEntry onBefore(@BindReceiver Runnable runnable) {
            if (!(runnable instanceof RunnableCallableMixin)) {
                // this class was loaded before class file transformer was added to jvm
                return null;
            }
            RunnableCallableMixin runnableMixin = (RunnableCallableMixin) runnable;
            AuxThreadContext asyncContext = runnableMixin.glowroot$getAuxThreadContext();
            if (asyncContext == null) {
                return null;
            }
            return asyncContext.start();
        }
        @OnReturn
        public static void onReturn(@BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry != null) {
                traceEntry.end();
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                                   @BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry != null) {
                traceEntry.endWithError(t);
            }
        }
        @OnAfter
        public static void onAfter(@BindReceiver Runnable runnable) {
            if (!(runnable instanceof RunnableCallableMixin)) {
                // this class was loaded before class file transformer was added to jvm
                return;
            }
            RunnableCallableMixin runnableMixin = (RunnableCallableMixin) runnable;
            runnableMixin.glowroot$setAuxAsyncContext(null);
        }
    }



}
