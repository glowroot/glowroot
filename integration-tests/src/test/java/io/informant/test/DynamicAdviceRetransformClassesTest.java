/**
 * Copyright 2013 the original author or authors.
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
package io.informant.test;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import io.informant.container.javaagent.JavaagentContainer;

/**
 * These tests only run on jdk6+
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class DynamicAdviceRetransformClassesTest extends DynamicAdviceTest {

    @BeforeClass
    public static void setUp() throws Exception {
        if (jdk5()) {
            return;
        }
        container = new JavaagentContainer(null, 0, false, false);
        container.executeAppUnderTest(ShouldExecute1.class);
        addPointcutForExecute1();
        addPointcutForExecute1MetricOnly();
        addPointcutForExecuteWithReturn();
        addPointcutForExecuteWithArgs();
        container.getConfigService().retransformClasses();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        if (jdk5()) {
            return;
        }
        container.close();
    }

    @Override
    @After
    public void afterEachTest() throws Exception {
        if (jdk5()) {
            return;
        }
        container.checkAndReset();
    }

    @Override
    @Test
    public void shouldExecute1() throws Exception {
        if (jdk5()) {
            return;
        }
        super.shouldExecute1();
    }

    @Override
    @Test
    public void shouldExecuteWithReturn() throws Exception {
        if (jdk5()) {
            return;
        }
        super.shouldExecuteWithReturn();
    }

    @Override
    @Test
    public void shouldExecuteWithArgs() throws Exception {
        if (jdk5()) {
            return;
        }
        super.shouldExecuteWithArgs();
    }

    // not using org.junit.Assume which reports the test as ignored, since ignored tests seem like
    // something that needs to be revisited and 'un-ignored'
    private static boolean jdk5() {
        return System.getProperty("java.version").startsWith("1.5");
    }
}
