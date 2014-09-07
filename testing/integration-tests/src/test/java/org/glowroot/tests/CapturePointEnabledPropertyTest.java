/*
 * Copyright 2011-2014 the original author or authors.
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
import org.glowroot.container.trace.TraceEntry;
import org.glowroot.container.trace.TraceMetric;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// CapturePoint's enabledProperty and traceEntryEnabledProperty can only be used by plugins and
// must be configured in glowroot.plugin.json
public class CapturePointEnabledPropertyTest {

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
    public void shouldReadTracesWithEnabledFifthMetric() throws Exception {
        // given
        container.getConfigService().setPluginProperty("glowroot-integration-tests",
                "levelFiveEnabled", true);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithNestedEntries.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(trace.getHeadline()).isEqualTo("Level One");
        assertThat(trace.getTransactionName()).isEqualTo("basic test");
        assertThat(trace.getRootMetric().getName()).isEqualTo("level one");
        assertThat(trace.getRootMetric().getNestedMetricNames())
                .containsOnly("level two");
        TraceMetric levelTwoMetric = trace.getRootMetric().getNestedMetrics().get(0);
        assertThat(levelTwoMetric.getNestedMetricNames()).containsOnly("level three");
        TraceMetric levelThreeMetric = levelTwoMetric.getNestedMetrics().get(0);
        assertThat(levelThreeMetric.getNestedMetricNames()).containsOnly("level four");
        TraceMetric levelFourMetric = levelThreeMetric.getNestedMetrics().get(0);
        assertThat(levelFourMetric.getNestedMetricNames()).containsOnly("level five");
        assertThat(entries).hasSize(4);
        TraceEntry entry1 = entries.get(0);
        assertThat(entry1.getMessage().getText()).isEqualTo("Level One");
        assertThat(entry1.getMessage().getDetail()).isEqualTo(mapOf("arg1", "a", "arg2", "b",
                "nested1", mapOf("nestedkey11", "a", "nestedkey12", "b",
                        "subnested1", mapOf("subnestedkey1", "a", "subnestedkey2", "b")),
                "nested2", mapOf("nestedkey21", "a", "nestedkey22", "b")));
        TraceEntry entry2 = entries.get(1);
        assertThat(entry2.getMessage().getText()).isEqualTo("Level Two");
        assertThat(entry2.getMessage().getDetail()).isEqualTo(mapOf("arg1", "ax", "arg2", "bx"));
        TraceEntry entry3 = entries.get(2);
        assertThat(entry3.getMessage().getText()).isEqualTo("Level Three");
        assertThat(entry3.getMessage().getDetail()).isEqualTo(mapOf("arg1", "axy", "arg2", "bxy"));
        // offset is measured in nanoseconds so there's no way this should be 0
        assertThat(entry3.getOffset()).isGreaterThan(0);
        TraceEntry entry4 = entries.get(3);
        assertThat(entry4.getMessage().getText()).isEqualTo("Level Four: axy, bxy");
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
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(trace.getHeadline()).isEqualTo("Level One");
        assertThat(trace.getTransactionName()).isEqualTo("basic test");
        assertThat(trace.getRootMetric().getName()).isEqualTo("level one");
        assertThat(trace.getRootMetric().getNestedMetricNames()).containsOnly("level two");
        TraceMetric levelTwoMetric = trace.getRootMetric().getNestedMetrics().get(0);
        assertThat(levelTwoMetric.getNestedMetricNames()).containsOnly("level three");
        TraceMetric levelThreeMetric = levelTwoMetric.getNestedMetrics().get(0);
        assertThat(levelThreeMetric.getNestedMetricNames()).containsOnly("level four");
        TraceMetric levelFourMetric = levelThreeMetric.getNestedMetrics().get(0);
        assertThat(levelFourMetric.getNestedMetricNames()).containsOnly("level five");
        assertThat(entries).hasSize(5);
        TraceEntry entry1 = entries.get(0);
        assertThat(entry1.getMessage().getText()).isEqualTo("Level One");
        assertThat(entry1.getMessage().getDetail()).isEqualTo(mapOf("arg1", "a", "arg2", "b",
                "nested1", mapOf("nestedkey11", "a", "nestedkey12", "b",
                        "subnested1", mapOf("subnestedkey1", "a", "subnestedkey2", "b")),
                "nested2", mapOf("nestedkey21", "a", "nestedkey22", "b")));
        TraceEntry entry2 = entries.get(1);
        assertThat(entry2.getMessage().getText()).isEqualTo("Level Two");
        assertThat(entry2.getMessage().getDetail()).isEqualTo(mapOf("arg1", "ax", "arg2", "bx"));
        TraceEntry entry3 = entries.get(2);
        assertThat(entry3.getMessage().getText()).isEqualTo("Level Three");
        assertThat(entry3.getMessage().getDetail()).isEqualTo(mapOf("arg1", "axy", "arg2", "bxy"));
        // offset is measured in nanoseconds so there's no way this should be 0
        assertThat(entry3.getOffset()).isGreaterThan(0);
        TraceEntry entry4 = entries.get(3);
        assertThat(entry4.getMessage().getText()).isEqualTo("Level Four: axy, bxy");
        TraceEntry entry5 = entries.get(4);
        assertThat(entry5.getMessage().getText()).isEqualTo("Level Five: axy, bxy");
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
