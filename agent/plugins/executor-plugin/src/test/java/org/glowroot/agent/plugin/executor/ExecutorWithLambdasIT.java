/*
 * Copyright 2019 the original author or authors.
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
package org.glowroot.agent.plugin.executor;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;

import com.google.common.collect.Queues;
import com.google.common.util.concurrent.MoreExecutors;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.TraceEntryMarker;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.agent.it.harness.impl.JavaagentContainer;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

public class ExecutorWithLambdasIT {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        // tests only work with javaagent container because they need to weave bootstrap classes
        // that implement Executor and ExecutorService
        container = JavaagentContainer.create();
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
    public void shouldCaptureExecute() throws Exception {
        // when
        Trace trace = container.execute(DoExecuteRunnableWithLambda.class);
        // then
        checkTrace(trace);
    }

    @Test
    public void shouldCaptureNestedExecute() throws Exception {
        // when
        Trace trace = container.execute(DoNestedExecuteRunnableWithLambda.class);
        // then
        checkTrace(trace);
    }

    private static void checkTrace(Trace trace) {
        Trace.Header header = trace.getHeader();
        assertThat(header.hasAuxThreadRootTimer()).isTrue();
        assertThat(header.getAsyncTimerCount()).isZero();
        assertThat(header.getAuxThreadRootTimer().getName()).isEqualTo("auxiliary thread");
        assertThat(header.getAuxThreadRootTimer().getCount()).isEqualTo(3);
        // should be 300ms, but margin of error, esp. in travis builds is high
        assertThat(header.getAuxThreadRootTimer().getTotalNanos())
                .isGreaterThanOrEqualTo(MILLISECONDS.toNanos(250));
        assertThat(header.getAuxThreadRootTimer().getChildTimerCount()).isEqualTo(1);
        assertThat(header.getAuxThreadRootTimer().getChildTimer(0).getName())
                .isEqualTo("mock trace entry marker");
        List<Trace.Entry> entries = trace.getEntryList();

        assertThat(entries.size()).isBetween(2, 6);
        for (int i = 0; i < entries.size(); i += 2) {
            assertThat(entries.get(i).getDepth()).isEqualTo(0);
            assertThat(entries.get(i).getMessage()).isEqualTo("auxiliary thread");

            assertThat(entries.get(i + 1).getDepth()).isEqualTo(1);
            assertThat(entries.get(i + 1).getMessage())
                    .isEqualTo("trace entry marker / CreateTraceEntry");
        }
    }

    public static class DoExecuteRunnableWithLambda implements AppUnderTest, TransactionMarker {

        private ThreadPoolExecutor executor;
        private CountDownLatch latch;

        @Override
        public void executeApp() throws Exception {
            executor =
                    new ThreadPoolExecutor(1, 1, 60, MILLISECONDS, Queues.newLinkedBlockingQueue());
            // need to pre-create threads, otherwise lambda execution will be captured by the
            // initial thread run, and won't really test lambda execution capture
            executor.prestartAllCoreThreads();
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            latch = new CountDownLatch(3);
            executor.execute(this::run);
            executor.execute(this::run);
            executor.execute(this::run);
            latch.await();
            executor.shutdown();
            executor.awaitTermination(10, SECONDS);
        }

        private void run() {
            new CreateTraceEntry().traceEntryMarker();
            latch.countDown();
        }
    }

    public static class DoNestedExecuteRunnableWithLambda
            implements AppUnderTest, TransactionMarker {

        private ThreadPoolExecutor executor;
        private CountDownLatch latch;

        @Override
        public void executeApp() throws Exception {
            executor =
                    new ThreadPoolExecutor(1, 1, 60, MILLISECONDS, Queues.newLinkedBlockingQueue());
            // need to pre-create threads, otherwise lambda execution will be captured by the
            // initial thread run, and won't really test lambda execution capture
            executor.prestartAllCoreThreads();
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            MoreExecutors.directExecutor().execute(this::outerRun);
        }

        private void outerRun() {
            latch = new CountDownLatch(3);
            executor.execute(this::innerRun);
            executor.execute(this::innerRun);
            executor.execute(this::innerRun);
            try {
                latch.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            executor.shutdown();
            try {
                executor.awaitTermination(10, SECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        private void innerRun() {
            new CreateTraceEntry().traceEntryMarker();
            latch.countDown();
        }
    }

    private static class CreateTraceEntry implements TraceEntryMarker {

        @Override
        public void traceEntryMarker() {
            try {
                MILLISECONDS.sleep(100);
            } catch (InterruptedException e) {
            }
        }
    }
}
