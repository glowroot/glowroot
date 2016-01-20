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
import org.glowroot.agent.plugin.api.transaction.ThreadContext;
import org.glowroot.agent.plugin.api.transaction.Timer;
import org.glowroot.agent.plugin.api.transaction.TimerName;
import org.glowroot.agent.plugin.api.transaction.TraceEntry;
import org.glowroot.agent.plugin.api.transaction.TransactionService;
import org.glowroot.agent.plugin.api.util.FastThreadLocal;
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

    private static final TransactionService transactionService = Agent.getTransactionService();

    @SuppressWarnings("nullness:type.argument.type.incompatible")
    private static final FastThreadLocal<Boolean> inSubmitOrExecute =
            new FastThreadLocal<Boolean>() {
                @Override
                protected Boolean initialValue() {
                    return false;
                }
            };

    // the field and method names are verbose to avoid conflict since they will become fields
    // and methods in all classes that extend java.lang.Runnable and/or
    // java.util.concurrent.Callable
    @Mixin({"java.lang.Runnable", "java.util.concurrent.Callable"})
    public abstract static class RunnableImpl implements RunnableCallableMixin {

        private volatile @Nullable ThreadContext glowroot$asyncContext;

        @Override
        public @Nullable ThreadContext glowroot$getAsyncContext() {
            return glowroot$asyncContext;
        }

        @Override
        public void glowroot$setAsyncContext(@Nullable ThreadContext asyncContext) {
            this.glowroot$asyncContext = asyncContext;
        }
    }

    // the method names are verbose to avoid conflict since they will become methods in all classes
    // that extend java.lang.Runnable and/or java.util.concurrent.Callable
    public interface RunnableCallableMixin {

        @Nullable
        ThreadContext glowroot$getAsyncContext();

        void glowroot$setAsyncContext(@Nullable ThreadContext asyncContext);
    }

    // ignore self nested is important for cases with wrapping ExecutorServices so that the outer
    // Runnable/Callable is the one used
    @Pointcut(className = "java.util.concurrent.ExecutorService", methodName = "submit",
            methodParameterTypes = {".."})
    public static class SubmitAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return !inSubmitOrExecute.get();
        }
        @OnBefore
        public static void onBefore(@BindParameter Object runnableCallable) {
            if (!(runnableCallable instanceof RunnableCallableMixin)) {
                // this class was loaded before class file transformer was added to jvm
                return;
            }
            inSubmitOrExecute.set(true);
            RunnableCallableMixin runnableCallableMixin = (RunnableCallableMixin) runnableCallable;
            ThreadContext asyncContext = transactionService.createThreadContext();
            runnableCallableMixin.glowroot$setAsyncContext(asyncContext);
        }
        @OnAfter
        public static void onAfter() {
            inSubmitOrExecute.set(false);
        }
    }

    // this method uses submit() and returns Future, but none of the callers use/wait on the Future
    @Pointcut(className = "net.sf.ehcache.store.disk.DiskStorageFactory", methodName = "schedule",
            methodParameterTypes = {"java.util.concurrent.Callable"})
    public static class EhcacheDiskStorageScheduleAdvice {
        @OnBefore
        public static void onBefore() {
            inSubmitOrExecute.set(true);
        }
        @OnAfter
        public static void onAfter() {
            inSubmitOrExecute.set(false);
        }
    }

    @Pointcut(className = "java.util.concurrent.Future", methodName = "get",
            methodParameterTypes = {".."}, timerName = "wait on future")
    public static class FutureGetAdvice {
        private static final TimerName timerName =
                transactionService.getTimerName(FutureGetAdvice.class);
        @IsEnabled
        public static boolean isEnabled(@BindReceiver Future<?> future) {
            // don't capture if already done, primarily this is to avoid caching pattern where
            // a future is used to store the value to ensure only-once initialization
            return !future.isDone();
        }
        @OnBefore
        public static Timer onBefore() {
            return transactionService.startTimer(timerName);
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
            ThreadContext asyncContext = runnableMixin.glowroot$getAsyncContext();
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
            ThreadContext asyncContext = callableMixin.glowroot$getAsyncContext();
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
                @BindTraveler TraceEntry traceEntry) {
            if (traceEntry != null) {
                traceEntry.endWithError(t);
            }
        }
    }
}
