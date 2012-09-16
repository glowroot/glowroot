/**
 * Copyright 2012 the original author or authors.
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
package org.informantproject.test;

import static org.fest.assertions.api.Assertions.assertThat;

import org.informantproject.testkit.AppUnderTest;
import org.informantproject.testkit.InformantContainer;
import org.informantproject.testkit.Trace;
import org.informantproject.testkit.Trace.Span;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class WeavingTest {

    private static InformantContainer container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = InformantContainer.create();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.closeAndDeleteFiles();
    }

    @After
    public void afterEachTest() throws Exception {
        container.getInformant().deleteAllTraces();
    }

    @Test
    public void shouldReadTraces() throws Exception {
        // given
        container.getInformant().setPersistenceThresholdMillis(0);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithNestedSpans.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
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
        public void executeApp() throws Exception {
            new LevelOne().call("a", "b");
        }
    }

    public static class LevelTwoSubclass extends LevelTwo {}
}
