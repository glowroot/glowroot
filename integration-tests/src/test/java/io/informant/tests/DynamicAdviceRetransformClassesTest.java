/*
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
package io.informant.tests;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import io.informant.container.javaagent.JavaagentContainer;
import io.informant.test.util.IgnoreOnJdk5;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@RunWith(IgnoreOnJdk5.class)
public class DynamicAdviceRetransformClassesTest extends DynamicAdviceTest {

    @BeforeClass
    public static void setUp() throws Exception {
        container = new JavaagentContainer(null, 0, false, false);
        container.executeAppUnderTest(ShouldExecute1.class);
        addSpanPointcutForExecute1();
        addSpanPointcutForExecute1MetricOnly();
        addSpanPointcutForExecuteWithReturn();
        addTracePointcutForExecuteWithArgs();
        container.getConfigService().retransformClasses();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
    }

    @Override
    @After
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Override
    @Test
    public void shouldExecute1() throws Exception {
        super.shouldExecute1();
    }

    @Override
    @Test
    public void shouldRenderSpanTextWithReturnValue() throws Exception {
        super.shouldRenderSpanTextWithReturnValue();
    }

    @Override
    @Test
    public void shouldRenderTraceGrouping() throws Exception {
        super.shouldRenderTraceGrouping();
    }
}
