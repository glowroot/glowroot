/*
 * Copyright 2011-2016 the original author or authors.
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
package org.glowroot.agent.tests;

import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.google.common.collect.Sets;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.agent.tests.app.LevelOne;
import org.glowroot.agent.tests.app.LogCause;
import org.glowroot.agent.tests.app.LogError;
import org.glowroot.agent.tests.plugin.LogCauseAspect.LogCauseAdvice;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.TransactionConfig;
import org.glowroot.wire.api.model.Proto;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class ErrorCaptureIT {

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
        container.getConfigService().updateTransactionConfig(
                TransactionConfig.newBuilder()
                        .setSlowThresholdMillis(ProtoOptional.of(10000))
                        .build());

        // when
        Trace trace = container.execute(ShouldCaptureError.class);

        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.hasError()).isTrue();
        assertThat(header.getError().getMessage()).isEqualTo(RuntimeException.class.getName());
        assertThat(header.getError().hasException()).isTrue();

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.hasError()).isFalse();
        assertThat(entry.getDepth()).isEqualTo(0);

        entry = i.next();
        assertThat(entry.hasError()).isFalse();
        assertThat(entry.getDepth()).isEqualTo(1);

        entry = i.next();
        assertThat(entry.hasError()).isFalse();
        assertThat(entry.getDepth()).isEqualTo(2);

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void shouldCaptureErrorWithTraceEntryStackTrace() throws Exception {
        // when
        Trace trace = container.execute(ShouldCaptureErrorWithTraceEntryStackTrace.class);

        // then
        assertThat(trace.getHeader().hasError()).isFalse();

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(1);
        assertThat(entry.getError().getMessage()).isNotEmpty();
        List<Proto.StackTraceElement> stackTraceElements = entry.getLocationStackTraceElementList();
        assertThat(stackTraceElements.get(0).getClassName()).isEqualTo(LogError.class.getName());
        assertThat(stackTraceElements.get(0).getMethodName()).isEqualTo("addNestedErrorEntry");
        assertThat(stackTraceElements.get(0).getFileName()).isEqualTo("LogError.java");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void shouldCaptureErrorWithCausalChain() throws Exception {
        // when
        Trace trace = container.execute(ShouldCaptureErrorWithCausalChain.class);

        // then
        assertThat(trace.getHeader().hasError()).isFalse();

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getError().getMessage()).isNotEmpty();
        assertThat(entry.getMessage()).isEqualTo("ERROR -- abc");

        Proto.Throwable throwable = entry.getError().getException();
        assertThat(throwable.getClassName()).isEqualTo("java.lang.IllegalStateException");
        assertThat(throwable.getMessage()).isEqualTo("java.lang.IllegalArgumentException: Cause 3");
        assertThat(throwable.getStackTraceElementList().get(0).getClassName())
                .isEqualTo(LogCauseAdvice.class.getName());
        assertThat(throwable.getStackTraceElementList().get(0).getMethodName())
                .isEqualTo("onAfter");
        assertThat(throwable.getFramesInCommonWithEnclosing()).isZero();
        Proto.Throwable cause = throwable.getCause();
        assertThat(cause.getClassName()).isEqualTo("java.lang.IllegalArgumentException");
        assertThat(cause.getMessage()).isEqualTo("Cause 3");
        assertThat(cause.getStackTraceElementList().get(0).getClassName())
                .isEqualTo(LogCauseAdvice.class.getName());
        assertThat(cause.getStackTraceElementList().get(0).getMethodName()).isEqualTo("onAfter");
        assertThat(cause.getFramesInCommonWithEnclosing()).isGreaterThan(0);
        Set<Integer> causeLineNumbers = Sets.newHashSet();
        causeLineNumbers.add(cause.getStackTraceElementList().get(0).getLineNumber());
        cause = cause.getCause();
        assertThat(cause.getClassName()).isEqualTo("java.lang.IllegalStateException");
        assertThat(cause.getMessage()).isEqualTo("Cause 2");
        assertThat(cause.getStackTraceElementList().get(0).getClassName())
                .isEqualTo(LogCauseAdvice.class.getName());
        assertThat(cause.getStackTraceElementList().get(0).getMethodName()).isEqualTo("onAfter");
        assertThat(cause.getFramesInCommonWithEnclosing()).isGreaterThan(0);
        causeLineNumbers.add(cause.getStackTraceElementList().get(0).getLineNumber());
        cause = cause.getCause();
        assertThat(cause.getClassName()).isEqualTo("java.lang.NullPointerException");
        assertThat(cause.getMessage()).isEqualTo("Cause 1");
        assertThat(cause.getStackTraceElementList().get(0).getClassName())
                .isEqualTo(LogCauseAdvice.class.getName());
        assertThat(cause.getStackTraceElementList().get(0).getMethodName()).isEqualTo("onAfter");
        assertThat(cause.getFramesInCommonWithEnclosing()).isGreaterThan(0);
        causeLineNumbers.add(cause.getStackTraceElementList().get(0).getLineNumber());
        // make sure they are all different line numbers
        assertThat(causeLineNumbers).hasSize(3);

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void shouldAddNestedErrorEntry() throws Exception {
        // when
        Trace trace = container.execute(ShouldAddNestedErrorEntry.class);

        // then
        assertThat(trace.getHeader().hasError()).isFalse();

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("outer entry to test nesting level");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(1);
        assertThat(entry.getError().getMessage()).isEqualTo("test add nested error entry message");

        assertThat(i.hasNext()).isFalse();
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
