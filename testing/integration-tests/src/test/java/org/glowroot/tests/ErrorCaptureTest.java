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
import java.util.Set;

import com.google.common.collect.Sets;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.TraceMarker;
import org.glowroot.container.config.TransactionConfig;
import org.glowroot.container.trace.ThrowableInfo;
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
        TransactionConfig transactionConfig = container.getConfigService().getTransactionConfig();
        transactionConfig.setSlowTraceThresholdMillis(10000);
        container.getConfigService().updateTransactionConfig(transactionConfig);
        // when
        container.executeAppUnderTest(ShouldCaptureError.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(trace.getErrorMessage()).isNotNull();
        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).getError()).isNull();
        assertThat(entries.get(1).getError()).isNull();
        assertThat(entries.get(2).getError()).isNull();
    }

    @Test
    public void shouldCaptureErrorWithTraceEntryStackTrace() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldCaptureErrorWithTraceEntryStackTrace.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(trace.getErrorMessage()).isNull();
        assertThat(entries).hasSize(2);
        assertThat(entries.get(1).getError()).isNotNull();
        List<String> stackTrace = entries.get(1).getStackTrace();
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
        assertThat(trace.getErrorMessage()).isNull();
        assertThat(entries).hasSize(1);
        TraceEntry entry = entries.get(0);
        assertThat(entry.getError()).isNotNull();
        assertThat(entry.getMessage().getText()).isEqualTo("ERROR -- abc");
        ThrowableInfo throwable = entry.getError().getThrowable();
        assertThat(throwable.getDisplay()).isEqualTo(
                "java.lang.IllegalStateException: java.lang.IllegalArgumentException: Cause 3");
        assertThat(throwable.getStackTrace().get(0))
                .startsWith(LogCauseAdvice.class.getName() + ".onAfter(");
        assertThat(throwable.getFramesInCommonWithCaused()).isZero();
        ThrowableInfo cause = throwable.getCause();
        assertThat(cause.getDisplay()).isEqualTo("java.lang.IllegalArgumentException: Cause 3");
        assertThat(cause.getStackTrace().get(0))
                .startsWith(LogCauseAdvice.class.getName() + ".onAfter(");
        assertThat(cause.getFramesInCommonWithCaused()).isGreaterThan(0);
        Set<Integer> causeLineNumbers = Sets.newHashSet();
        causeLineNumbers.add(getFirstLineNumber(cause));
        cause = cause.getCause();
        assertThat(cause.getDisplay()).isEqualTo("java.lang.IllegalStateException: Cause 2");
        assertThat(cause.getStackTrace().get(0))
                .startsWith(LogCauseAdvice.class.getName() + ".onAfter(");
        assertThat(cause.getFramesInCommonWithCaused()).isGreaterThan(0);
        causeLineNumbers.add(getFirstLineNumber(cause));
        cause = cause.getCause();
        assertThat(cause.getDisplay()).isEqualTo("java.lang.NullPointerException: Cause 1");
        assertThat(cause.getStackTrace().get(0))
                .startsWith(LogCauseAdvice.class.getName() + ".onAfter(");
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
        assertThat(trace.getErrorMessage()).isNull();
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).getMessage().getText())
                .isEqualTo("outer entry to test nesting level");
        assertThat(entries.get(0).getNestingLevel()).isEqualTo(0);
        assertThat(entries.get(1).getError().getMessage())
                .isEqualTo("test add nested error entry message");
        assertThat(entries.get(1).getNestingLevel()).isEqualTo(1);
    }

    private static int getFirstLineNumber(ThrowableInfo cause) {
        String element = cause.getStackTrace().get(0);
        return Integer
                .parseInt(element.substring(element.lastIndexOf(':') + 1, element.length() - 1));
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

    public static class ShouldCaptureErrorWithTraceEntryStackTrace
            implements AppUnderTest, TraceMarker {
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
