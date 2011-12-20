/**
 * Copyright 2011 the original author or authors.
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

import org.informantproject.MainEntryPoint;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class SameJvmInformantContainer extends InformantContainer {

    private final IsolatedWeavingClassLoader isolatedWeavingClassLoader;

    public SameJvmInformantContainer() {
        isolatedWeavingClassLoader = new IsolatedWeavingClassLoader(AppUnderTest.class,
                RunnableWithStringArg.class);
    }

    @Override
    protected void initImpl(String options) throws Exception {
        // TODO why does this commented out code break unit tests?
        // ClassLoader previousContextClassLoader = Thread.currentThread().getContextClassLoader();
        // Thread.currentThread().setContextClassLoader(isolatedWeavingClassLoader);
        // try {
        isolatedWeavingClassLoader.newInstance(OpenSameJvmTest.class,
                RunnableWithStringArg.class).run(options);
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
            isolatedWeavingClassLoader.newInstance(appUnderTestClass, AppUnderTest.class).execute();
        } finally {
            Thread.currentThread().setName(previousThreadName);
            Thread.currentThread().setContextClassLoader(previousContextClassLoader);
        }
    }

    @Override
    protected void closeImpl() throws Exception {
        isolatedWeavingClassLoader.newInstance(CloseSameJvmTest.class, Runnable.class).run();
    }

    public interface RunnableWithStringArg {
        void run(String arg);
    }

    public static class OpenSameJvmTest implements RunnableWithStringArg {
        public void run(String options) {
            ClassLoader previousContextClassLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(OpenSameJvmTest.class.getClassLoader());
            try {
                MainEntryPoint.start(options);
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
