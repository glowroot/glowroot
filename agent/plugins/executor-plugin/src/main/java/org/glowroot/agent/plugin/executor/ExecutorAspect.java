/*
 * Copyright 2016-2019 the original author or authors.
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TimerTask;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.AuxThreadContext;
import org.glowroot.agent.plugin.api.Logger;
import org.glowroot.agent.plugin.api.ParameterHolder;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.Timer;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.checker.Nullable;
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

    private static final Logger logger = Logger.getLogger(ExecutorAspect.class);

    private static final String EXECUTOR_CLASSES = "java.util.concurrent.Executor"
            + "|java.util.concurrent.ExecutorService"
            + "|java.util.concurrent.ForkJoinPool"
            + "|org.springframework.core.task.AsyncTaskExecutor"
            + "|org.springframework.core.task.AsyncListenableTaskExecutor"
            + "|akka.jsr166y.ForkJoinPool"
            + "|scala.concurrent.forkjoin.ForkJoinPool";

    private static final AtomicBoolean isDoneExceptionLogged = new AtomicBoolean();

    // the field and method names are verbose since they will be mixed in to existing classes
    @Mixin({"java.lang.Thread", "java.util.TimerTask", "java.util.concurrent.ForkJoinTask",
            "akka.jsr166y.ForkJoinTask", "scala.concurrent.forkjoin.ForkJoinTask"})
    public abstract static class OtherRunnableMixinImpl implements OtherRunnableMixin {

        private transient volatile @Nullable AuxThreadContext glowroot$auxContext;

        @Override
        public @Nullable AuxThreadContext glowroot$getAuxContext() {
            return glowroot$auxContext;
        }

        @Override
        public void glowroot$setAuxContext(@Nullable AuxThreadContext auxContext) {
            glowroot$auxContext = auxContext;
        }
    }

    // the method names are verbose since they will be mixed in to existing classes
    public interface OtherRunnableMixin {

        @Nullable
        AuxThreadContext glowroot$getAuxContext();

        void glowroot$setAuxContext(@Nullable AuxThreadContext auxContext);
    }

    // TODO suppress various known thread pool threads (e.g. TimerThread)
    @Mixin({"org.apache.tomcat.util.net.JIoEndpoint$SocketProcessor",
            "org.apache.http.impl.nio.client.CloseableHttpAsyncClientBase$1",
            "java.util.TimerThread"})
    public static class SuppressedRunnableImpl implements SuppressedRunnableMixin {}

    public interface SuppressedRunnableMixin {}

    @Pointcut(className = EXECUTOR_CLASSES, methodName = "execute|submit|invoke|submitListenable",
            methodParameterTypes = {"java.lang.Runnable", ".."}, nestingGroup = "executor-execute")
    public static class ExecuteRunnableAdvice {
        @IsEnabled
        public static boolean isEnabled(@BindParameter Runnable runnable) {
            return !(runnable instanceof SuppressedRunnableMixin);
        }
        @OnBefore
        public static void onBefore(ThreadContext context,
                @BindParameter ParameterHolder<Runnable> runnableHolder) {
            wrapRunnable(runnableHolder, context);
        }
    }

    @Pointcut(className = EXECUTOR_CLASSES,
            methodName = "execute|submit|invoke|submitListenable",
            methodParameterTypes = {"java.util.concurrent.Callable", ".."},
            nestingGroup = "executor-execute")
    public static class ExecuteCallableAdvice {
        @OnBefore
        public static <T> void onBefore(ThreadContext context,
                @BindParameter ParameterHolder<Callable<T>> callableHolder) {
            wrapCallable(callableHolder, context);
        }
    }

    @Pointcut(className = EXECUTOR_CLASSES,
            methodName = "execute|submit|invoke|submitListenable",
            methodParameterTypes = {"java.util.concurrent.ForkJoinTask|akka.jsr166y.ForkJoinTask"
                    + "|scala.concurrent.forkjoin.ForkJoinTask", ".."},
            nestingGroup = "executor-execute")
    public static class ExecuteForkJoinTaskAdvice {
        @IsEnabled
        public static boolean isEnabled(@BindParameter Object forkJoinTask) {
            // this class may have been loaded before the class file transformer was added to jvm,
            // also this may be a lambda class, which are no longer being passed to
            // ClassFileTransformer in Java 9+
            return forkJoinTask instanceof OtherRunnableMixin;
        }
        @OnBefore
        public static void onBefore(ThreadContext context, @BindParameter Object forkJoinTask) {
            // cast is safe because of isEnabled() check above
            ((OtherRunnableMixin) forkJoinTask)
                    .glowroot$setAuxContext(context.createAuxThreadContext());
        }
    }

    @Pointcut(className = "java.lang.Thread", methodName = "<init>", methodParameterTypes = {},
            nestingGroup = "executor-execute")
    public static class ThreadInitAdvice {
        @OnReturn
        public static void onReturn(ThreadContext context, @BindReceiver Thread thread) {
            if (thread instanceof OtherRunnableMixin
                    && !(thread instanceof SuppressedRunnableMixin)) {
                ((OtherRunnableMixin) thread)
                        .glowroot$setAuxContext(context.createAuxThreadContext());
            }
        }
    }

    @Pointcut(className = "java.lang.Thread", methodName = "<init>",
            methodParameterTypes = {"java.lang.String"}, nestingGroup = "executor-execute")
    public static class ThreadInitWithStringAdvice {
        @OnReturn
        public static void onReturn(ThreadContext context, @BindReceiver Thread thread) {
            if (thread instanceof OtherRunnableMixin
                    && !(thread instanceof SuppressedRunnableMixin)) {
                ((OtherRunnableMixin) thread)
                        .glowroot$setAuxContext(context.createAuxThreadContext());
            }
        }
    }

    @Pointcut(className = "java.lang.Thread", methodName = "<init>",
            methodParameterTypes = {"java.lang.ThreadGroup", "java.lang.String"},
            nestingGroup = "executor-execute")
    public static class ThreadInitWithStringAndThreadGroupAdvice {
        @OnReturn
        public static void onReturn(ThreadContext context, @BindReceiver Thread thread) {
            if (thread instanceof OtherRunnableMixin
                    && !(thread instanceof SuppressedRunnableMixin)) {
                ((OtherRunnableMixin) thread)
                        .glowroot$setAuxContext(context.createAuxThreadContext());
            }
        }
    }

    @Pointcut(className = "java.lang.Thread", methodName = "<init>",
            methodParameterTypes = {"java.lang.Runnable", ".."}, nestingGroup = "executor-execute")
    public static class ThreadInitWithRunnableAdvice {
        @OnBefore
        public static void onBefore(ThreadContext context,
                @BindParameter ParameterHolder<Runnable> runnableHolder) {
            Runnable runnable = runnableHolder.get();
            if (runnable != null && !(runnable instanceof SuppressedRunnableMixin)) {
                runnableHolder.set(new RunnableWrapper(runnable, context.createAuxThreadContext()));
            }
        }
        @OnReturn
        public static void onReturn(ThreadContext context, @BindReceiver Thread thread,
                @BindParameter @Nullable Runnable runnable) {
            if (runnable == null && thread instanceof OtherRunnableMixin
                    && !(thread instanceof SuppressedRunnableMixin)) {
                ((OtherRunnableMixin) thread)
                        .glowroot$setAuxContext(context.createAuxThreadContext());
            }
        }
    }

    @Pointcut(className = "java.lang.Thread", methodName = "<init>",
            methodParameterTypes = {"java.lang.ThreadGroup", "java.lang.Runnable", ".."},
            nestingGroup = "executor-execute")
    public static class ThreadInitWithThreadGroupAdvice {
        @OnBefore
        public static void onBefore(ThreadContext context,
                @SuppressWarnings("unused") @BindParameter ThreadGroup threadGroup,
                @BindParameter ParameterHolder<Runnable> runnableHolder) {
            Runnable runnable = runnableHolder.get();
            if (runnable != null && !(runnable instanceof SuppressedRunnableMixin)) {
                runnableHolder.set(new RunnableWrapper(runnable, context.createAuxThreadContext()));
            }
        }
        @OnReturn
        public static void onReturn(ThreadContext context, @BindReceiver Thread thread,
                @SuppressWarnings("unused") @BindParameter ThreadGroup threadGroup,
                @BindParameter @Nullable Runnable runnable) {
            if (runnable == null && thread instanceof OtherRunnableMixin
                    && !(thread instanceof SuppressedRunnableMixin)) {
                ((OtherRunnableMixin) thread)
                        .glowroot$setAuxContext(context.createAuxThreadContext());
            }
        }
    }

    @Pointcut(className = "com.google.common.util.concurrent.ListenableFuture",
            methodName = "addListener",
            methodParameterTypes = {"java.lang.Runnable", "java.util.concurrent.Executor"},
            nestingGroup = "executor-add-listener")
    public static class AddListenerAdvice {
        @OnBefore
        public static void onBefore(ThreadContext context,
                @BindParameter ParameterHolder<Runnable> runnableHolder) {
            wrapRunnable(runnableHolder, context);
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
        public static <T> void onBefore(ThreadContext context,
                @BindParameter ParameterHolder<Collection</*@Nullable*/ Callable<T>>> callablesHolder) {
            Collection</*@Nullable*/ Callable<T>> callables = callablesHolder.get();
            if (callables == null) {
                return;
            }
            List</*@Nullable*/ Callable<T>> wrapped =
                    new ArrayList</*@Nullable*/ Callable<T>>(callables.size());
            for (Callable<T> callable : callables) {
                if (callable == null) {
                    wrapped.add(null);
                } else {
                    wrapped.add(new CallableWrapper<T>(callable, context.createAuxThreadContext()));
                }
            }
            callablesHolder.set(wrapped);
        }
    }

    @Pointcut(className = "java.util.concurrent.ScheduledExecutorService", methodName = "schedule",
            methodParameterTypes = {"java.lang.Runnable", ".."}, nestingGroup = "executor-execute")
    public static class ScheduleRunnableAdvice {
        @OnBefore
        public static void onBefore(ThreadContext context,
                @BindParameter ParameterHolder<Runnable> runnableHolder) {
            wrapRunnable(runnableHolder, context);
        }
    }

    @Pointcut(className = "java.util.concurrent.ScheduledExecutorService", methodName = "schedule",
            methodParameterTypes = {"java.util.concurrent.Callable", ".."},
            nestingGroup = "executor-execute")
    public static class ScheduleCallableAdvice {
        @OnBefore
        public static <T> void onBefore(ThreadContext context,
                @BindParameter ParameterHolder<Callable<T>> callableHolder) {
            wrapCallable(callableHolder, context);
        }
    }

    @Pointcut(className = "akka.actor.Scheduler", methodName = "scheduleOnce",
            methodParameterTypes = {"scala.concurrent.duration.FiniteDuration",
                    "java.lang.Runnable", ".."},
            nestingGroup = "executor-execute")
    public static class ScheduleOnceAdvice {
        @OnBefore
        public static void onBefore(ThreadContext context,
                @SuppressWarnings("unused") @BindParameter Object duration,
                @BindParameter ParameterHolder<Runnable> runnableHolder) {
            wrapRunnable(runnableHolder, context);
        }
    }

    @Pointcut(className = "java.util.Timer", methodName = "schedule",
            methodParameterTypes = {"java.util.TimerTask", ".."}, nestingGroup = "executor-execute")
    public static class TimerScheduleAdvice {
        @IsEnabled
        public static boolean isEnabled(@BindParameter TimerTask timerTask) {
            // this class may have been loaded before class file transformer was added to jvm
            return timerTask instanceof OtherRunnableMixin;
        }
        @OnBefore
        public static void onBefore(ThreadContext context, @BindParameter TimerTask timerTask) {
            ((OtherRunnableMixin) timerTask)
                    .glowroot$setAuxContext(context.createAuxThreadContext());
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
        @OnBefore
        public static void onBefore(ThreadContext context,
                @BindParameter ParameterHolder<Runnable> runnableHolder) {
            wrapRunnable(runnableHolder, context);
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

    // need to clear out the "executor-execute" nesting group, see
    // ExecutorIT.shouldCaptureNestedExecute()
    @Pointcut(className = "org.glowroot.agent.plugin.executor.RunnableWrapper", methodName = "run",
            methodParameterTypes = {}, nestingGroup = "executor-run")
    public static class RunnableAdvice {}

    // need to clear out the "executor-execute" nesting group, see
    // ExecutorIT.shouldCaptureNestedSubmit()
    @Pointcut(className = "org.glowroot.agent.plugin.executor.CallableWrapper", methodName = "call",
            methodParameterTypes = {}, nestingGroup = "executor-run")
    public static class CallableAdvice {}

    @Pointcut(className = "java.lang.Thread|java.util.TimerTask|java.util.concurrent.ForkJoinTask"
            + "|akka.jsr166y.ForkJoinTask|scala.concurrent.forkjoin.ForkJoinTask",
            methodName = "run|exec", methodParameterTypes = {}, nestingGroup = "executor-run")
    public static class OtherRunnableAdvice {
        @IsEnabled
        public static boolean isEnabled(@BindReceiver Object otherRunnable) {
            if (!(otherRunnable instanceof OtherRunnableMixin)) {
                // this class was loaded before class file transformer was added to jvm
                return false;
            }
            OtherRunnableMixin otherRunnableMixin = (OtherRunnableMixin) otherRunnable;
            return otherRunnableMixin.glowroot$getAuxContext() != null;
        }
        @OnBefore
        public static @Nullable TraceEntry onBefore(@BindReceiver Object otherRunnable) {
            OtherRunnableMixin otherRunnableMixin = (OtherRunnableMixin) otherRunnable;
            AuxThreadContext auxContext = otherRunnableMixin.glowroot$getAuxContext();
            if (auxContext == null) {
                // this is unlikely (since checked in @IsEnabled) but possible under concurrency
                return null;
            }
            otherRunnableMixin.glowroot$setAuxContext(null);
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

    private static void wrapRunnable(ParameterHolder<Runnable> runnableHolder,
            ThreadContext context) {
        Runnable runnable = runnableHolder.get();
        if (runnable != null) {
            runnableHolder.set(new RunnableWrapper(runnable, context.createAuxThreadContext()));
        }
    }

    private static <T> void wrapCallable(ParameterHolder<Callable<T>> callableHolder,
            ThreadContext context) {
        Callable<T> callable = callableHolder.get();
        if (callable != null) {
            callableHolder.set(new CallableWrapper<T>(callable, context.createAuxThreadContext()));
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
