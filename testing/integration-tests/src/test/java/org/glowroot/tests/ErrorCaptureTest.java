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
import org.glowroot.container.TransactionMarker;
import org.glowroot.container.config.TransactionConfig;
import org.glowroot.container.trace.Trace;
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
        transactionConfig.setSlowThresholdMillis(10000);
        container.getConfigService().updateTransactionConfig(transactionConfig);
        // when
        container.executeAppUnderTest(ShouldCaptureError.class);
        // then
        Trace.Header header = container.getTraceService().getLastTrace();
        assertThat(header.entryCount()).isEqualTo(3);
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        // the exception has no message, and no message is supplied in this test
        assertThat(header.error().get().message()).isEmpty();
        assertThat(header.error().get().exception().isPresent()).isTrue();
        assertThat(entries).hasSize(1);
        Trace.Entry entry1 = entries.get(0);
        assertThat(entry1.error().isPresent()).isFalse();
        assertThat(entry1.childEntries()).hasSize(1);
        Trace.Entry entry2 = entry1.childEntries().get(0);
        assertThat(entry2.error().isPresent()).isFalse();
        assertThat(entry2.childEntries()).hasSize(1);
        Trace.Entry entry3 = entry2.childEntries().get(0);
        assertThat(entry3.error().isPresent()).isFalse();
    }

    @Test
    public void shouldCaptureErrorWithTraceEntryStackTrace() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldCaptureErrorWithTraceEntryStackTrace.class);
        // then
        Trace.Header header = container.getTraceService().getLastTrace();
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(header.error().isPresent()).isFalse();
        assertThat(entries).hasSize(1);
        List<Trace.Entry> childEntries = entries.get(0).childEntries();
        assertThat(childEntries).hasSize(1);
        assertThat(childEntries.get(0).error().get().message()).isNotEmpty();
        List<String> stackTrace = childEntries.get(0).locationStackTraceElements();
        assertThat(stackTrace.get(0))
                .isEqualTo(LogError.class.getName() + ".addNestedErrorEntry(LogError.java)");
    }

    @Test
    public void shouldCaptureErrorWithCausalChain() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldCaptureErrorWithCausalChain.class);
        // then
        Trace.Header header = container.getTraceService().getLastTrace();
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(header.error().isPresent()).isFalse();
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.error().get().message()).isNotEmpty();
        assertThat(entry.message()).isEqualTo("ERROR -- abc");
        Trace.Throwable throwable = entry.error().get().exception().get();
        assertThat(throwable.display()).isEqualTo(
                "java.lang.IllegalStateException: java.lang.IllegalArgumentException: Cause 3");
        assertThat(throwable.stackTraceElements().get(0).toString())
                .startsWith(LogCauseAdvice.class.getName() + ".onAfter(");
        assertThat(throwable.framesInCommonWithEnclosing().isPresent()).isFalse();
        Trace.Throwable cause = throwable.cause().get();
        assertThat(cause.display()).isEqualTo("java.lang.IllegalArgumentException: Cause 3");
        assertThat(cause.stackTraceElements().get(0).toString())
                .startsWith(LogCauseAdvice.class.getName() + ".onAfter(");
        assertThat(cause.framesInCommonWithEnclosing().get()).isGreaterThan(0);
        Set<Integer> causeLineNumbers = Sets.newHashSet();
        causeLineNumbers.add(getFirstLineNumber(cause));
        cause = cause.cause().get();
        assertThat(cause.display()).isEqualTo("java.lang.IllegalStateException: Cause 2");
        assertThat(cause.stackTraceElements().get(0).toString())
                .startsWith(LogCauseAdvice.class.getName() + ".onAfter(");
        assertThat(cause.framesInCommonWithEnclosing().get()).isGreaterThan(0);
        causeLineNumbers.add(getFirstLineNumber(cause));
        cause = cause.cause().get();
        assertThat(cause.display()).isEqualTo("java.lang.NullPointerException: Cause 1");
        assertThat(cause.stackTraceElements().get(0).toString())
                .startsWith(LogCauseAdvice.class.getName() + ".onAfter(");
        assertThat(cause.framesInCommonWithEnclosing().get()).isGreaterThan(0);
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
        Trace.Header header = container.getTraceService().getLastTrace();
        List<Trace.Entry> entries = container.getTraceService().getEntries(header.id());
        assertThat(header.error().isPresent()).isFalse();
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.message()).isEqualTo("outer entry to test nesting level");
        List<Trace.Entry> childEntries = entry.childEntries();
        assertThat(childEntries).hasSize(1);
        Trace.Entry childEntry = childEntries.get(0);
        assertThat(childEntry.error().get().message())
                .isEqualTo("test add nested error entry message");
    }

    private static int getFirstLineNumber(Trace.Throwable cause) {
        String element = cause.stackTraceElements().get(0);
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
            implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws Exception {
            new LogError().addNestedErrorEntry();
        }
    }

    public static class ShouldCaptureErrorWithCausalChain
            implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws Exception {
            new LogCause().log("abc");
        }
    }

    public static class ShouldAddNestedErrorEntry implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws Exception {
            new LogError().addNestedErrorEntry();
        }
    }
}
