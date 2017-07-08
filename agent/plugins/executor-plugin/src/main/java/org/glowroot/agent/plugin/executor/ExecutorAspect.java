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
package org.glowroot.agent.plugin.executor;

import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.AuxThreadContext;
import org.glowroot.agent.plugin.api.Logger;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.Timer;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.weaving.BindClassMeta;
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

    private static final Logger logger = Agent.getLogger(ExecutorAspect.class);

    private static final AtomicBoolean isDoneExceptionLogged = new AtomicBoolean();

    // the field and method names are verbose since they will be mixed in to existing classes
    @Mixin({"java.lang.Runnable", "java.util.concurrent.Callable",
            "java.util.concurrent.ForkJoinTask", "akka.jsr166y.ForkJoinTask",
            "scala.concurrent.forkjoin.ForkJoinTask"})
    public abstract static class RunnableEtcImpl implements RunnableEtcMixin {

        private volatile @Nullable AuxThreadContext glowroot$auxContext;

        @Override
        public @Nullable AuxThreadContext glowroot$getAuxContext() {
            return glowroot$auxContext;
        }

        @Override
        public void glowroot$setAuxContext(@Nullable AuxThreadContext auxContext) {
            glowroot$auxContext = auxContext;
        }
    }

    @Mixin("org.apache.tomcat.util.net.JIoEndpoint$SocketProcessor")
    public static class SuppressedRunnableImpl implements SuppressedRunnableEtcMixin {}

    // the method names are verbose since they will be mixed in to existing classes
    public interface RunnableEtcMixin {

        @Nullable
        AuxThreadContext glowroot$getAuxContext();

        void glowroot$setAuxContext(@Nullable AuxThreadContext auxContext);
    }

    public interface SuppressedRunnableEtcMixin {}

    @Pointcut(
            className = "java.util.concurrent.Executor|java.util.concurrent.ExecutorService"
                    + "|java.util.concurrent.ForkJoinPool"
                    + "|org.springframework.core.task.AsyncTaskExecutor"
                    + "|org.springframework.core.task.AsyncListenableTaskExecutor"
                    + "|akka.jsr166y.ForkJoinPool"
                    + "|scala.concurrent.forkjoin.ForkJoinPool",
            methodName = "execute|submit|invoke|submitListenable",
            methodParameterTypes = {".."}, nestingGroup = "executor-execute")
    public static class ExecuteAdvice {
        @IsEnabled
        public static boolean isEnabled(@BindParameter Object runnableEtc) {
            // this class may have been loaded before class file transformer was added to jvm
            return runnableEtc instanceof RunnableEtcMixin
                    && !(runnableEtc instanceof SuppressedRunnableEtcMixin);
        }
        @OnBefore
        public static void onBefore(ThreadContext context, @BindParameter Object runnableEtc) {
            RunnableEtcMixin runnableMixin = (RunnableEtcMixin) runnableEtc;
            AuxThreadContext auxContext = context.createAuxThreadContext();
            runnableMixin.glowroot$setAuxContext(auxContext);
        }
    }

    @Pointcut(className = "com.google.common.util.concurrent.ListenableFuture",
            methodName = "addListener",
            methodParameterTypes = {"java.lang.Runnable", "java.util.concurrent.Executor"},
            nestingGroup = "executor-add-listener")
    public static class AddListenerAdvice {
        @IsEnabled
        public static boolean isEnabled(@BindParameter Object runnableEtc) {
            return ExecuteAdvice.isEnabled(runnableEtc);
        }
        @OnBefore
        public static void onBefore(ThreadContext context, @BindParameter Object runnableEtc) {
            ExecuteAdvice.onBefore(context, runnableEtc);
        }
    }

    @Pointcut(
            className = "java.util.concurrent.ExecutorService|java.util.concurrent.ForkJoinPool"
                    + "|akka.jsr166y.ForkJoinPool|scala.concurrent.forkjoin.ForkJoinPool",
            methodName = "invokeAll|invokeAny",
            methodParameterTypes = {"java.util.Collection", ".."},
            nestingGroup = "executor-execute")
    public static class InvokeAnyAllAdvice {
        @OnBefore
        public static void onBefore(ThreadContext context, @BindParameter Collection<?> callables) {
            if (callables == null) {
                return;
            }
            for (Object callable : callables) {
                // this class may have been loaded before class file transformer was added to jvm
                if (callable instanceof RunnableEtcMixin
                        && !(callable instanceof SuppressedRunnableEtcMixin)) {
                    RunnableEtcMixin callableMixin = (RunnableEtcMixin) callable;
                    AuxThreadContext auxContext = context.createAuxThreadContext();
                    callableMixin.glowroot$setAuxContext(auxContext);
                }
            }
        }
    }

    @Pointcut(className = "java.util.concurrent.ScheduledExecutorService", methodName = "schedule",
            methodParameterTypes = {".."}, nestingGroup = "executor-execute")
    public static class ScheduleAdvice {
        @IsEnabled
        public static boolean isEnabled(@BindParameter Object runnableEtc) {
            // this class may have been loaded before class file transformer was added to jvm
            return runnableEtc instanceof RunnableEtcMixin
                    && !(runnableEtc instanceof SuppressedRunnableEtcMixin);
        }
        @OnBefore
        public static void onBefore(ThreadContext context, @BindParameter Object runnableEtc) {
            RunnableEtcMixin runnableEtcMixin = (RunnableEtcMixin) runnableEtc;
            AuxThreadContext auxContext = context.createAuxThreadContext();
            runnableEtcMixin.glowroot$setAuxContext(auxContext);
        }
    }

    @Pointcut(className = "akka.actor.Scheduler", methodName = "scheduleOnce",
            methodParameterTypes = {"scala.concurrent.duration.FiniteDuration",
                    "java.lang.Runnable", ".."},
            nestingGroup = "executor-execute")
    public static class ScheduleOnceAdvice {
        @IsEnabled
        public static boolean isEnabled(@SuppressWarnings("unused") @BindParameter Object duration,
                @BindParameter Object runnableEtc) {
            // this class may have been loaded before class file transformer was added to jvm
            return runnableEtc instanceof RunnableEtcMixin
                    && !(runnableEtc instanceof SuppressedRunnableEtcMixin);
        }
        @OnBefore
        public static void onBefore(ThreadContext context,
                @SuppressWarnings("unused") @BindParameter Object duration,
                @BindParameter Object runnableEtc) {
            RunnableEtcMixin runnableEtcMixin = (RunnableEtcMixin) runnableEtc;
            AuxThreadContext auxContext = context.createAuxThreadContext();
            runnableEtcMixin.glowroot$setAuxContext(auxContext);
        }
    }

    @Pointcut(className = "java.util.Timer", methodName = "schedule",
            methodParameterTypes = {"java.util.TimerTask", ".."}, nestingGroup = "executor-execute")
    public static class TimerScheduleAdvice {
        @IsEnabled
        public static boolean isEnabled(@BindParameter Object runnableEtc) {
            // this class may have been loaded before class file transformer was added to jvm
            return runnableEtc instanceof RunnableEtcMixin
                    && !(runnableEtc instanceof SuppressedRunnableEtcMixin);
        }
        @OnBefore
        public static void onBefore(ThreadContext context, @BindParameter Object runnableEtc) {
            RunnableEtcMixin runnableEtcMixin = (RunnableEtcMixin) runnableEtc;
            AuxThreadContext auxContext = context.createAuxThreadContext();
            runnableEtcMixin.glowroot$setAuxContext(auxContext);
        }
    }

    // this method uses submit() and returns Future, but none of the callers use/wait on the Future
    @Pointcut(className = "net.sf.ehcache.store.disk.DiskStorageFactory", methodName = "schedule",
            methodParameterTypes = {"java.util.concurrent.Callable"},
            nestingGroup = "executor-execute")
    public static class EhcacheDiskStorageScheduleAdvice {}

    // these methods use execute() to start long running threads that should not be tied to the
    // current transaction
    @Pointcut(className = "org.eclipse.jetty.io.SelectorManager"
            + "|org.eclipse.jetty.server.AbstractConnector"
            + "|wiremock.org.eclipse.jetty.io.SelectorManager"
            + "|wiremock.org.eclipse.jetty.server.AbstractConnector",
            methodName = "doStart", methodParameterTypes = {}, nestingGroup = "executor-execute")
    public static class JettyDoStartAdvice {}

    @Pointcut(className = "javax.servlet.AsyncContext", methodName = "start",
            methodParameterTypes = {"java.lang.Runnable"})
    public static class StartAdvice {
        @IsEnabled
        public static boolean isEnabled(@BindParameter Runnable runnable) {
            // this class may have been loaded before class file transformer was added to jvm
            return runnable instanceof RunnableEtcMixin;
        }
        @OnBefore
        public static void onBefore(ThreadContext context, @BindParameter Object runnable) {
            RunnableEtcMixin runnableMixin = (RunnableEtcMixin) runnable;
            AuxThreadContext auxContext = context.createAuxThreadContext();
            runnableMixin.glowroot$setAuxContext(auxContext);
        }
    }

    @Pointcut(className = "java.util.concurrent.Future", methodName = "get",
            methodParameterTypes = {".."}, timerName = "wait on future",
            suppressibleUsingKey = "wait-on-future")
    public static class FutureGetAdvice {
        private static final TimerName timerName = Agent.getTimerName(FutureGetAdvice.class);
        @IsEnabled
        public static boolean isEnabled(@BindReceiver Future<?> future,
                @BindClassMeta FutureClassMeta futureClassMeta) {
            if (futureClassMeta.isNonStandardFuture()) {
                // this is to handle known non-standard Future implementations
                return false;
            }
            // don't capture if already done, primarily this is to avoid caching pattern where
            // a future is used to store the value to ensure only-once initialization
            try {
                return !future.isDone();
            } catch (Exception e) {
                logger.debug(e.getMessage(), e);
                if (!isDoneExceptionLogged.getAndSet(true)) {
                    logger.info("encountered a non-standard java.util.concurrent.Future"
                            + " implementation, please report this stack trace to the Glowroot"
                            + " project:", e);
                }
                return false;
            }
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

    // the nesting group only starts applying once auxiliary thread context is started (it does not
    // apply to OptionalThreadContext that miss)
    @Pointcut(className = "java.lang.Runnable", methodName = "run", methodParameterTypes = {},
            nestingGroup = "executor-run")
    public static class RunnableAdvice {
        @IsEnabled
        public static boolean isEnabled(@BindReceiver Runnable runnable) {
            if (!(runnable instanceof RunnableEtcMixin)) {
                // this class was loaded before class file transformer was added to jvm
                return false;
            }
            RunnableEtcMixin runnableMixin = (RunnableEtcMixin) runnable;
            return runnableMixin.glowroot$getAuxContext() != null;
        }
        @OnBefore
        public static @Nullable TraceEntry onBefore(@BindReceiver Runnable runnable) {
            RunnableEtcMixin runnableMixin = (RunnableEtcMixin) runnable;
            AuxThreadContext auxContext = runnableMixin.glowroot$getAuxContext();
            if (auxContext == null) {
                // this is unlikely (since checked in @IsEnabled) but possible under concurrency
                return null;
            }
            runnableMixin.glowroot$setAuxContext(null);
            return auxContext.start();
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

    // the nesting group only starts applying once auxiliary thread context is started (it does not
    // apply to OptionalThreadContext that miss)
    @Pointcut(className = "java.util.concurrent.Callable", methodName = "call",
            methodParameterTypes = {}, nestingGroup = "executor-run")
    public static class CallableAdvice {
        @IsEnabled
        public static boolean isEnabled(@BindReceiver Callable<?> callable) {
            if (!(callable instanceof RunnableEtcMixin)) {
                // this class was loaded before class file transformer was added to jvm
                return false;
            }
            RunnableEtcMixin callableMixin = (RunnableEtcMixin) callable;
            return callableMixin.glowroot$getAuxContext() != null;
        }
        @OnBefore
        public static @Nullable TraceEntry onBefore(@BindReceiver Callable<?> callable) {
            RunnableEtcMixin callableMixin = (RunnableEtcMixin) callable;
            AuxThreadContext auxContext = callableMixin.glowroot$getAuxContext();
            if (auxContext == null) {
                // this is unlikely (since checked in @IsEnabled) but possible under concurrency
                return null;
            }
            callableMixin.glowroot$setAuxContext(null);
            return auxContext.start();
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

    // the nesting group only starts applying once auxiliary thread context is started (it does not
    // apply to OptionalThreadContext that miss)
    @Pointcut(className = "java.util.concurrent.ForkJoinTask|akka.jsr166y.ForkJoinTask"
            + "|scala.concurrent.forkjoin.ForkJoinTask",
            methodName = "exec", methodParameterTypes = {}, nestingGroup = "executor-run")
    public static class ExecAdvice {
        @IsEnabled
        public static boolean isEnabled(@BindReceiver Object task) {
            if (!(task instanceof RunnableEtcMixin)) {
                // this class was loaded before class file transformer was added to jvm
                return false;
            }
            RunnableEtcMixin taskMixin = (RunnableEtcMixin) task;
            return taskMixin.glowroot$getAuxContext() != null;
        }
        @OnBefore
        public static @Nullable TraceEntry onBefore(@BindReceiver Object task) {
            RunnableEtcMixin taskMixin = (RunnableEtcMixin) task;
            AuxThreadContext auxContext = taskMixin.glowroot$getAuxContext();
            if (auxContext == null) {
                // this is unlikely (since checked in @IsEnabled) but possible under concurrency
                return null;
            }
            taskMixin.glowroot$setAuxContext(null);
            return auxContext.start();
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

    // ========== debug ==========

    // KEEP THIS CODE IT IS VERY USEFUL

    // private static final ThreadLocal<?> inAuxDebugLogging;
    //
    // static {
    // try {
    // Class<?> clazz = Class.forName("org.glowroot.agent.impl.AuxThreadContextImpl");
    // Field field = clazz.getDeclaredField("inAuxDebugLogging");
    // field.setAccessible(true);
    // inAuxDebugLogging = (ThreadLocal<?>) field.get(null);
    // } catch (Exception e) {
    // throw new IllegalStateException(e);
    // }
    // }
    //
    // @Pointcut(className = "/(?!org.glowroot).*/", methodName = "<init>",
    // methodParameterTypes = {".."})
    // public static class RunnableInitAdvice {
    //
    // @OnAfter
    // public static void onAfter(OptionalThreadContext context, @BindReceiver Object obj) {
    // if (obj instanceof Runnable && isNotGlowrootThread()
    // && inAuxDebugLogging.get() == null) {
    // new Exception(
    // "Init " + Thread.currentThread().getName() + " " + obj.getClass().getName()
    // + ":" + obj.hashCode() + " " + context.getClass().getName())
    // .printStackTrace();
    // }
    // }
    // }
    //
    // @Pointcut(className = "java.lang.Runnable", methodName = "run", methodParameterTypes = {},
    // order = 1)
    // public static class RunnableRunAdvice {
    //
    // @IsEnabled
    // public static boolean isEnabled() {
    // return isNotGlowrootThread();
    // }
    //
    // @OnBefore
    // public static void onBefore(OptionalThreadContext context, @BindReceiver Runnable obj) {
    // new Exception("Run " + Thread.currentThread().getName() + " " + obj.getClass().getName()
    // + ":" + obj.hashCode() + " " + context.getClass().getName()).printStackTrace();
    // }
    // }
    //
    // private static boolean isNotGlowrootThread() {
    // String threadName = Thread.currentThread().getName();
    // return !threadName.contains("GRPC") && !threadName.contains("Glowroot");
    // }
}
