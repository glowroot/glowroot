/*
 * Copyright 2016 the original author or authors.
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
package org.glowroot.agent.tests.javaagent;

import java.lang.reflect.Proxy;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.impl.JavaagentContainer;

public class MixinProxyIT {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        // this test only works under javaagent because Proxy classes don't get loaded normally
        // through class loader and so IsolatedWeavingClassLoader cannot weave them
        container = JavaagentContainer.create();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
    }

    @After
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void testCausedBy() throws Exception {
        // given
        // when
        container.executeNoExpectedTrace(MixinProxyApp.class);
        // then
        // don't generate ClassFormatError
    }

    public static class MixinProxyApp implements AppUnderTest {
        @Override
        public void executeApp() throws Exception {
            Proxy.getProxyClass(MixinProxyApp.class.getClassLoader(),
                    MyRunnable.class.getInterfaces());
        }
    }

    public static class MyRunnable implements Runnable {
        @Override
        public void run() {}
    }
}
