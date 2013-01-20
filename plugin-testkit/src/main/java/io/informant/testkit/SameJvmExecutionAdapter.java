/**
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.testkit;

import io.informant.api.weaving.Mixin;
import io.informant.core.MainEntryPoint;
import io.informant.core.config.PluginDescriptor;
import io.informant.core.config.Plugins;
import io.informant.core.util.Threads;
import io.informant.core.weaving.Advice;
import io.informant.core.weaving.IsolatedWeavingClassLoader;
import io.informant.core.weaving.WeavingMetric;
import io.informant.testkit.InformantContainer.ExecutionAdapter;

import java.sql.DriverManager;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.Callable;

import javax.annotation.concurrent.ThreadSafe;

import org.fest.reflect.core.Reflection;

import com.google.common.collect.Lists;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
class SameJvmExecutionAdapter implements ExecutionAdapter {

    private final Collection<Thread> preExistingThreads;
    private volatile IsolatedWeavingClassLoader isolatedWeavingClassLoader;

    SameJvmExecutionAdapter(final Map<String, String> properties) throws Exception {
        preExistingThreads = Threads.currentThreads();
        final List<Mixin> mixins = Lists.newArrayList();
        final List<Advice> advisors = Lists.newArrayList();
        for (PluginDescriptor plugin : Plugins.getInstalledPluginDescriptors()) {
            mixins.addAll(plugin.getMixins());
            advisors.addAll(plugin.getAdvisors());
        }
        // temporary thread is used to ensure ThreadLocals (e.g. Gson.calls) don't get bound to the
        // main thread which could prevent the isolated weaving class loader from being gc'd
        executeInTemporaryThread(new Callable<Void>() {
            public Void call() throws Exception {
                // instantiate class loader
                isolatedWeavingClassLoader = new IsolatedWeavingClassLoader(mixins, advisors,
                        AppUnderTest.class, RunnableWithMapArg.class,
                        RunnableWithWeavingMetricReturn.class, RunnableWithIntegerReturn.class);
                // start agent inside class loader
                isolatedWeavingClassLoader.newInstance(StartContainer.class,
                        RunnableWithMapArg.class)
                        .run(properties);
                // start weaving (needed to retrieve weaving metric from agent first)
                WeavingMetric weavingMetric = isolatedWeavingClassLoader.newInstance(
                        GetWeavingMetric.class, RunnableWithWeavingMetricReturn.class).run();
                isolatedWeavingClassLoader.initWeaver(weavingMetric);

                return null;
            }
        });
    }

    public int getPort() throws Exception {
        return executeInTemporaryThread(new Callable<Integer>() {
            public Integer call() throws Exception {
                return isolatedWeavingClassLoader.newInstance(GetPort.class,
                        RunnableWithIntegerReturn.class).run();
            }
        });
    }

    public void executeAppUnderTest(final Class<? extends AppUnderTest> appUnderTestClass,
            String threadName) throws Exception {

        // temporary thread is used to ensure ThreadLocals (e.g. Gson.calls) don't get bound to the
        // main thread which could prevent the isolated weaving class loader from being gc'd
        executeInTemporaryThread(threadName, new Callable<Void>() {
            public Void call() throws Exception {
                isolatedWeavingClassLoader.newInstance(appUnderTestClass, AppUnderTest.class)
                        .executeApp();
                return null;
            }
        });
    }

    public void close() throws Exception {
        Threads.preShutdownCheck(preExistingThreads);
        executeInTemporaryThread(new Callable<Void>() {
            public Void call() throws Exception {
                isolatedWeavingClassLoader.newInstance(ShutdownContainer.class, Runnable.class)
                        .run();
                return null;
            }
        });
        Threads.postShutdownCheck(preExistingThreads);
        // de-reference class loader, otherwise leads to PermGen OutOfMemoryErrors
        if (System.getProperty("java.version").startsWith("1.5")) {
            cleanUpDriverManagerInJdk5(isolatedWeavingClassLoader);
            // unfortunately, JDK 1.5 still has trouble releasing the IsolatedWeavingClassLoader
            // even though there are only weak references left to it (verified by taking a heap dump
            // here)
            // luckily JDK 1.6 has no such problem
        }
        isolatedWeavingClassLoader = null;
    }

    private static <V> V executeInTemporaryThread(Callable<V> callable) throws Exception {
        return executeInTemporaryThread(null, callable);
    }

    private static <V> V executeInTemporaryThread(String threadName, Callable<V> callable)
            throws Exception {
        ThreadWithReturnValue<V> executeAppThread = new ThreadWithReturnValue<V>(callable);
        if (threadName != null) {
            executeAppThread.setName(threadName);
        }
        executeAppThread.start();
        executeAppThread.join();
        if (executeAppThread.exception != null) {
            throw executeAppThread.exception;
        }
        return executeAppThread.returnValue;
    }

    private static void cleanUpDriverManagerInJdk5(ClassLoader classLoader) {
        Vector<?> drivers = Reflection.field("drivers").ofType(Vector.class)
                .in(DriverManager.class).get();
        for (Iterator<?> i = drivers.iterator(); i.hasNext();) {
            Object driverInfo = i.next();
            Class<?> driverClass = Reflection.field("driverClass").ofType(Class.class)
                    .in(driverInfo).get();
            if (driverClass.getClassLoader() == classLoader) {
                i.remove();
            }
        }
    }

    public interface RunnableWithMapArg {
        void run(Map<String, String> args);
    }

    public interface RunnableWithIntegerReturn {
        Integer run();
    }

    public interface RunnableWithWeavingMetricReturn {
        WeavingMetric run();
    }

    public static class StartContainer implements RunnableWithMapArg {
        public void run(Map<String, String> properties) {
            ClassLoader previousContextClassLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(StartContainer.class.getClassLoader());
            try {
                MainEntryPoint.start(properties);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                Thread.currentThread().setContextClassLoader(previousContextClassLoader);
            }
        }
    }

    public static class GetWeavingMetric implements RunnableWithWeavingMetricReturn {
        public WeavingMetric run() {
            ClassLoader previousContextClassLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(GetWeavingMetric.class.getClassLoader());
            try {
                return MainEntryPoint.getWeavingMetric();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                Thread.currentThread().setContextClassLoader(previousContextClassLoader);
            }
        }
    }

    public static class GetPort implements RunnableWithIntegerReturn {
        public Integer run() {
            ClassLoader previousContextClassLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(GetPort.class.getClassLoader());
            try {
                return MainEntryPoint.getPort();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                Thread.currentThread().setContextClassLoader(previousContextClassLoader);
            }
        }
    }

    public static class ShutdownContainer implements Runnable {
        public void run() {
            ClassLoader previousContextClassLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(ShutdownContainer.class.getClassLoader());
            try {
                MainEntryPoint.shutdown();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                Thread.currentThread().setContextClassLoader(previousContextClassLoader);
            }
        }
    }

    private static class ThreadWithReturnValue<V> extends Thread {

        private final Callable<V> callable;
        private volatile V returnValue;
        private volatile Exception exception;

        public ThreadWithReturnValue(Callable<V> callable) {
            this.callable = callable;
        }

        @Override
        public void run() {
            try {
                returnValue = callable.call();
            } catch (Exception e) {
                exception = e;
            }
        }
    }
}
