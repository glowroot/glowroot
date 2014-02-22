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

import java.util.Map;

import com.google.common.collect.ImmutableMap;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.trace.Span;
import org.glowroot.container.trace.Trace;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class BasicTest {

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
        assertThat(trace.getGrouping()).isEqualTo("Level One");
        assertThat(trace.getMetricNames()).containsOnly("level one", "level two", "level three",
                "level four");
        assertThat(trace.getSpans()).hasSize(4);
        Span span1 = trace.getSpans().get(0);
        assertThat(span1.getMessage().getText()).isEqualTo("Level One");
        assertThat(span1.getMessage().getDetail()).isEqualTo(mapOf("arg1", "a", "arg2", "b",
                "nested1", mapOf("nestedkey11", "a", "nestedkey12", "b",
                        "subnested1", mapOf("subnestedkey1", "a", "subnestedkey2", "b")),
                "nested2", mapOf("nestedkey21", "a", "nestedkey22", "b")));
        Span span2 = trace.getSpans().get(1);
        assertThat(span2.getMessage().getText()).isEqualTo("Level Two");
        assertThat(span2.getMessage().getDetail()).isEqualTo(mapOf("arg1", "ax", "arg2", "bx"));
        Span span3 = trace.getSpans().get(2);
        assertThat(span3.getMessage().getText()).isEqualTo("Level Three");
        assertThat(span3.getMessage().getDetail()).isEqualTo(mapOf("arg1", "axy", "arg2", "bxy"));
        // offset is measured in nanoseconds so there's no way this should be 0
        assertThat(span3.getOffset()).isGreaterThan(0);
        Span span4 = trace.getSpans().get(3);
        assertThat(span4.getMessage().getText()).isEqualTo("Level Four: axy, bxy");
    }

    @Test
    public void shouldReadTracesWithEnabledFifthMetric() throws Exception {
        // given
        container.getConfigService().setPluginProperty("glowroot-integration-tests",
                "levelFiveEnabled", true);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithNestedSpans.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getGrouping()).isEqualTo("Level One");
        assertThat(trace.getMetricNames()).containsOnly("level one", "level two", "level three",
                "level four", "level five");
        assertThat(trace.getSpans()).hasSize(4);
        Span span1 = trace.getSpans().get(0);
        assertThat(span1.getMessage().getText()).isEqualTo("Level One");
        assertThat(span1.getMessage().getDetail()).isEqualTo(mapOf("arg1", "a", "arg2", "b",
                "nested1", mapOf("nestedkey11", "a", "nestedkey12", "b",
                        "subnested1", mapOf("subnestedkey1", "a", "subnestedkey2", "b")),
                "nested2", mapOf("nestedkey21", "a", "nestedkey22", "b")));
        Span span2 = trace.getSpans().get(1);
        assertThat(span2.getMessage().getText()).isEqualTo("Level Two");
        assertThat(span2.getMessage().getDetail()).isEqualTo(mapOf("arg1", "ax", "arg2", "bx"));
        Span span3 = trace.getSpans().get(2);
        assertThat(span3.getMessage().getText()).isEqualTo("Level Three");
        assertThat(span3.getMessage().getDetail()).isEqualTo(mapOf("arg1", "axy", "arg2", "bxy"));
        // offset is measured in nanoseconds so there's no way this should be 0
        assertThat(span3.getOffset()).isGreaterThan(0);
        Span span4 = trace.getSpans().get(3);
        assertThat(span4.getMessage().getText()).isEqualTo("Level Four: axy, bxy");
    }

    @Test
    public void shouldReadTracesWithEnabledFifthSpan() throws Exception {
        // given
        container.getConfigService().setPluginProperty("glowroot-integration-tests",
                "levelFiveEnabled", true);
        container.getConfigService().setPluginProperty("glowroot-integration-tests",
                "levelFiveSpanEnabled", true);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithNestedSpans.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getGrouping()).isEqualTo("Level One");
        assertThat(trace.getMetricNames()).containsOnly("level one", "level two", "level three",
                "level four", "level five");
        assertThat(trace.getSpans()).hasSize(5);
        Span span1 = trace.getSpans().get(0);
        assertThat(span1.getMessage().getText()).isEqualTo("Level One");
        assertThat(span1.getMessage().getDetail()).isEqualTo(mapOf("arg1", "a", "arg2", "b",
                "nested1", mapOf("nestedkey11", "a", "nestedkey12", "b",
                        "subnested1", mapOf("subnestedkey1", "a", "subnestedkey2", "b")),
                "nested2", mapOf("nestedkey21", "a", "nestedkey22", "b")));
        Span span2 = trace.getSpans().get(1);
        assertThat(span2.getMessage().getText()).isEqualTo("Level Two");
        assertThat(span2.getMessage().getDetail()).isEqualTo(mapOf("arg1", "ax", "arg2", "bx"));
        Span span3 = trace.getSpans().get(2);
        assertThat(span3.getMessage().getText()).isEqualTo("Level Three");
        assertThat(span3.getMessage().getDetail()).isEqualTo(mapOf("arg1", "axy", "arg2", "bxy"));
        // offset is measured in nanoseconds so there's no way this should be 0
        assertThat(span3.getOffset()).isGreaterThan(0);
        Span span4 = trace.getSpans().get(3);
        assertThat(span4.getMessage().getText()).isEqualTo("Level Four: axy, bxy");
        Span span5 = trace.getSpans().get(4);
        assertThat(span5.getMessage().getText()).isEqualTo("Level Five: axy, bxy");
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

    public static class ShouldGenerateTraceWithNestedSpans implements AppUnderTest {
        @Override
        public void executeApp() {
            new LevelOne().call("a", "b");
        }
    }
}
