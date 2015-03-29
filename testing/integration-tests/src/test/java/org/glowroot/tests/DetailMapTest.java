/*
 * Copyright 2011-2015 the original author or authors.
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

import java.io.File;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.trace.Timer;
import org.glowroot.container.trace.Trace;
import org.glowroot.container.trace.TraceEntry;

import static org.assertj.core.api.Assertions.assertThat;

public class DetailMapTest {

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
    public void shouldReadDetailMap() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithNestedEntries.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getHeadline()).isEqualTo("Level One");
        assertThat(trace.getCustomDetail()).isEqualTo(
                ImmutableMap.of("arg1", "a", "arg2", "b", "nested1",
                        ImmutableMap.of("nestedkey11", "a", "nestedkey12", "b", "subnested1",
                                ImmutableMap.of("subnestedkey1", "a", "subnestedkey2", "b")),
                        "nested2", ImmutableMap.of("nestedkey21", "a", "nestedkey22", "b")));
        assertThat(trace.getTransactionName()).isEqualTo("basic test");
        assertThat(trace.getRootTimer().getName()).isEqualTo("level one");
        assertThat(trace.getRootTimer().getNestedTimerNames())
                .containsOnly("level two");
        Timer levelTwoTimer = trace.getRootTimer().getNestedTimers().get(0);
        assertThat(levelTwoTimer.getNestedTimerNames()).containsOnly("level three");
        Timer levelThreeTimer = levelTwoTimer.getNestedTimers().get(0);
        assertThat(levelThreeTimer.getNestedTimerNames()).containsOnly("level four");
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(3);
        TraceEntry entry1 = entries.get(0);
        assertThat(entry1.getMessage().getText()).isEqualTo("Level Two");
        assertThat(entry1.getMessage().getDetail()).isEqualTo(
                ImmutableMap.of("arg1", "ax", "arg2", "bx"));
        TraceEntry entry2 = entries.get(1);
        assertThat(entry2.getMessage().getText()).isEqualTo("Level Three");
        assertThat(entry2.getMessage().getDetail()).isEqualTo(
                ImmutableMap.of("arg1", "axy", "arg2", "bxy"));
        // offset is measured in nanoseconds so there's no way this should be 0
        assertThat(entry2.getOffset()).isGreaterThan(0);
        TraceEntry entry3 = entries.get(2);
        assertThat(entry3.getMessage().getText()).isEqualTo("Level Four: axy, bxy");
    }

    @Test
    public void shouldReadDetailMapWithBooleans() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithBooleans.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getHeadline()).isEqualTo("Level One");
        assertThat(trace.getCustomDetail()).isEqualTo(
                ImmutableMap.of("arg1", false, "arg2", true, "nested1",
                        ImmutableMap.of("nestedkey11", false, "nestedkey12", true, "subnested1",
                                ImmutableMap.of("subnestedkey1", false, "subnestedkey2", true)),
                        "nested2", ImmutableMap.of("nestedkey21", false, "nestedkey22", true)));
    }

    @Test
    public void shouldReadDetailMapWithNumbers() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithNumbers.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getHeadline()).isEqualTo("Level One");
        assertThat(trace.getCustomDetail()).isEqualTo(
                ImmutableMap.of("arg1", 5.0, "arg2", 5.5, "nested1",
                        ImmutableMap.of("nestedkey11", 5.0, "nestedkey12", 5.5, "subnested1",
                                ImmutableMap.of("subnestedkey1", 5.0, "subnestedkey2", 5.5)),
                        "nested2", ImmutableMap.of("nestedkey21", 5.0, "nestedkey22", 5.5)));
    }

    @Test
    public void shouldReadDetailMapWithNulls() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithNulls.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getHeadline()).isEqualTo("Level One");
        Map<String, Object> map = Maps.newLinkedHashMap();
        map.put("arg1", 5.0);
        map.put("arg2", null);
        Map<String, Object> nestedMap = Maps.newLinkedHashMap();
        nestedMap.put("nestedkey11", 5.0);
        nestedMap.put("nestedkey12", null);
        map.put("nested1", nestedMap);
        Map<String, Object> subnestedMap = Maps.newLinkedHashMap();
        subnestedMap.put("subnestedkey1", 5.0);
        subnestedMap.put("subnestedkey2", null);
        nestedMap.put("subnested1", subnestedMap);
        Map<String, Object> nestedMap2 = Maps.newLinkedHashMap();
        nestedMap2.put("nestedkey21", 5.0);
        nestedMap2.put("nestedkey22", null);
        map.put("nested2", nestedMap2);
        assertThat(trace.getCustomDetail()).isEqualTo(map);
    }

    @Test
    public void shouldReadDetailMapWithBadType() throws Exception {
        // given
        for (int i = 0; i < 4; i++) {
            container.addExpectedLogMessage("org.glowroot.collector.DetailMapWriter",
                    "detail map has unexpected value type: java.io.File");
        }
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithBadType.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getHeadline()).isEqualTo("Level One");
        assertThat(trace.getCustomDetail()).isEqualTo(
                ImmutableMap.of("arg1", "a", "arg2", "x", "nested1",
                        ImmutableMap.of("nestedkey11", "a", "nestedkey12", "x", "subnested1",
                                ImmutableMap.of("subnestedkey1", "a", "subnestedkey2", "x")),
                        "nested2", ImmutableMap.of("nestedkey21", "a", "nestedkey22", "x")));
    }

    @Test
    public void shouldReadDetailMapWithNullKey() throws Exception {
        // given
        container.addExpectedLogMessage("org.glowroot.collector.DetailMapWriter",
                "detail map has null key");
        container.addExpectedLogMessage("org.glowroot.collector.DetailMapWriter",
                "detail map has null key");
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithNullKey.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getHeadline()).isEqualTo("Level One");
        Map<String, Object> map = Maps.newLinkedHashMap();
        map.put("arg1", "useArg2AsKeyAndValue");
        map.put("", null);
        Map<String, Object> nestedMap = Maps.newLinkedHashMap();
        nestedMap.put("nestedkey11", "useArg2AsKeyAndValue");
        nestedMap.put("", null);
        map.put("nested1", nestedMap);
        assertThat(trace.getCustomDetail()).isEqualTo(map);
    }

    @Test
    public void shouldReadDetailMapWithBadKeyType() throws Exception {
        // given
        for (int i = 0; i < 2; i++) {
            container.addExpectedLogMessage("org.glowroot.collector.DetailMapWriter",
                    "detail map has unexpected key type: java.io.File");
            container.addExpectedLogMessage("org.glowroot.collector.DetailMapWriter",
                    "detail map has unexpected value type: java.io.File");
        }
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithBadKeyType.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getHeadline()).isEqualTo("Level One");
        assertThat(trace.getCustomDetail()).isEqualTo(
                ImmutableMap.of("arg1", "useArg2AsKeyAndValue", "x", "x", "nested1",
                        ImmutableMap.of("nestedkey11", "useArg2AsKeyAndValue", "x", "x")));
    }

    public static class ShouldGenerateTraceWithNestedEntries implements AppUnderTest {
        @Override
        public void executeApp() {
            new LevelOne().call("a", "b");
        }
    }

    public static class ShouldGenerateTraceWithBooleans implements AppUnderTest {
        @Override
        public void executeApp() {
            new LevelOne().call(false, true);
        }
    }

    public static class ShouldGenerateTraceWithNumbers implements AppUnderTest {
        @Override
        public void executeApp() {
            new LevelOne().call(5, 5.5);
        }
    }

    public static class ShouldGenerateTraceWithNulls implements AppUnderTest {
        @Override
        public void executeApp() {
            new LevelOne().call(5, null);
        }
    }

    public static class ShouldGenerateTraceWithBadType implements AppUnderTest {
        @Override
        public void executeApp() {
            new LevelOne().call("a", new File("x"));
        }
    }

    public static class ShouldGenerateTraceWithNullKey implements AppUnderTest {
        @Override
        public void executeApp() {
            new LevelOne().call("useArg2AsKeyAndValue", null);
        }
    }

    public static class ShouldGenerateTraceWithBadKeyType implements AppUnderTest {
        @Override
        public void executeApp() {
            new LevelOne().call("useArg2AsKeyAndValue", new File("x"));
        }
    }
}
