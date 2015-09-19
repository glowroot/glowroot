/*
 * Copyright 2012-2015 the original author or authors.
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

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.trace.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class TransactionAttributesTest {

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
    public void shouldReadTraceAttributesInAlphaOrder() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithNestedEntries.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        Iterator<Map.Entry<String, List<String>>> i = header.attributes().entrySet().iterator();
        Map.Entry<String, List<String>> entry = i.next();
        assertThat(entry.getKey()).isEqualTo("Wee Four");
        assertThat(entry.getValue()).containsExactly("ww");
        entry = i.next();
        assertThat(entry.getKey()).isEqualTo("Xee Three");
        assertThat(entry.getValue()).containsExactly("xx");
        entry = i.next();
        assertThat(entry.getKey()).isEqualTo("Yee Two");
        assertThat(entry.getValue()).containsExactly("yy", "Yy2", "yy3");
        entry = i.next();
        assertThat(entry.getKey()).isEqualTo("Zee One");
        assertThat(entry.getValue()).containsExactly("bx");
    }

    public static class ShouldGenerateTraceWithNestedEntries implements AppUnderTest {
        @Override
        public void executeApp() {
            new LevelOne().call("ax", "bx");
        }
    }
}
