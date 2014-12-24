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
import java.util.Set;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.TraceMarker;
import org.glowroot.container.config.TraceConfig;
import org.glowroot.container.trace.ExceptionInfo;
import org.glowroot.container.trace.Trace;
import org.glowroot.container.trace.TraceEntry;
import org.glowroot.tests.plugin.LogCauseAspect.LogCauseAdvice;

import static org.assertj.core.api.Assertions.assertThat;

public class ErrorCaptureTest {

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
    public void shouldCaptureError() throws Exception {
        // given
        TraceConfig traceConfig = container.getConfigService().getTraceConfig();
        traceConfig.setStoreThresholdMillis(10000);
        container.getConfigService().updateTraceConfig(traceConfig);
        // when
        container.executeAppUnderTest(ShouldCaptureError.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(trace.getError()).isNotNull();
        assertThat(entries).hasSize(4);
        TraceEntry rootEntry = entries.get(0);
        assertThat(rootEntry.getError()).isNotNull();
        assertThat(rootEntry.getError().getDetail()).isNotNull();
        assertThat(rootEntry.getError().getDetail()).isEqualTo(
                mapOf("erra", null, "errb", mapOf("errc", null, "errd", "xyz")));
        assertThat(entries.get(1).getError()).isNull();
        assertThat(entries.get(2).getError()).isNull();
        assertThat(entries.get(3).getError()).isNull();
    }

    @Test
    public void shouldCaptureErrorWithTraceEntryStackTrace() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldCaptureErrorWithTraceEntryStackTrace.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(trace.getError()).isNull();
        assertThat(entries).hasSize(3);
        assertThat(entries.get(2).getError()).isNotNull();
        List<String> stackTrace = entries.get(2).getStackTrace();
        assertThat(stackTrace.get(0))
                .isEqualTo(LogError.class.getName() + ".addNestedErrorEntry(LogError.java)");
    }

    @Test
    public void shouldCaptureErrorWithCausalChain() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldCaptureErrorWithCausalChain.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(trace.getError()).isNull();
        assertThat(entries).hasSize(2);
        assertThat(entries.get(1).getError()).isNotNull();
        assertThat(entries.get(1).getMessage().getText()).isEqualTo("ERROR -- abc");
        ExceptionInfo exception = entries.get(1).getError().getException();
        assertThat(exception.getDisplay()).isEqualTo(
                "java.lang.IllegalStateException: java.lang.IllegalArgumentException: Cause 3");
        assertThat(exception.getStackTrace().get(0)).startsWith(
                LogCauseAdvice.class.getName() + ".onAfter(");
        assertThat(exception.getFramesInCommonWithCaused()).isZero();
        ExceptionInfo cause = exception.getCause();
        assertThat(cause.getDisplay()).isEqualTo("java.lang.IllegalArgumentException: Cause 3");
        assertThat(cause.getStackTrace().get(0)).startsWith(
                LogCauseAdvice.class.getName() + ".onAfter(");
        assertThat(cause.getFramesInCommonWithCaused()).isGreaterThan(0);
        Set<Integer> causeLineNumbers = Sets.newHashSet();
        causeLineNumbers.add(getFirstLineNumber(cause));
        cause = cause.getCause();
        assertThat(cause.getDisplay()).isEqualTo("java.lang.IllegalStateException: Cause 2");
        assertThat(cause.getStackTrace().get(0)).startsWith(
                LogCauseAdvice.class.getName() + ".onAfter(");
        assertThat(cause.getFramesInCommonWithCaused()).isGreaterThan(0);
        causeLineNumbers.add(getFirstLineNumber(cause));
        cause = cause.getCause();
        assertThat(cause.getDisplay()).isEqualTo("java.lang.NullPointerException: Cause 1");
        assertThat(cause.getStackTrace().get(0)).startsWith(
                LogCauseAdvice.class.getName() + ".onAfter(");
        assertThat(cause.getFramesInCommonWithCaused()).isGreaterThan(0);
        causeLineNumbers.add(getFirstLineNumber(cause));
        // make sure they are all different line numbers
        assertThat(causeLineNumbers).hasSize(3);
    }

    @Test
    public void shouldAddNestedErrorEntry() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldAddNestedErrorEntry.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(trace.getError()).isNull();
        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).getNestingLevel())
                .isEqualTo(0);
        assertThat(entries.get(1).getMessage().getText())
                .isEqualTo("outer entry to test nesting level");
        assertThat(entries.get(1).getNestingLevel())
                .isEqualTo(1);
        assertThat(entries.get(2).getError().getText())
                .isEqualTo("test add nested error entry message");
        assertThat(entries.get(2).getNestingLevel())
                .isEqualTo(2);
    }

    private static int getFirstLineNumber(ExceptionInfo cause) {
        String element = cause.getStackTrace().get(0);
        return Integer.parseInt(element.substring(element.lastIndexOf(':') + 1,
                element.length() - 1));
    }

    private static Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> map = Maps.newHashMap();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }

    public static class ShouldCaptureError implements AppUnderTest {
        @Override
        public void executeApp() throws Exception {
            RuntimeException expected = new RuntimeException();
            try {
                new LevelOne(expected).call("a", "b");
            } catch (RuntimeException e) {
                if (e != expected) {
                    // suppress expected exception
                    throw e;
                }
            }
        }
    }

    public static class ShouldCaptureErrorWithTraceEntryStackTrace implements AppUnderTest,
            TraceMarker {
        @Override
        public void executeApp() throws Exception {
            traceMarker();
        }
        @Override
        public void traceMarker() throws Exception {
            new LogError().addNestedErrorEntry();
        }
    }

    public static class ShouldCaptureErrorWithCausalChain implements AppUnderTest, TraceMarker {
        @Override
        public void executeApp() throws Exception {
            traceMarker();
        }
        @Override
        public void traceMarker() throws Exception {
            new LogCause().log("abc");
        }
    }

    public static class ShouldAddNestedErrorEntry implements AppUnderTest, TraceMarker {
        @Override
        public void executeApp() throws Exception {
            traceMarker();
        }
        @Override
        public void traceMarker() throws Exception {
            new LogError().addNestedErrorEntry();
        }
    }
}
