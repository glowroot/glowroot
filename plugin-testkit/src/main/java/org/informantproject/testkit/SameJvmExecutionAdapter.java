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
package org.informantproject.testkit;

import java.util.Collection;
import java.util.List;

import javax.annotation.concurrent.ThreadSafe;

import org.informantproject.api.weaving.Mixin;
import org.informantproject.core.MainEntryPoint;
import org.informantproject.core.config.PluginDescriptor;
import org.informantproject.core.config.Plugins;
import org.informantproject.core.util.UnitTests;
import org.informantproject.core.weaving.Advice;
import org.informantproject.core.weaving.IsolatedWeavingClassLoader;
import org.informantproject.core.weaving.WeavingMetric;
import org.informantproject.testkit.InformantContainer.ExecutionAdapter;

import com.google.common.collect.Lists;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
class SameJvmExecutionAdapter implements ExecutionAdapter {

    private final Collection<Thread> preExistingThreads;
    private volatile IsolatedWeavingClassLoader isolatedWeavingClassLoader;

    SameJvmExecutionAdapter(String agentArgs) throws InstantiationException,
            IllegalAccessException, ClassNotFoundException {

        preExistingThreads = UnitTests.currentThreads();

        List<Mixin> mixins = Lists.newArrayList();
        List<Advice> advisors = Lists.newArrayList();
        for (PluginDescriptor plugin : Plugins.getInstalledPluginDescriptors()) {
            mixins.addAll(plugin.getMixins());
            advisors.addAll(plugin.getAdvisors());
        }
        // instantiate class loader
        isolatedWeavingClassLoader = new IsolatedWeavingClassLoader(mixins, advisors,
                AppUnderTest.class, RunnableWithArg.class, RunnableWithReturn.class);
        // start agent inside class loader
        // TODO fix the type safety warning in the following line using TypeToken after upgrading
        // to guava 12.0
        isolatedWeavingClassLoader.newInstance(StartContainer.class, RunnableWithArg.class)
                .run(agentArgs);
        // start weaving (needed to retrieve weaving metric from agent first)
        WeavingMetric weavingMetric = (WeavingMetric) isolatedWeavingClassLoader.newInstance(
                GetWeavingMetric.class, RunnableWithReturn.class).run();
        isolatedWeavingClassLoader.initWeaver(weavingMetric);
    }

    public int getPort() throws InstantiationException, IllegalAccessException,
            ClassNotFoundException {

        return (Integer) isolatedWeavingClassLoader.newInstance(GetPort.class,
                RunnableWithReturn.class).run();
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

        UnitTests.preShutdownCheck(preExistingThreads);
        isolatedWeavingClassLoader.newInstance(ShutdownContainer.class, Runnable.class).run();
        UnitTests.postShutdownCheck(preExistingThreads);
        // de-reference class loader, otherwise leads to PermGen OutOfMemoryErrors
        isolatedWeavingClassLoader = null;
    }

    public interface RunnableWithArg<T> {
        void run(T args);
    }

    public interface RunnableWithReturn<T> {
        T run();
    }

    public static class StartContainer implements RunnableWithArg<String> {
        public void run(String agentArgs) {
            ClassLoader previousContextClassLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(StartContainer.class.getClassLoader());
            try {
                MainEntryPoint.start(agentArgs);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                Thread.currentThread().setContextClassLoader(previousContextClassLoader);
            }
        }
    }

    public static class GetWeavingMetric implements RunnableWithReturn<WeavingMetric> {
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

    public static class GetPort implements RunnableWithReturn<Integer> {
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
