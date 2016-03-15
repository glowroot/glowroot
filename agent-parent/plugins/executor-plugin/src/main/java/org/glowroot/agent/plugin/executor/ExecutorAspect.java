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
package org.glowroot.agent.plugin.executor;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.AuxThreadContext;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.Timer;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindReceiver;
import org.glowroot.agent.plugin.api.weaving.BindThrowable;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.IsEnabled;
import org.glowroot.agent.plugin.api.weaving.Mixin;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.OnThrow;
import org.glowroot.agent.plugin.api.weaving.Pointcut;

public class ExecutorAspect {

    // the field and method names are verbose to avoid conflict since they will become fields
    // and methods in all classes that extend java.lang.Runnable and/or
    // java.util.concurrent.Callable
    @Mixin({"java.lang.Runnable", "java.util.concurrent.Callable"})
    public abstract static class RunnableImpl implements RunnableCallableMixin {

        private volatile @Nullable AuxThreadContext glowroot$auxContext;

        @Override
        public @Nullable AuxThreadContext glowroot$getAuxContext() {
            return glowroot$auxContext;
        }

        @Override
        public void glowroot$setAuxContext(@Nullable AuxThreadContext auxContext) {
            this.glowroot$auxContext = auxContext;
        }
    }

    // the field and method names are verbose to avoid conflict since they will become fields
    // and methods in all classes that extend java.util.concurrent.FutureTask
    @Mixin({"java.util.concurrent.FutureTask"})
    public abstract static class FutureTaskImpl implements FutureTaskMixin {

        private volatile @Nullable RunnableCallableMixin glowroot$innerRunnableCallable;

        @Override
        public @Nullable RunnableCallableMixin glowroot$getInnerRunnableCallable() {
            return glowroot$innerRunnableCallable;
        }

        @Override
        public void glowroot$setInnerRunnableCallable(
                @Nullable RunnableCallableMixin innerRunnableCallable) {
            this.glowroot$innerRunnableCallable = innerRunnableCallable;
        }
    }

    // the method names are verbose to avoid conflict since they will become methods in all classes
    // that extend java.lang.Runnable and/or java.util.concurrent.Callable
    public interface RunnableCallableMixin {

        @Nullable
        AuxThreadContext glowroot$getAuxContext();

        void glowroot$setAuxContext(@Nullable AuxThreadContext auxContext);
    }

    // the method names are verbose to avoid conflict since they will become methods in all classes
    // that extend java.lang.Runnable and/or java.util.concurrent.Callable
    public interface FutureTaskMixin {

        @Nullable
        RunnableCallableMixin glowroot$getInnerRunnableCallable();

        void glowroot$setInnerRunnableCallable(
                @Nullable RunnableCallableMixin runnableCallableMixin);
    }

    // ignore self nested is important for cases with wrapping ExecutorServices so that the outer
    // Runnable/Callable is the one used
    @Pointcut(className = "java.util.concurrent.ExecutorService", methodName = "submit",
            methodParameterTypes = {".."}, nestingGroup = "executor")
    public static class SubmitAdvice {
        @IsEnabled
        public static boolean isEnabled(@BindParameter Object runnableCallable) {
            // this class may have been loaded before class file transformer was added to jvm
            return runnableCallable instanceof RunnableCallableMixin;
        }
        @OnBefore
        public static void onBefore(ThreadContext context, @BindParameter Object runnableCallable) {
            RunnableCallableMixin runnableCallableMixin = (RunnableCallableMixin) runnableCallable;
            AuxThreadContext auxContext = context.createAuxThreadContext();
            runnableCallableMixin.glowroot$setAuxContext(auxContext);
        }
    }

    @Pointcut(className = "javax.servlet.AsyncContext", methodName = "start",
            methodParameterTypes = {"java.lang.Runnable"})
    public static class StartAdvice {
        @IsEnabled
        public static boolean isEnabled(@BindParameter Runnable runnable) {
            // this class may have been loaded before class file transformer was added to jvm
            return runnable instanceof RunnableCallableMixin;
        }
        @OnBefore
        public static void onBefore(ThreadContext context, @BindParameter Object runnable) {
            RunnableCallableMixin runnableMixin = (RunnableCallableMixin) runnable;
            AuxThreadContext auxContext = context.createAuxThreadContext();
            runnableMixin.glowroot$setAuxContext(auxContext);
        }
    }

    @Pointcut(className = "java.util.concurrent.FutureTask", methodName = "<init>",
            methodParameterTypes = {"java.util.concurrent.Callable"})
    public static class FutureTaskInitWithCallableAdvice {
        @OnBefore
        public static void onBefore(@BindReceiver Object futureTask,
                @BindParameter Object runnableCallable) {
            if (!(runnableCallable instanceof RunnableCallableMixin)) {
                // this class was loaded before class file transformer was added to jvm
                return;
            }
            if (!(futureTask instanceof FutureTaskMixin)) {
                // this class was loaded before class file transformer was added to jvm
                return;
            }
            ((FutureTaskMixin) futureTask)
                    .glowroot$setInnerRunnableCallable((RunnableCallableMixin) runnableCallable);
        }
    }

    @Pointcut(className = "java.util.concurrent.FutureTask", methodName = "<init>",
            methodParameterTypes = {"java.lang.Runnable", ".."})
    public static class FutureTaskInitWithRunnableAdvice {
        @OnBefore
        public static void onBefore(@BindReceiver Object futureTask,
                @BindParameter Object runnableCallable) {
            FutureTaskInitWithCallableAdvice.onBefore(futureTask, runnableCallable);
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
            if (runnableCallable instanceof FutureTaskMixin) {
                FutureTaskMixin futureTaskMixin = (FutureTaskMixin) runnableCallable;
                AuxThreadContext auxContext = context.createAuxThreadContext();
                RunnableCallableMixin innerRunnableCallable =
                        futureTaskMixin.glowroot$getInnerRunnableCallable();
                if (innerRunnableCallable != null) {
                    innerRunnableCallable.glowroot$setAuxContext(auxContext);
                }
            } else if (context.isTransactionAsync()) {
                RunnableCallableMixin runnableCallableMixin =
                        (RunnableCallableMixin) runnableCallable;
                AuxThreadContext asyncContext = context.createAuxThreadContext();
                runnableCallableMixin.glowroot$setAuxContext(asyncContext);
            }
        }
    }

    // this method uses submit() and returns Future, but none of the callers use/wait on the Future
    @Pointcut(className = "net.sf.ehcache.store.disk.DiskStorageFactory", methodName = "schedule",
            methodParameterTypes = {"java.util.concurrent.Callable"}, nestingGroup = "executor")
    public static class EhcacheDiskStorageScheduleAdvice {}

    @Pointcut(className = "java.util.concurrent.Future", methodName = "get",
            methodParameterTypes = {".."}, timerName = "wait on future")
    public static class FutureGetAdvice {
        private static final TimerName timerName = Agent.getTimerName(FutureGetAdvice.class);
        @IsEnabled
        public static boolean isEnabled(@BindReceiver Future<?> future) {
            // don't capture if already done, primarily this is to avoid caching pattern where
            // a future is used to store the value to ensure only-once initialization
            return !future.isDone();
        }
        @OnBefore
        public static Timer onBefore(ThreadContext context) {
            return context.startTimer(timerName);
        }
        @OnAfter
        public static void onAfter(@BindTraveler Timer timer) {
            timer.stop();
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
            AuxThreadContext auxContext = runnableMixin.glowroot$getAuxContext();
            if (auxContext != null) {
                runnableMixin.glowroot$setAuxContext(null);
                return auxContext.start();
            }
            return null;
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
    }

    @Pointcut(className = "java.util.concurrent.Callable", methodName = "call",
            methodParameterTypes = {})
    public static class CallableAdvice {
        @OnBefore
        public static @Nullable TraceEntry onBefore(@BindReceiver Callable<?> callable) {
            if (!(callable instanceof RunnableCallableMixin)) {
                // this class was loaded before class file transformer was added to jvm
                return null;
            }
            RunnableCallableMixin callableMixin = (RunnableCallableMixin) callable;
            AuxThreadContext auxContext = callableMixin.glowroot$getAuxContext();
            if (auxContext != null) {
                callableMixin.glowroot$setAuxContext(null);
                return auxContext.start();
            }
            return null;
        }
        @OnReturn
        public static void onReturn(@BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry != null) {
                traceEntry.end();
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler TraceEntry traceEntry) {
            if (traceEntry != null) {
                traceEntry.endWithError(t);
            }
        }
    }
}
