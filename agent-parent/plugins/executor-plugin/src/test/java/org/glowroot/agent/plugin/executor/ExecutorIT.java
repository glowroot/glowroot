/*
 * Copyright 2016 the original author or authors.
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.TransactionConfig;
import org.glowroot.wire.api.model.Proto.OptionalInt32;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;

public class ExecutorIT {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        // tests only work with javaagent container because they need to weave bootstrap classes
        // that implement Executor and ExecutorService
        container = Containers.createJavaagent();
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
    public void shouldCaptureCallable() throws Exception {
        // given
        container.getConfigService().updateTransactionConfig(
                TransactionConfig.newBuilder()
                        .setSlowThresholdMillis(OptionalInt32.newBuilder().setValue(0))
                        .setProfilingIntervalMillis(OptionalInt32.newBuilder().setValue(20).build())
                        .build());
        // when
        Trace trace = container.execute(DoSomeCallableWork.class);
        // then
        checkTrace(trace);
    }

    @Test
    public void shouldCaptureRunnableAndCallable() throws Exception {
        // given
        container.getConfigService().updateTransactionConfig(
                TransactionConfig.newBuilder()
                        .setSlowThresholdMillis(OptionalInt32.newBuilder().setValue(0))
                        .setProfilingIntervalMillis(OptionalInt32.newBuilder().setValue(20).build())
                        .build());
        // when
        Trace trace = container.execute(DoSomeRunnableAndCallableWork.class);
        // then
        checkTrace(trace);
    }

    @Test
    public void shouldNotCaptureTraceEntryForEmptyAuxThread() throws Exception {
        // given
        container.getConfigService().updateTransactionConfig(
                TransactionConfig.newBuilder()
                        .setSlowThresholdMillis(OptionalInt32.newBuilder().setValue(0))
                        .setProfilingIntervalMillis(OptionalInt32.newBuilder().setValue(20).build())
                        .build());
        // when
        Trace trace = container.execute(DoSomeSimpleRunnableWork.class);
        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.getAuxThreadRootTimerCount()).isEqualTo(1);
        assertThat(header.getAsyncRootTimerCount()).isZero();
        assertThat(header.getAuxThreadRootTimer(0).getName()).isEqualTo("auxiliary thread");
        assertThat(header.getAuxThreadRootTimer(0).getCount()).isEqualTo(3);
        assertThat(header.getAuxThreadRootTimer(0).getTotalNanos())
                .isGreaterThanOrEqualTo(MILLISECONDS.toNanos(500));
        assertThat(header.getAuxThreadRootTimer(0).getChildTimerCount()).isZero();
        assertThat(trace.hasMainThreadProfile()).isTrue();
        assertThat(header.getMainThreadProfileSampleCount()).isGreaterThanOrEqualTo(1);
        assertThat(trace.hasAuxThreadProfile()).isTrue();
        assertThat(header.getAuxThreadProfileSampleCount()).isGreaterThanOrEqualTo(1);
        assertThat(header.getEntryCount()).isZero();
    }

    @Test
    public void shouldNotCaptureAlreadyCompletedFutureGet() throws Exception {
        // given
        // when
        Trace trace = container.execute(CallFutureGetOnAlreadyCompletedFuture.class);
        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.getMainThreadRootTimer().getChildTimerCount()).isZero();
    }

    private static void checkTrace(Trace trace) {
        Trace.Header header = trace.getHeader();
        assertThat(header.getMainThreadRootTimer().getChildTimerCount()).isEqualTo(1);
        assertThat(header.getMainThreadRootTimer().getChildTimer(0).getName())
                .isEqualTo("wait on future");
        assertThat(header.getMainThreadRootTimer().getChildTimer(0).getCount())
                .isGreaterThanOrEqualTo(1);
        assertThat(header.getMainThreadRootTimer().getChildTimer(0).getCount())
                .isLessThanOrEqualTo(3);
        assertThat(header.getAuxThreadRootTimerCount()).isEqualTo(1);
        assertThat(header.getAsyncRootTimerCount()).isZero();
        assertThat(header.getAuxThreadRootTimer(0).getName()).isEqualTo("auxiliary thread");
        assertThat(header.getAuxThreadRootTimer(0).getCount()).isEqualTo(3);
        assertThat(header.getAuxThreadRootTimer(0).getTotalNanos())
                .isGreaterThanOrEqualTo(MILLISECONDS.toNanos(250));
        assertThat(header.getAuxThreadRootTimer(0).getChildTimerCount()).isEqualTo(1);
        assertThat(header.getAuxThreadRootTimer(0).getChildTimer(0).getName())
                .isEqualTo("mock trace marker");
        assertThat(trace.hasMainThreadProfile()).isTrue();
        assertThat(header.getMainThreadProfileSampleCount()).isGreaterThanOrEqualTo(1);
        assertThat(trace.hasAuxThreadProfile()).isTrue();
        assertThat(header.getAuxThreadProfileSampleCount()).isGreaterThanOrEqualTo(1);
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(6);
        Trace.Entry entry1 = entries.get(0);
        assertThat(entry1.getDepth()).isEqualTo(0);
        assertThat(entry1.getMessage()).isEqualTo("auxiliary thread");
        Trace.Entry entry2 = entries.get(1);
        assertThat(entry2.getDepth()).isEqualTo(1);
        assertThat(entry2.getMessage()).isEqualTo("trace marker / CreateTraceEntry");
        Trace.Entry entry3 = entries.get(2);
        assertThat(entry3.getDepth()).isEqualTo(0);
        assertThat(entry3.getMessage()).isEqualTo("auxiliary thread");
        Trace.Entry entry4 = entries.get(3);
        assertThat(entry4.getDepth()).isEqualTo(1);
        assertThat(entry4.getMessage()).isEqualTo("trace marker / CreateTraceEntry");
        Trace.Entry entry5 = entries.get(4);
        assertThat(entry5.getDepth()).isEqualTo(0);
        assertThat(entry5.getMessage()).isEqualTo("auxiliary thread");
        Trace.Entry entry6 = entries.get(5);
        assertThat(entry6.getDepth()).isEqualTo(1);
        assertThat(entry6.getMessage()).isEqualTo("trace marker / CreateTraceEntry");
    }

    public static class DoSomeCallableWork implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            ExecutorService executor = Executors.newCachedThreadPool();
            Future<Void> future1 = executor.submit(new Callable<Void>() {
                @Override
                public Void call() {
                    new CreateTraceEntry().transactionMarker();
                    return null;
                }
            });
            Future<Void> future2 = executor.submit(new Callable<Void>() {
                @Override
                public Void call() {
                    new CreateTraceEntry().transactionMarker();
                    return null;
                }
            });
            Future<Void> future3 = executor.submit(new Callable<Void>() {
                @Override
                public Void call() {
                    new CreateTraceEntry().transactionMarker();
                    return null;
                }
            });
            future1.get();
            future2.get();
            future3.get();
        }
    }

    public static class DoSomeRunnableAndCallableWork implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            ExecutorService executor = Executors.newCachedThreadPool();
            Future<Void> future1 = executor.submit((Callable<Void>) new RunnableAndCallableWork());
            Future<Void> future2 = executor.submit((Callable<Void>) new RunnableAndCallableWork());
            Future<Void> future3 = executor.submit((Callable<Void>) new RunnableAndCallableWork());
            future1.get();
            future2.get();
            future3.get();
        }
    }

    public static class DoSomeSimpleRunnableWork implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            ExecutorService executor = Executors.newCachedThreadPool();
            Future<?> future1 = executor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                    }
                }
            });
            Future<?> future2 = executor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                    }
                }
            });
            Future<?> future3 = executor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                    }
                }
            });
            future1.get();
            future2.get();
            future3.get();
        }
    }

    public static class CallFutureGetOnAlreadyCompletedFuture
            implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            ExecutorService executor = Executors.newCachedThreadPool();
            Future<Void> future = executor.submit(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    return null;
                }
            });
            while (!future.isDone()) {
                Thread.sleep(1);
            }
            future.get();
        }
    }

    private static class RunnableAndCallableWork implements Runnable, Callable<Void> {

        private final AtomicBoolean complete = new AtomicBoolean();

        @Override
        public Void call() {
            new CreateTraceEntry().transactionMarker();
            return null;
        }

        @Override
        public void run() {
            new CreateTraceEntry().transactionMarker();
            complete.set(true);
        }
    }

    private static class CreateTraceEntry implements TransactionMarker {

        @Override
        public void transactionMarker() {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
            }
        }
    }
}
