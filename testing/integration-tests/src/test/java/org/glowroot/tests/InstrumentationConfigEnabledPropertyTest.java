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

import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableMap;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.trace.Trace;

import static org.assertj.core.api.Assertions.assertThat;

// InstrumentationConfig's enabledProperty and traceEntryEnabledProperty can only be used by plugins
// and must be configured in glowroot.plugin.json
public class InstrumentationConfigEnabledPropertyTest {

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
    public void shouldReadTracesWithEnabledFifthTimer() throws Exception {
        // given
        container.getConfigService().setPluginProperty("glowroot-integration-tests",
                "levelFiveEnabled", true);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithNestedEntries.class);
        // then
        Trace.Header header = container.getTraceService().getLastTrace();
        assertThat(header.headline()).isEqualTo("Level One");
        assertThat(header.transactionName()).isEqualTo("basic test");
        assertThat(header.detail()).isEqualTo(mapOf("arg1", "a", "arg2", "b", "nested1",
                mapOf("nestedkey11", "a", "nestedkey12", "b", "subnested1",
                        mapOf("subnestedkey1", "a", "subnestedkey2", "b")),
                "nested2", mapOf("nestedkey21", "a", "nestedkey22", "b")));
        assertThat(header.rootTimer().name()).isEqualTo("level one");
        assertThat(header.rootTimer().childTimers().get(0).name()).isEqualTo("level two");
        Trace.Timer levelTwoTimer = header.rootTimer().childTimers().get(0);
        assertThat(levelTwoTimer.childTimers().get(0).name()).isEqualTo("level three");
        Trace.Timer levelThreeTimer = levelTwoTimer.childTimers().get(0);
        assertThat(levelThreeTimer.childTimers().get(0).name()).isEqualTo("level four");
        Trace.Timer levelFourTimer = levelThreeTimer.childTimers().get(0);
        assertThat(levelFourTimer.childTimers().get(0).name()).isEqualTo("level five");
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(1);
        Trace.Entry entry2 = entries.get(0);
        assertThat(entry2.message()).isEqualTo("Level Two");
        assertThat(entry2.detail()).isEqualTo(mapOf("arg1", "ax", "arg2", "bx"));
        List<Trace.Entry> childEntries2 = entry2.childEntries();
        assertThat(childEntries2).hasSize(1);
        Trace.Entry entry3 = childEntries2.get(0);
        assertThat(entry3.message()).isEqualTo("Level Three");
        assertThat(entry3.detail()).isEqualTo(mapOf("arg1", "axy", "arg2", "bxy"));
        // there's no way offsetNanos should be 0
        assertThat(entry3.startOffsetNanos()).isGreaterThan(0);
        List<Trace.Entry> childEntries3 = entry3.childEntries();
        assertThat(childEntries3).hasSize(1);
        Trace.Entry entry4 = childEntries3.get(0);
        assertThat(entry4.message()).isEqualTo("Level Four: axy, bxy");
    }

    @Test
    public void shouldReadTracesWithEnabledFifthEntry() throws Exception {
        // given
        container.getConfigService().setPluginProperty("glowroot-integration-tests",
                "levelFiveEnabled", true);
        container.getConfigService().setPluginProperty("glowroot-integration-tests",
                "levelFiveEntryEnabled", true);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithNestedEntries.class);
        // then
        Trace.Header header = container.getTraceService().getLastTrace();
        assertThat(header.headline()).isEqualTo("Level One");
        assertThat(header.transactionName()).isEqualTo("basic test");
        assertThat(header.detail()).isEqualTo(mapOf("arg1", "a", "arg2", "b", "nested1",
                mapOf("nestedkey11", "a", "nestedkey12", "b", "subnested1",
                        mapOf("subnestedkey1", "a", "subnestedkey2", "b")),
                "nested2", mapOf("nestedkey21", "a", "nestedkey22", "b")));
        assertThat(header.rootTimer().name()).isEqualTo("level one");
        assertThat(header.rootTimer().childTimers().get(0).name()).isEqualTo("level two");
        Trace.Timer levelTwoTimer = header.rootTimer().childTimers().get(0);
        assertThat(levelTwoTimer.childTimers().get(0).name()).isEqualTo("level three");
        Trace.Timer levelThreeTimer = levelTwoTimer.childTimers().get(0);
        assertThat(levelThreeTimer.childTimers().get(0).name()).isEqualTo("level four");
        Trace.Timer levelFourTimer = levelThreeTimer.childTimers().get(0);
        assertThat(levelFourTimer.childTimers().get(0).name()).isEqualTo("level five");
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(entries).hasSize(1);
        Trace.Entry entry2 = entries.get(0);
        assertThat(entry2.message()).isEqualTo("Level Two");
        assertThat(entry2.detail()).isEqualTo(mapOf("arg1", "ax", "arg2", "bx"));
        List<Trace.Entry> childEntries2 = entry2.childEntries();
        assertThat(childEntries2).hasSize(1);
        Trace.Entry entry3 = childEntries2.get(0);
        assertThat(entry3.message()).isEqualTo("Level Three");
        assertThat(entry3.detail()).isEqualTo(mapOf("arg1", "axy", "arg2", "bxy"));
        // there's no way offsetNanos should be 0
        assertThat(entry3.startOffsetNanos()).isGreaterThan(0);
        List<Trace.Entry> childEntries3 = entry3.childEntries();
        assertThat(childEntries3).hasSize(1);
        Trace.Entry entry4 = childEntries3.get(0);
        assertThat(entry4.message()).isEqualTo("Level Four: axy, bxy");
        List<Trace.Entry> childEntries4 = entry4.childEntries();
        assertThat(childEntries4).hasSize(1);
        Trace.Entry entry5 = childEntries4.get(0);
        assertThat(entry5.message()).isEqualTo("Level Five: axy, bxy");
    }

    private static Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2) {
        return ImmutableMap.of(k1, v1, k2, v2);
    }

    private static Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2, String k3,
            Object v3) {

        return ImmutableMap.of(k1, v1, k2, v2, k3, v3);
    }

    private static Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2, String k3,
            Object v3, String k4, Object v4) {

        return ImmutableMap.of(k1, v1, k2, v2, k3, v3, k4, v4);
    }

    public static class ShouldGenerateTraceWithNestedEntries implements AppUnderTest {
        @Override
        public void executeApp() {
            new LevelOne().call("a", "b");
        }
    }
}
