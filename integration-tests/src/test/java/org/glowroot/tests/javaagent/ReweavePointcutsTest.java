/*
 * Copyright 2013-2014 the original author or authors.
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
package org.glowroot.tests.javaagent;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.tests.PointcutConfigTest;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ReweavePointcutsTest extends PointcutConfigTest {

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.getSharedJavaagentContainer();
        container.executeAppUnderTest(ShouldExecute1.class);
        addPointcutConfigForExecute1();
        addPointcutConfigForExecute1MetricOnly();
        addPointcutConfigForExecuteWithReturn();
        addPointcutConfigForExecuteWithArgs();
        container.getConfigService().reweavePointcuts();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        // afterEachTest() will remove the pointcut configs, but still need to reweave here
        // in order to get back to square one
        container.getConfigService().reweavePointcuts();
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
    public void shouldRenderTraceHeadline() throws Exception {
        super.shouldRenderTraceHeadline();
    }
}
