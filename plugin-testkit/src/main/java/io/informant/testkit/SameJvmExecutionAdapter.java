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
import io.informant.core.util.ThreadSafe;
import io.informant.core.util.Threads;
import io.informant.core.weaving.Advice;
import io.informant.core.weaving.IsolatedWeavingClassLoader;
import io.informant.testkit.InformantContainer.ExecutionAdapter;

import java.util.Collection;
import java.util.Map;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
class SameJvmExecutionAdapter implements ExecutionAdapter {

    private final Collection<Thread> preExistingThreads;
    private final IsolatedWeavingClassLoader isolatedWeavingClassLoader;

    SameJvmExecutionAdapter(final Map<String, String> properties) throws Exception {
        preExistingThreads = Threads.currentThreads();
        MainEntryPoint.start(properties);
        IsolatedWeavingClassLoader.Builder loader = IsolatedWeavingClassLoader.builder();
        for (PluginDescriptor plugin : Plugins.getPluginDescriptors()) {
            loader.addMixins(plugin.getMixins().toArray(new Mixin[0]));
            loader.addAdvisors(plugin.getAdvisors().toArray(new Advice[0]));
        }
        loader.addBridgeClasses(AppUnderTest.class);
        loader.addExcludePackages("io.informant.api", "io.informant.core", "io.informant.local");
        loader.weavingMetric(MainEntryPoint.getWeavingMetric());
        isolatedWeavingClassLoader = loader.build();
    }

    public int getPort() throws Exception {
        return MainEntryPoint.getPort();
    }

    public void executeAppUnderTest(final Class<? extends AppUnderTest> appUnderTestClass,
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

    public void close() throws Exception {
        Threads.preShutdownCheck(preExistingThreads);
        MainEntryPoint.shutdown();
        Threads.postShutdownCheck(preExistingThreads);
    }
}
