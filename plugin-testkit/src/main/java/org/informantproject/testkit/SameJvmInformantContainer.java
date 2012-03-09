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

import org.informantproject.core.MainEntryPoint;
import org.informantproject.shaded.aspectj.bridge.context.CompilationAndWeavingContext;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class SameJvmInformantContainer extends InformantContainer {

    private IsolatedWeavingClassLoader isolatedWeavingClassLoader;

    public SameJvmInformantContainer() {
        isolatedWeavingClassLoader = new IsolatedWeavingClassLoader(AppUnderTest.class,
                RunnableWithStringArg.class);
    }

    @Override
    protected void initImpl(String agentArgs) throws Exception {
        // TODO why does this commented out code break unit tests?
        // ClassLoader previousContextClassLoader = Thread.currentThread().getContextClassLoader();
        // Thread.currentThread().setContextClassLoader(isolatedWeavingClassLoader);
        // try {
        MainEntryPoint.setAspectjAopXmlSearchPath();
        isolatedWeavingClassLoader.newInstance(OpenSameJvmTest.class,
                RunnableWithStringArg.class).run(agentArgs);
        // } finally {
        // Thread.currentThread().setContextClassLoader(previousContextClassLoader);
        // }
    }

    @Override
    protected void executeAppUnderTestImpl(Class<? extends AppUnderTest> appUnderTestClass,
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

    @Override
    protected void closeImpl() throws Exception {
        isolatedWeavingClassLoader.newInstance(CloseSameJvmTest.class, Runnable.class).run();
        // de-reference class loader, otherwise leads to PermGen OutOfMemoryErrors
        isolatedWeavingClassLoader = null;
        // AspectJ has a memory leak that prevents the IsolatedWeavingClassLoader from being
        // released, see https://bugs.eclipse.org/bugs/show_bug.cgi?id=373195
        // the sequence of calls below clears the map that is retaining the Threads which are in
        // turn retaining their context class loaders
        CompilationAndWeavingContext.setMultiThreaded(false);
        CompilationAndWeavingContext.reset();
        CompilationAndWeavingContext.setMultiThreaded(true);
    }

    public interface RunnableWithStringArg {
        void run(String arg);
    }

    public static class OpenSameJvmTest implements RunnableWithStringArg {
        public void run(String agentArgs) {
            ClassLoader previousContextClassLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(OpenSameJvmTest.class.getClassLoader());
            try {
                MainEntryPoint.start(agentArgs);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                Thread.currentThread().setContextClassLoader(previousContextClassLoader);
            }
        }
    }

    public static class CloseSameJvmTest implements Runnable {
        public void run() {
            try {
                MainEntryPoint.shutdown();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
