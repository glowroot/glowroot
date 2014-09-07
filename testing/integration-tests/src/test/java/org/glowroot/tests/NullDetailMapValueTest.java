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
package org.glowroot.tests;

import java.util.List;
import java.util.Map;

import com.google.common.collect.Maps;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.trace.Trace;
import org.glowroot.container.trace.TraceEntry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// also tests null trace attribute value
public class NullDetailMapValueTest {

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
        container.executeAppUnderTest(ShouldGenerateTraceWithNestedEntries.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(trace.getHeadline()).isEqualTo("Level One");
        assertThat(trace.getTransactionName()).isEqualTo("basic test");
        assertThat(entries).hasSize(4);
        TraceEntry entry1 = entries.get(0);
        assertThat(entry1.getMessage().getText()).isEqualTo("Level One");
        assertThat(entry1.getMessage().getDetail()).isEqualTo(mapOf("arg1", "a", "arg2", null,
                "nested1", mapOf("nestedkey11", "a", "nestedkey12", null,
                        "subnested1", mapOf("subnestedkey1", "a", "subnestedkey2", null)),
                "nested2", mapOf("nestedkey21", "a", "nestedkey22", null)));
    }

    private static Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> map = Maps.newHashMap();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }

    private static Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2, String k3,
            Object v3) {
        Map<String, Object> map = Maps.newHashMap();
        map.put(k1, v1);
        map.put(k2, v2);
        map.put(k3, v3);
        return map;
    }

    private static Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2, String k3,
            Object v3, String k4, Object v4) {
        Map<String, Object> map = Maps.newHashMap();
        map.put(k1, v1);
        map.put(k2, v2);
        map.put(k3, v3);
        map.put(k4, v4);
        return map;
    }

    public static class ShouldGenerateTraceWithNestedEntries implements AppUnderTest {
        @Override
        public void executeApp() {
            new LevelOne().call("a", null);
        }
    }
}
