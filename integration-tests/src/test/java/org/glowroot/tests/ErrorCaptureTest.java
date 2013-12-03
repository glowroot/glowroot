/*
 * Copyright 2011-2013 the original author or authors.
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
import org.glowroot.container.trace.ExceptionInfo;
import org.glowroot.container.trace.Trace;
import org.glowroot.tests.plugin.LogCauseAspect;
import org.glowroot.tests.plugin.LogCauseAspect.LogCauseAdvice;

import static org.fest.assertions.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ErrorCaptureTest {

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
    public void shouldCaptureError() throws Exception {
        // given
        container.getConfigService().setStoreThresholdMillis(10000);
        // when
        container.executeAppUnderTest(ShouldCaptureError.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getError()).isNotNull();
        assertThat(trace.getError().getDetail()).isNotNull();
        assertThat(trace.getError().getDetail()).isEqualTo(
                mapOf("erra", null, "errb", mapOf("errc", null, "errd", "xyz")));
        assertThat(trace.getSpans()).hasSize(3);
        assertThat(trace.getSpans().get(0).getError()).isNotNull();
        assertThat(trace.getSpans().get(1).getError()).isNull();
        assertThat(trace.getSpans().get(2).getError()).isNull();
    }

    @Test
    public void shouldCaptureErrorWithSpanStackTrace() throws Exception {
        // given
        container.getConfigService().setStoreThresholdMillis(0);
        // when
        container.executeAppUnderTest(ShouldCaptureErrorWithSpanStackTrace.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getError()).isNull();
        assertThat(trace.getSpans()).hasSize(2);
        assertThat(trace.getSpans().get(1).getError()).isNotNull();
        assertThat(trace.getSpans().get(1).getMessage().getText()).isEqualTo("ERROR -- abc");
        List<String> stackTrace = trace.getSpans().get(1).getStackTrace();
        assertThat(stackTrace.get(0)).startsWith(LogError.class.getName() + ".log(");
    }

    @Test
    public void shouldCaptureErrorWithCausalChain() throws Exception {
        // given
        container.getConfigService().setStoreThresholdMillis(0);
        // when
        container.executeAppUnderTest(ShouldCaptureErrorWithCausalChain.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getError()).isNull();
        assertThat(trace.getSpans()).hasSize(2);
        assertThat(trace.getSpans().get(1).getError()).isNotNull();
        assertThat(trace.getSpans().get(1).getMessage().getText()).isEqualTo("ERROR -- abc");
        ExceptionInfo exception = trace.getSpans().get(1).getError().getException();
        assertThat(exception.getDisplay()).isEqualTo(
                "java.lang.IllegalStateException: java.lang.IllegalArgumentException: Cause 3");
        assertThat(exception.getStackTrace().get(0)).startsWith(
                LogCauseAdvice.class.getName() + ".onAfter(");
        assertThat(exception.getFramesInCommonWithCaused()).isZero();
        ExceptionInfo cause = exception.getCause();
        assertThat(cause.getDisplay()).isEqualTo("java.lang.IllegalArgumentException: Cause 3");
        assertThat(cause.getStackTrace().get(0)).startsWith(
                LogCauseAspect.class.getName() + ".<clinit>(");
        assertThat(cause.getFramesInCommonWithCaused()).isGreaterThan(0);
        Set<Integer> causeLineNumbers = Sets.newHashSet();
        causeLineNumbers.add(getFirstLineNumber(cause));
        cause = cause.getCause();
        assertThat(cause.getDisplay()).isEqualTo("java.lang.IllegalStateException: Cause 2");
        assertThat(cause.getStackTrace().get(0)).startsWith(
                LogCauseAspect.class.getName() + ".<clinit>(");
        assertThat(cause.getFramesInCommonWithCaused()).isGreaterThan(0);
        causeLineNumbers.add(getFirstLineNumber(cause));
        cause = cause.getCause();
        assertThat(cause.getDisplay()).isEqualTo("java.lang.NullPointerException: Cause 1");
        assertThat(cause.getStackTrace().get(0)).startsWith(
                LogCauseAspect.class.getName() + ".<clinit>(");
        assertThat(cause.getFramesInCommonWithCaused()).isGreaterThan(0);
        causeLineNumbers.add(getFirstLineNumber(cause));
        // make sure they are all different line numbers
        assertThat(causeLineNumbers).hasSize(3);
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

    public static class ShouldCaptureErrorWithSpanStackTrace implements AppUnderTest, TraceMarker {
        public void executeApp() throws Exception {
            traceMarker();
        }
        public void traceMarker() throws Exception {
            new LogError().log("abc");
        }
    }

    public static class ShouldCaptureErrorWithCausalChain implements AppUnderTest, TraceMarker {
        public void executeApp() throws Exception {
            traceMarker();
        }
        public void traceMarker() throws Exception {
            new LogCause().log("abc");
        }
    }
}
