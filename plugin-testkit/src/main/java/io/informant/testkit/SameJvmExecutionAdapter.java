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

    SameJvmExecutionAdapter(Map<String, String> properties) throws InstantiationException,
            IllegalAccessException, ClassNotFoundException {

        preExistingThreads = Threads.currentThreads();

        List<Mixin> mixins = Lists.newArrayList();
        List<Advice> advisors = Lists.newArrayList();
        for (PluginDescriptor plugin : Plugins.getInstalledPluginDescriptors()) {
            mixins.addAll(plugin.getMixins());
            advisors.addAll(plugin.getAdvisors());
        }
        // instantiate class loader
        isolatedWeavingClassLoader = new IsolatedWeavingClassLoader(mixins, advisors,
                AppUnderTest.class, RunnableWithMapArg.class,
                RunnableWithWeavingMetricReturn.class, RunnableWithIntegerReturn.class);
        // start agent inside class loader
        isolatedWeavingClassLoader.newInstance(StartContainer.class, RunnableWithMapArg.class)
                .run(properties);
        // start weaving (needed to retrieve weaving metric from agent first)
        WeavingMetric weavingMetric = isolatedWeavingClassLoader.newInstance(
                GetWeavingMetric.class, RunnableWithWeavingMetricReturn.class).run();
        isolatedWeavingClassLoader.initWeaver(weavingMetric);
    }

    public int getPort() throws InstantiationException, IllegalAccessException,
            ClassNotFoundException {

        return isolatedWeavingClassLoader.newInstance(GetPort.class,
                RunnableWithIntegerReturn.class).run();
    }

    public void executeAppUnderTest(Class<? extends AppUnderTest> appUnderTestClass,
            String threadName) throws Exception {

        String previousThreadName = Thread.currentThread().getName();
        ClassLoader previousContextClassLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setName(threadName);
        Thread.currentThread().setContextClassLoader(isolatedWeavingClassLoader);
        try {
            isolatedWeavingClassLoader.newInstance(appUnderTestClass, AppUnderTest.class)
                    .executeApp();
        } finally {
            Thread.currentThread().setName(previousThreadName);
            Thread.currentThread().setContextClassLoader(previousContextClassLoader);
        }
    }

    public void close() throws InstantiationException, IllegalAccessException,
            ClassNotFoundException, InterruptedException {

        Threads.preShutdownCheck(preExistingThreads);
        isolatedWeavingClassLoader.newInstance(ShutdownContainer.class, Runnable.class).run();
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
}
