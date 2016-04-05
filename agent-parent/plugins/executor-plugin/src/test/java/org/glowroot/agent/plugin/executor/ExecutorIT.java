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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.collect.Lists;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TraceEntryMarker;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.TransactionConfig;
import org.glowroot.wire.api.model.Proto.OptionalInt32;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
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
    public void shouldCaptureExecute() throws Exception {
        // given
        container.getConfigService().updateTransactionConfig(
                TransactionConfig.newBuilder()
                        .setSlowThresholdMillis(OptionalInt32.newBuilder().setValue(0))
                        .setProfilingIntervalMillis(OptionalInt32.newBuilder().setValue(20).build())
                        .build());
        // when
        Trace trace = container.execute(DoExecuteRunnable.class);
        // then
        checkTrace(trace, false);
    }

    @Test
    public void shouldCaptureSubmitCallable() throws Exception {
        // given
        container.getConfigService().updateTransactionConfig(
                TransactionConfig.newBuilder()
                        .setSlowThresholdMillis(OptionalInt32.newBuilder().setValue(0))
                        .setProfilingIntervalMillis(OptionalInt32.newBuilder().setValue(20).build())
                        .build());
        // when
        Trace trace = container.execute(DoSubmitCallable.class);
        // then
        checkTrace(trace, true);
    }

    @Test
    public void shouldCaptureSubmitRunnableAndCallable() throws Exception {
        // given
        container.getConfigService().updateTransactionConfig(
                TransactionConfig.newBuilder()
                        .setSlowThresholdMillis(OptionalInt32.newBuilder().setValue(0))
                        .setProfilingIntervalMillis(OptionalInt32.newBuilder().setValue(20).build())
                        .build());
        // when
        Trace trace = container.execute(DoSubmitRunnableAndCallable.class);
        // then
        checkTrace(trace, true);
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
        Trace trace = container.execute(DoSimpleSubmitRunnableWork.class);
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

    @Test
    public void shouldCaptureNestedFutureGet() throws Exception {
        // given
        // when
        Trace trace = container.execute(CallFutureGetOnNestedFuture.class);
        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.getEntryCount()).isEqualTo(4);
        Trace.Entry entry1 = trace.getEntry(0);
        assertThat(entry1.getDepth()).isEqualTo(0);
        assertThat(entry1.getMessage()).isEqualTo("auxiliary thread");
        Trace.Entry entry2 = trace.getEntry(1);
        assertThat(entry2.getDepth()).isEqualTo(1);
        assertThat(entry2.getMessage()).isEqualTo("trace entry marker / CreateTraceEntry");
        Trace.Entry entry3 = trace.getEntry(2);
        assertThat(entry3.getDepth()).isEqualTo(1);
        assertThat(entry3.getMessage()).isEqualTo("auxiliary thread");
        Trace.Entry entry4 = trace.getEntry(3);
        assertThat(entry4.getDepth()).isEqualTo(2);
        assertThat(entry4.getMessage()).isEqualTo("trace entry marker / CreateTraceEntry");
    }

    @Test
    public void shouldCaptureInvokeAll() throws Exception {
        // given
        container.getConfigService().updateTransactionConfig(
                TransactionConfig.newBuilder()
                        .setSlowThresholdMillis(OptionalInt32.newBuilder().setValue(0))
                        .setProfilingIntervalMillis(OptionalInt32.newBuilder().setValue(20).build())
                        .build());
        // when
        Trace trace = container.execute(DoInvokeAll.class);
        // then
        checkTrace(trace, true);
    }

    @Test
    public void shouldCaptureInvokeAllWithTimeout() throws Exception {
        // given
        container.getConfigService().updateTransactionConfig(
                TransactionConfig.newBuilder()
                        .setSlowThresholdMillis(OptionalInt32.newBuilder().setValue(0))
                        .setProfilingIntervalMillis(OptionalInt32.newBuilder().setValue(20).build())
                        .build());
        // when
        Trace trace = container.execute(DoInvokeAllWithTimeout.class);
        // then
        checkTrace(trace, true);
    }

    @Test
    public void shouldCaptureInvokeAny() throws Exception {
        // given
        container.getConfigService().updateTransactionConfig(
                TransactionConfig.newBuilder()
                        .setSlowThresholdMillis(OptionalInt32.newBuilder().setValue(0))
                        .setProfilingIntervalMillis(OptionalInt32.newBuilder().setValue(20).build())
                        .build());
        // when
        Trace trace = container.execute(DoInvokeAny.class);
        // then
        checkTrace(trace, false);
    }

    @Test
    public void shouldCaptureInvokeAnyWithTimeout() throws Exception {
        // given
        container.getConfigService().updateTransactionConfig(
                TransactionConfig.newBuilder()
                        .setSlowThresholdMillis(OptionalInt32.newBuilder().setValue(0))
                        .setProfilingIntervalMillis(OptionalInt32.newBuilder().setValue(20).build())
                        .build());
        // when
        Trace trace = container.execute(DoInvokeAnyWithTimeout.class);
        // then
        checkTrace(trace, false);
    }

    private static void checkTrace(Trace trace, boolean withFuture) {
        Trace.Header header = trace.getHeader();
        if (withFuture) {
            assertThat(header.getMainThreadRootTimer().getChildTimerCount()).isEqualTo(1);
            assertThat(header.getMainThreadRootTimer().getChildTimer(0).getName())
                    .isEqualTo("wait on future");
            assertThat(header.getMainThreadRootTimer().getChildTimer(0).getCount())
                    .isGreaterThanOrEqualTo(1);
            assertThat(header.getMainThreadRootTimer().getChildTimer(0).getCount())
                    .isLessThanOrEqualTo(3);
        }
        assertThat(header.getAuxThreadRootTimerCount()).isEqualTo(1);
        assertThat(header.getAsyncRootTimerCount()).isZero();
        assertThat(header.getAuxThreadRootTimer(0).getName()).isEqualTo("auxiliary thread");
        assertThat(header.getAuxThreadRootTimer(0).getCount()).isEqualTo(3);
        assertThat(header.getAuxThreadRootTimer(0).getTotalNanos())
                .isGreaterThanOrEqualTo(MILLISECONDS.toNanos(250));
        assertThat(header.getAuxThreadRootTimer(0).getChildTimerCount()).isEqualTo(1);
        assertThat(header.getAuxThreadRootTimer(0).getChildTimer(0).getName())
                .isEqualTo("mock trace entry marker");
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
        assertThat(entry2.getMessage()).isEqualTo("trace entry marker / CreateTraceEntry");
        Trace.Entry entry3 = entries.get(2);
        assertThat(entry3.getDepth()).isEqualTo(0);
        assertThat(entry3.getMessage()).isEqualTo("auxiliary thread");
        Trace.Entry entry4 = entries.get(3);
        assertThat(entry4.getDepth()).isEqualTo(1);
        assertThat(entry4.getMessage()).isEqualTo("trace entry marker / CreateTraceEntry");
        Trace.Entry entry5 = entries.get(4);
        assertThat(entry5.getDepth()).isEqualTo(0);
        assertThat(entry5.getMessage()).isEqualTo("auxiliary thread");
        Trace.Entry entry6 = entries.get(5);
        assertThat(entry6.getDepth()).isEqualTo(1);
        assertThat(entry6.getMessage()).isEqualTo("trace entry marker / CreateTraceEntry");
    }

    private static ExecutorService createExecutorService() {
        return Executors.newCachedThreadPool();
    }

    public static class DoExecuteRunnable implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            ExecutorService executor = createExecutorService();
            final CountDownLatch latch = new CountDownLatch(3);
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    new CreateTraceEntry().traceEntryMarker();
                    latch.countDown();
                }
            });
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    new CreateTraceEntry().traceEntryMarker();
                    latch.countDown();
                }
            });
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    new CreateTraceEntry().traceEntryMarker();
                    latch.countDown();
                }
            });
            latch.await();
            executor.shutdown();
            executor.awaitTermination(10, SECONDS);
        }
    }

    public static class DoSubmitCallable implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            ExecutorService executor = createExecutorService();
            Future<Void> future1 = executor.submit(new Callable<Void>() {
                @Override
                public Void call() {
                    new CreateTraceEntry().traceEntryMarker();
                    return null;
                }
            });
            Future<Void> future2 = executor.submit(new Callable<Void>() {
                @Override
                public Void call() {
                    new CreateTraceEntry().traceEntryMarker();
                    return null;
                }
            });
            Future<Void> future3 = executor.submit(new Callable<Void>() {
                @Override
                public Void call() {
                    new CreateTraceEntry().traceEntryMarker();
                    return null;
                }
            });
            future1.get();
            future2.get();
            future3.get();
        }
    }

    public static class DoSubmitRunnableAndCallable implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            ExecutorService executor = createExecutorService();
            Future<Void> future1 = executor.submit((Callable<Void>) new RunnableAndCallableWork());
            Future<Void> future2 = executor.submit((Callable<Void>) new RunnableAndCallableWork());
            Future<Void> future3 = executor.submit((Callable<Void>) new RunnableAndCallableWork());
            future1.get();
            future2.get();
            future3.get();
        }
    }

    public static class DoSimpleSubmitRunnableWork implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            ExecutorService executor = createExecutorService();
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
            ExecutorService executor = createExecutorService();
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

    public static class CallFutureGetOnNestedFuture implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            final ExecutorService executor = createExecutorService();
            Future<Void> future = executor.submit(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    new CreateTraceEntry().traceEntryMarker();
                    Future<Void> future = executor.submit(new Callable<Void>() {
                        @Override
                        public Void call() {
                            new CreateTraceEntry().traceEntryMarker();
                            return null;
                        }
                    });
                    future.get();
                    return null;
                }
            });
            future.get();
        }
    }

    public static class DoInvokeAll implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            ExecutorService executor = createExecutorService();
            List<Callable<Void>> callables = Lists.newArrayList();
            callables.add(new Callable<Void>() {
                @Override
                public Void call() {
                    new CreateTraceEntry().traceEntryMarker();
                    return null;
                }
            });
            callables.add(new Callable<Void>() {
                @Override
                public Void call() {
                    new CreateTraceEntry().traceEntryMarker();
                    return null;
                }
            });
            callables.add(new Callable<Void>() {
                @Override
                public Void call() {
                    new CreateTraceEntry().traceEntryMarker();
                    return null;
                }
            });
            for (Future<Void> future : executor.invokeAll(callables)) {
                future.get();
            }
        }
    }

    public static class DoInvokeAllWithTimeout implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            ExecutorService executor = createExecutorService();
            List<Callable<Void>> callables = Lists.newArrayList();
            callables.add(new Callable<Void>() {
                @Override
                public Void call() {
                    new CreateTraceEntry().traceEntryMarker();
                    return null;
                }
            });
            callables.add(new Callable<Void>() {
                @Override
                public Void call() {
                    new CreateTraceEntry().traceEntryMarker();
                    return null;
                }
            });
            callables.add(new Callable<Void>() {
                @Override
                public Void call() {
                    new CreateTraceEntry().traceEntryMarker();
                    return null;
                }
            });
            for (Future<Void> future : executor.invokeAll(callables, 10, SECONDS)) {
                future.get();
            }
        }
    }

    public static class DoInvokeAny implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            ExecutorService executor = createExecutorService();
            List<Callable<Void>> callables = Lists.newArrayList();
            callables.add(new Callable<Void>() {
                @Override
                public Void call() {
                    new CreateTraceEntry().traceEntryMarker();
                    return null;
                }
            });
            callables.add(new Callable<Void>() {
                @Override
                public Void call() {
                    new CreateTraceEntry().traceEntryMarker();
                    return null;
                }
            });
            callables.add(new Callable<Void>() {
                @Override
                public Void call() {
                    new CreateTraceEntry().traceEntryMarker();
                    return null;
                }
            });
            executor.invokeAny(callables);
        }
    }

    public static class DoInvokeAnyWithTimeout implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            ExecutorService executor = createExecutorService();
            List<Callable<Void>> callables = Lists.newArrayList();
            callables.add(new Callable<Void>() {
                @Override
                public Void call() {
                    new CreateTraceEntry().traceEntryMarker();
                    return null;
                }
            });
            callables.add(new Callable<Void>() {
                @Override
                public Void call() {
                    new CreateTraceEntry().traceEntryMarker();
                    return null;
                }
            });
            callables.add(new Callable<Void>() {
                @Override
                public Void call() {
                    new CreateTraceEntry().traceEntryMarker();
                    return null;
                }
            });
            executor.invokeAll(callables, 10, SECONDS);
        }
    }

    private static class RunnableAndCallableWork implements Runnable, Callable<Void> {

        private final AtomicBoolean complete = new AtomicBoolean();

        @Override
        public Void call() {
            new CreateTraceEntry().traceEntryMarker();
            return null;
        }

        @Override
        public void run() {
            new CreateTraceEntry().traceEntryMarker();
            complete.set(true);
        }
    }

    private static class CreateTraceEntry implements TraceEntryMarker {

        @Override
        public void traceEntryMarker() {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
            }
        }
    }
}
