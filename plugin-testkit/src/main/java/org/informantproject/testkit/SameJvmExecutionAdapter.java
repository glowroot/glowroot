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

import java.util.List;

import javax.annotation.concurrent.ThreadSafe;

import org.informantproject.api.weaving.Mixin;
import org.informantproject.core.MainEntryPoint;
import org.informantproject.core.config.PluginDescriptor;
import org.informantproject.core.config.Plugins;
import org.informantproject.core.weaving.Advice;
import org.informantproject.core.weaving.IsolatedWeavingClassLoader;
import org.informantproject.testkit.InformantContainer.ExecutionAdapter;

import com.google.common.collect.Lists;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
class SameJvmExecutionAdapter implements ExecutionAdapter {

    private volatile IsolatedWeavingClassLoader isolatedWeavingClassLoader;

    SameJvmExecutionAdapter(String agentArgs) throws InstantiationException,
            IllegalAccessException, ClassNotFoundException {

        List<Mixin> mixins = Lists.newArrayList();
        List<Advice> advisors = Lists.newArrayList();
        for (PluginDescriptor plugin : Plugins.getInstalledPluginDescriptors()) {
            mixins.addAll(plugin.getMixins());
            advisors.addAll(plugin.getAdvisors());
        }
        isolatedWeavingClassLoader = new IsolatedWeavingClassLoader(mixins, advisors,
                AppUnderTest.class, RunnableWithStringArg.class, RunnableWithIntReturn.class);
        isolatedWeavingClassLoader.newInstance(StartContainer.class, RunnableWithStringArg.class)
                .run(agentArgs);
    }

    public int getPort() throws InstantiationException, IllegalAccessException,
            ClassNotFoundException {

        return isolatedWeavingClassLoader.newInstance(GetPort.class, RunnableWithIntReturn.class)
                .run();
    }

    public void executeAppUnderTestImpl(Class<? extends AppUnderTest> appUnderTestClass,
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

    public void closeImpl() throws InstantiationException, IllegalAccessException,
            ClassNotFoundException {

        isolatedWeavingClassLoader.newInstance(ShutdownContainer.class, Runnable.class).run();
        // de-reference class loader, otherwise leads to PermGen OutOfMemoryErrors
        isolatedWeavingClassLoader = null;
    }

    public interface RunnableWithStringArg {
        void run(String arg);
    }

    public interface RunnableWithIntReturn {
        int run();
    }

    public static class StartContainer implements RunnableWithStringArg {
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

    public static class GetPort implements RunnableWithIntReturn {
        public int run() {
            ClassLoader previousContextClassLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(StartContainer.class.getClassLoader());
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
            Thread.currentThread().setContextClassLoader(StartContainer.class.getClassLoader());
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
