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

import org.glowroot.agent.harness.AppUnderTest;
import org.glowroot.agent.harness.Container;
import org.glowroot.agent.harness.Containers;
import org.glowroot.agent.harness.trace.Trace;

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
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header.headline()).isEqualTo("Level One");
        assertThat(header.detail()).isEqualTo(ImmutableMap.of("arg1", "a", "arg2", "b", "nested1",
                ImmutableMap.of("nestedkey11", "a", "nestedkey12", "b", "subnested1",
                        ImmutableMap.of("subnestedkey1", "a", "subnestedkey2", "b")),
                "nested2", ImmutableMap.of("nestedkey21", "a", "nestedkey22", "b")));
        assertThat(header.transactionName()).isEqualTo("basic test");
        assertThat(header.rootTimer().name()).isEqualTo("level one");
        assertThat(header.rootTimer().childTimers()).hasSize(1);
        assertThat(header.rootTimer().childTimers().get(0).name())
                .isEqualTo("level two");
        Trace.Timer levelTwoTimer = header.rootTimer().childTimers().get(0);
        assertThat(levelTwoTimer.childTimers()).hasSize(1);
        assertThat(levelTwoTimer.childTimers().get(0).name()).isEqualTo("level three");
        Trace.Timer levelThreeTimer = levelTwoTimer.childTimers().get(0);
        assertThat(levelThreeTimer.childTimers()).hasSize(1);
        assertThat(levelThreeTimer.childTimers().get(0).name()).isEqualTo("level four");
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(1);
        Trace.Entry entry2 = entries.get(0);
        assertThat(entry2.message()).isEqualTo("Level Two");
        assertThat(entry2.detail()).isEqualTo(ImmutableMap.of("arg1", "ax", "arg2", "bx"));
        List<Trace.Entry> childEntries2 = entry2.childEntries();
        assertThat(childEntries2).hasSize(1);
        Trace.Entry entry3 = childEntries2.get(0);
        assertThat(entry3.message()).isEqualTo("Level Three");
        assertThat(entry3.detail()).isEqualTo(ImmutableMap.of("arg1", "axy", "arg2", "bxy"));
        // there's no way offsetNanos should be 0
        assertThat(entry3.startOffsetNanos()).isGreaterThan(0);
        List<Trace.Entry> childEntries3 = entry3.childEntries();
        assertThat(childEntries3).hasSize(1);
        Trace.Entry entry4 = childEntries3.get(0);
        assertThat(entry4.message()).isEqualTo("Level Four: axy, bxy");
    }

    @Test
    public void shouldReadDetailMapWithBooleans() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithBooleans.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header.headline()).isEqualTo("Level One");
        assertThat(header.detail())
                .isEqualTo(ImmutableMap.of("arg1", false, "arg2", true, "nested1",
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
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header.headline()).isEqualTo("Level One");
        assertThat(header.detail()).isEqualTo(ImmutableMap.of("arg1", 5.0, "arg2", 5.5, "nested1",
                ImmutableMap.of("nestedkey11", 5.0, "nestedkey12", 5.5, "subnested1",
                        ImmutableMap.of("subnestedkey1", 5.0, "subnestedkey2", 5.5)),
                "nested2", ImmutableMap.of("nestedkey21", 5.0, "nestedkey22", 5.5)));
    }

    @Test
    public void shouldReadDetailMapWithBadType() throws Exception {
        // given
        for (int i = 0; i < 4; i++) {
            container.addExpectedLogMessage("org.glowroot.agent.core.model.DetailMapWriter",
                    "detail map has unexpected value type: java.io.File");
        }
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithBadType.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header.headline()).isEqualTo("Level One");
        assertThat(header.detail()).isEqualTo(ImmutableMap.of("arg1", "a", "arg2", "x", "nested1",
                ImmutableMap.of("nestedkey11", "a", "nestedkey12", "x", "subnested1",
                        ImmutableMap.of("subnestedkey1", "a", "subnestedkey2", "x")),
                "nested2", ImmutableMap.of("nestedkey21", "a", "nestedkey22", "x")));
    }

    @Test
    public void shouldReadDetailMapWithNullKey() throws Exception {
        // given
        container.addExpectedLogMessage("org.glowroot.agent.core.model.DetailMapWriter",
                "detail map has null key");
        container.addExpectedLogMessage("org.glowroot.agent.core.model.DetailMapWriter",
                "detail map has null key");
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithNullKey.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header.headline()).isEqualTo("Level One");
        Map<String, Object> map = Maps.newLinkedHashMap();
        map.put("arg1", "useArg2AsKeyAndValue");
        Map<String, Object> nestedMap = Maps.newLinkedHashMap();
        nestedMap.put("nestedkey11", "useArg2AsKeyAndValue");
        map.put("nested1", nestedMap);
        assertThat(header.detail()).isEqualTo(map);
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
