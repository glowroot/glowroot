/*
 * Copyright 2012-2014 the original author or authors.
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
package org.glowroot.tests;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.trace.Span;
import org.glowroot.container.trace.Trace;

import static org.fest.assertions.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class WeavingTest {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.getSharedContainer();
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
    public void shouldReadTraces() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithNestedSpans.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        Span span1 = trace.getSpans().get(0);
        Span span2 = trace.getSpans().get(1);
        assertThat(span1.getMessage().getText()).isEqualTo("Level One");
        assertThat(span2.getMessage().getText()).isEqualTo("Level Two");
    }

    public static class ShouldGenerateTraceWithNestedSpans implements AppUnderTest {
        public ShouldGenerateTraceWithNestedSpans() {
            // force the subclass to be loaded first
            LevelTwoSubclass.class.getClass();
        }
        @Override
        public void executeApp() {
            new LevelOne().call("a", "b");
        }
    }

    public static class LevelTwoSubclass extends LevelTwo {}
}
