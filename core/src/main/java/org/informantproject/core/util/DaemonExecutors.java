/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.core.util;

import java.lang.Thread.UncaughtExceptionHandler;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.informantproject.api.Logger;
import org.informantproject.api.LoggerFactory;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * Convenience methods for creating {@link ExecutorService}s.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
public final class DaemonExecutors {

    private static final String NAME_COUNTER_SUFFIX = "-%d";

    // same as Executors.newCachedThreadPool(), but with custom thread factory to name and daemonize
    // its threads
    public static ExecutorService newCachedThreadPool(String name) {
        // returns a proxy, see see comment below on ExecutorInvocationHandler for details
        return newExecutorServiceProxy(new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L,
                TimeUnit.SECONDS, new SynchronousQueue<Runnable>(), newThreadFactory(name)));
    }

    // same as Executors.newSingleThreadExecutor(), but with custom thread factory to name and
    // daemonize its threads
    public static ExecutorService newSingleThreadExecutor(String name) {
        // returns a proxy, see see comment below on ExecutorInvocationHandler for details
        return newExecutorServiceProxy(new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(), newThreadFactory(name)));
    }

    // same as Executors.newSingleThreadScheduledExecutor(), but with custom thread factory to name
    // and daemonize its threads
    public static ScheduledExecutorService newSingleThreadScheduledExecutor(String name) {
        // returns a proxy, see see comment below on ExecutorInvocationHandler for details
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1,
                newThreadFactory(name));
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return newScheduledExecutorServiceProxy(executor);
    }

    private static ThreadFactory newThreadFactory(String name) {
        return new ThreadFactoryBuilder()
                .setDaemon(true)
                .setNameFormat(name + NAME_COUNTER_SUFFIX)
                .setUncaughtExceptionHandler(new ExceptionHandler())
                .build();
    }

    private static ExecutorService newExecutorServiceProxy(ThreadPoolExecutor executor) {
        return (ExecutorService) Proxy.newProxyInstance(executor.getClass()
                .getClassLoader(), new Class<?>[] { ScheduledExecutorService.class },
                new ExecutorInvocationHandler(executor));
    }

    private static ScheduledExecutorService newScheduledExecutorServiceProxy(
            ThreadPoolExecutor executor) {
        return (ScheduledExecutorService) Proxy.newProxyInstance(executor.getClass()
                .getClassLoader(), new Class<?>[] { ScheduledExecutorService.class },
                new ExecutorInvocationHandler(executor));
    }

    private static class ExceptionHandler implements UncaughtExceptionHandler {
        private static final Logger logger = LoggerFactory.getLogger(ExceptionHandler.class);
        public void uncaughtException(Thread t, Throwable e) {
            logger.error(e.getMessage(), e);
        }
    }

    // was occassionally getting 'OutOfMemoryError: PermGen space' in unit tests because isolated
    // class loaders were being retained via pending Finalizers -> ThreadPoolExecutor
    // -> (guava) ThreadFactoryBuilder$1 -> IsolatedWeavingClassLoader
    //
    // (for a little more detail, see
    // http://docs.oracle.com/javase/7/docs/webnotes/tsg/TSG-VM/html/memleaks.html#gbyvh)
    //
    // tried System.gc(), System.runFinalization() at the end of each test, but those are only
    // "suggestions" and it didn't resolve the issue
    //
    // so resorted to breaking the reference from the ThreadPoolExecutor to the
    // IsolatedWeavingClassLoader by supplying proxy for ThreadPoolExecutor and breaking the
    // reference in shutdown()/shutdownNow()
    private static class ExecutorInvocationHandler implements InvocationHandler {

        private final ThreadPoolExecutor executor;

        private ExecutorInvocationHandler(ThreadPoolExecutor executor) {
            this.executor = executor;
        }

        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object result = method.invoke(executor, args);
            String methodName = method.getName();
            if (methodName.equals("shutdown") || methodName.equals("shutdownNow")) {
                executor.getQueue().clear();
                executor.setThreadFactory(Executors.defaultThreadFactory());
            }
            return result;
        }
    }

    private DaemonExecutors() {}
}
