/*
 * Copyright 2012-2017 the original author or authors.
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
package org.glowroot.agent.tests;

import java.util.List;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.tests.app.LevelOne;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class TransactionAttributesIT {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.create();
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
    public void shouldReadTraceAttributesInAlphaOrder() throws Exception {
        // when
        Trace trace = container.execute(ShouldGenerateTraceWithNestedEntries.class);

        // then
        List<Trace.Attribute> attributes = trace.getHeader().getAttributeList();
        assertThat(attributes.get(0).getName()).isEqualTo("Wee Four");
        assertThat(attributes.get(0).getValueList()).containsExactly("ww");
        assertThat(attributes.get(1).getName()).isEqualTo("Xee Three");
        assertThat(attributes.get(1).getValueList()).containsExactly("xx");
        assertThat(attributes.get(2).getName()).isEqualTo("Yee Two");
        assertThat(attributes.get(2).getValueList()).containsExactly("Yy2", "yy", "yy3");
        assertThat(attributes.get(3).getName()).isEqualTo("Zee One");
        assertThat(attributes.get(3).getValueList()).containsExactly("bx");
    }

    public static class ShouldGenerateTraceWithNestedEntries implements AppUnderTest {
        @Override
        public void executeApp() {
            new LevelOne().call("ax", "bx");
        }
    }
}
