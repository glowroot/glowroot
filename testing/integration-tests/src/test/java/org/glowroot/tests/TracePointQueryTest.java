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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.google.common.base.Stopwatch;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.trace.Trace;
import org.glowroot.container.trace.TraceQuery;
import org.glowroot.local.store.StringComparator;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

public class TracePointQueryTest {

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
    public void shouldQueryTraces() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldGenerateTraces.class);
        // then
        verify(container, false);
    }

    @Test
    public void shouldQueryActiveTraces() throws Exception {
        // given
        // when
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future<?> future = executorService.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    container.executeAppUnderTest(ShouldGenerateActiveTraces.class);
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        });
        // then
        verify(container, true);
        // interrupt trace
        container.interruptAppUnderTest();
        future.get();
        verify(container, false);
    }

    private static void verify(Container container, boolean active) throws Exception {
        Stopwatch stopwatch = Stopwatch.createStarted();
        AssertionError failure = null;
        while (stopwatch.elapsed(SECONDS) < 5) {
            try {
                verifyOnce(container, active);
                failure = null;
                break;
            } catch (AssertionError e) {
                failure = e;
            }
            Thread.sleep(10);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static void verifyOnce(Container container, boolean active) throws Exception {
        List<Trace> traces = container.getTraceService().getTraces(TraceQuery.builder()
                .transactionType("nomatch")
                .build());
        assertThat(traces).isEmpty();

        traces = container.getTraceService().getTraces(TraceQuery.builder()
                .transactionType("tt1")
                .build());
        assertThat(traces).hasSize(2);
        verifyActiveStatus(traces, active);

        traces = container.getTraceService().getTraces(TraceQuery.builder()
                .transactionType("tt1")
                .transactionName("nomatch")
                .transactionNameComparator(StringComparator.BEGINS)
                .build());
        assertThat(traces).isEmpty();

        traces = container.getTraceService().getTraces(TraceQuery.builder()
                .transactionType("tt1")
                .transactionName("tnx1")
                .transactionNameComparator(StringComparator.BEGINS)
                .build());
        assertThat(traces).hasSize(2);
        verifyActiveStatus(traces, active);

        traces = container.getTraceService().getTraces(TraceQuery.builder()
                .transactionType("tt1")
                .transactionName("tnx1")
                .transactionNameComparator(StringComparator.BEGINS)
                .headline("nomatch")
                .headlineComparator(StringComparator.BEGINS)
                .build());
        assertThat(traces).isEmpty();

        traces = container.getTraceService().getTraces(TraceQuery.builder()
                .transactionType("tt1")
                .transactionName("tnx1")
                .transactionNameComparator(StringComparator.BEGINS)
                .headline("h1")
                .headlineComparator(StringComparator.BEGINS)
                .build());
        assertThat(traces).hasSize(2);
        verifyActiveStatus(traces, active);

        traces = container.getTraceService().getTraces(TraceQuery.builder()
                .transactionType("tt1")
                .transactionName("tnx1")
                .transactionNameComparator(StringComparator.BEGINS)
                .headline("h1")
                .headlineComparator(StringComparator.BEGINS)
                .error("nomatch")
                .errorComparator(StringComparator.BEGINS)
                .build());
        assertThat(traces).isEmpty();

        traces = container.getTraceService().getTraces(TraceQuery.builder()
                .transactionType("tt1")
                .transactionName("tnx1")
                .transactionNameComparator(StringComparator.BEGINS)
                .headline("h1")
                .headlineComparator(StringComparator.BEGINS)
                .error("e1")
                .errorComparator(StringComparator.BEGINS)
                .build());
        assertThat(traces).hasSize(2);
        verifyActiveStatus(traces, active);

        traces = container.getTraceService().getTraces(TraceQuery.builder()
                .transactionType("tt1")
                .transactionName("tnx1")
                .transactionNameComparator(StringComparator.BEGINS)
                .headline("h1")
                .headlineComparator(StringComparator.BEGINS)
                .error("e1")
                .errorComparator(StringComparator.BEGINS)
                .customAttributeName("nomatch")
                .build());
        assertThat(traces).isEmpty();

        traces = container.getTraceService().getTraces(TraceQuery.builder()
                .transactionType("tt1")
                .transactionName("tnx1")
                .transactionNameComparator(StringComparator.BEGINS)
                .headline("h1")
                .headlineComparator(StringComparator.BEGINS)
                .error("e1")
                .errorComparator(StringComparator.BEGINS)
                .customAttributeName("can1")
                .build());
        assertThat(traces).hasSize(2);
        verifyActiveStatus(traces, active);

        traces = container.getTraceService().getTraces(TraceQuery.builder()
                .transactionType("tt1")
                .transactionName("tnx1")
                .transactionNameComparator(StringComparator.BEGINS)
                .headline("h1")
                .headlineComparator(StringComparator.BEGINS)
                .error("e1")
                .errorComparator(StringComparator.BEGINS)
                .customAttributeName("can1")
                .customAttributeValue("nomatch")
                .customAttributeValueComparator(StringComparator.BEGINS)
                .build());
        assertThat(traces).isEmpty();

        traces = container.getTraceService().getTraces(TraceQuery.builder()
                .transactionType("tt1")
                .transactionName("tnx1")
                .transactionNameComparator(StringComparator.BEGINS)
                .headline("h1")
                .headlineComparator(StringComparator.BEGINS)
                .error("e1")
                .errorComparator(StringComparator.BEGINS)
                .customAttributeName("can1")
                .customAttributeValue("cav1")
                .customAttributeValueComparator(StringComparator.BEGINS)
                .build());
        assertThat(traces).hasSize(2);
        verifyActiveStatus(traces, active);

        // check different comparators

        traces = container.getTraceService().getTraces(TraceQuery.builder()
                .transactionType("tt1")
                .transactionName("tnx")
                .transactionNameComparator(StringComparator.EQUALS)
                .build());
        assertThat(traces).isEmpty();

        traces = container.getTraceService().getTraces(TraceQuery.builder()
                .transactionType("tt1")
                .transactionName("tnx1")
                .transactionNameComparator(StringComparator.EQUALS)
                .build());
        assertThat(traces).hasSize(2);
        verifyActiveStatus(traces, active);

        traces = container.getTraceService().getTraces(TraceQuery.builder()
                .transactionType("tt1")
                .transactionName("tnx")
                .transactionNameComparator(StringComparator.ENDS)
                .build());
        assertThat(traces).isEmpty();

        traces = container.getTraceService().getTraces(TraceQuery.builder()
                .transactionType("tt1")
                .transactionName("nx1")
                .transactionNameComparator(StringComparator.ENDS)
                .build());
        assertThat(traces).hasSize(2);
        verifyActiveStatus(traces, active);

        traces = container.getTraceService().getTraces(TraceQuery.builder()
                .transactionType("tt1")
                .transactionName("nx")
                .transactionNameComparator(StringComparator.CONTAINS)
                .build());
        assertThat(traces).hasSize(2);
        verifyActiveStatus(traces, active);

        traces = container.getTraceService().getTraces(TraceQuery.builder()
                .transactionType("tt1")
                .transactionName("nomatch")
                .transactionNameComparator(StringComparator.CONTAINS)
                .build());
        assertThat(traces).isEmpty();

        traces = container.getTraceService().getTraces(TraceQuery.builder()
                .transactionType("tt1")
                .transactionName("nomatch")
                .transactionNameComparator(StringComparator.NOT_CONTAINS)
                .build());
        assertThat(traces).hasSize(2);
        verifyActiveStatus(traces, active);

        traces = container.getTraceService().getTraces(TraceQuery.builder()
                .transactionType("tt1")
                .transactionName("nx")
                .transactionNameComparator(StringComparator.NOT_CONTAINS)
                .build());
        assertThat(traces).isEmpty();
    }

    private static void verifyActiveStatus(List<Trace> traces, boolean active) {
        for (Trace trace : traces) {
            assertThat(trace.isActive()).isEqualTo(active);
        }
    }

    private static void trace1(boolean active) throws InterruptedException {
        TraceGenerator.builder()
                .transactionType("tt1")
                .transactionName("tnx1")
                .headline("h1")
                .error("e1")
                .user("u1")
                .putCustomAttributes("can1", "cav1")
                .build()
                .call(active);
    }

    private static void trace2(boolean active) throws InterruptedException {
        TraceGenerator.builder()
                .transactionType("tt2")
                .transactionName("tnx2")
                .headline("h2")
                .error("e2")
                .user("u2")
                .putCustomAttributes("can2", "cav2")
                .build()
                .call(active);
    }

    private static void trace3(boolean active) throws InterruptedException {
        TraceGenerator.builder()
                .transactionType("tt3")
                .transactionName("tnx3")
                .headline("h3")
                .build()
                .call(active);
    }

    public static class ShouldGenerateTraces implements AppUnderTest {
        @Override
        public void executeApp() throws InterruptedException {
            trace1(false);
            trace1(false);
            trace2(false);
            trace3(false);
        }
    }

    public static class ShouldGenerateActiveTraces implements AppUnderTest {
        @Override
        public void executeApp() {
            ExecutorService executorService = Executors.newCachedThreadPool();
            executorService.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        trace1(true);
                    } catch (InterruptedException e) {
                    }
                }
            });
            executorService.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        trace1(true);
                    } catch (InterruptedException e) {
                    }
                }
            });
            executorService.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        trace2(true);
                    } catch (InterruptedException e) {
                    }
                }
            });
            executorService.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        trace3(true);
                    } catch (InterruptedException e) {
                    }
                }
            });
            try {
                executorService.awaitTermination(Long.MAX_VALUE, MILLISECONDS);
            } catch (InterruptedException e) {
                executorService.shutdownNow();
            }
        }
    }
}
