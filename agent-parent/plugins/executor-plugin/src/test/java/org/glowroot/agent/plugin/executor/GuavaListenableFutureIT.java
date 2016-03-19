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

import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TraceEntryMarker;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class GuavaListenableFutureIT {

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
    public void shouldCaptureListenerAddedBeforeComplete() throws Exception {
        // given
        // when
        Trace trace = container.execute(AddListenerBeforeComplete.class);
        // then
        assertThat(trace.getHeader().getEntryCount()).isEqualTo(3);
        assertThat(trace.getEntry(0).getDepth()).isEqualTo(0);
        assertThat(trace.getEntry(0).getMessage()).isEqualTo("auxiliary thread");
        assertThat(trace.getEntry(1).getDepth()).isEqualTo(1);
        assertThat(trace.getEntry(1).getMessage()).isEqualTo("auxiliary thread");
        assertThat(trace.getEntry(2).getDepth()).isEqualTo(2);
        assertThat(trace.getEntry(2).getMessage())
                .isEqualTo("trace entry marker / CreateTraceEntry");
    }

    @Test
    public void shouldCaptureListenerAddedAfterComplete() throws Exception {
        // given
        // when
        Trace trace = container.execute(AddListenerAfterComplete.class);
        // then
        assertThat(trace.getHeader().getEntryCount()).isEqualTo(2);
        assertThat(trace.getEntry(0).getDepth()).isEqualTo(0);
        assertThat(trace.getEntry(0).getMessage()).isEqualTo("auxiliary thread");
        assertThat(trace.getEntry(1).getDepth()).isEqualTo(1);
        assertThat(trace.getEntry(1).getMessage())
                .isEqualTo("trace entry marker / CreateTraceEntry");
    }

    @Test
    public void shouldCaptureSameExecutorListenerAddedBeforeComplete() throws Exception {
        // given
        // when
        Trace trace = container.execute(AddSameExecutorListenerBeforeComplete.class);
        // then
        assertThat(trace.getHeader().getEntryCount()).isEqualTo(2);
        assertThat(trace.getEntry(0).getDepth()).isEqualTo(0);
        assertThat(trace.getEntry(0).getMessage()).isEqualTo("auxiliary thread");
        assertThat(trace.getEntry(1).getDepth()).isEqualTo(1);
        assertThat(trace.getEntry(1).getMessage())
                .isEqualTo("trace entry marker / CreateTraceEntry");
    }

    @Test
    public void shouldCaptureSameExecutorListenerAddedAfterComplete() throws Exception {
        // given
        // when
        Trace trace = container.execute(AddSameExecutorListenerAfterComplete.class);
        // then
        assertThat(trace.getHeader().getEntryCount()).isEqualTo(1);
        assertThat(trace.getEntry(0).getMessage())
                .isEqualTo("trace entry marker / CreateTraceEntry");
    }

    public static class AddListenerBeforeComplete implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            ListeningExecutorService executor =
                    MoreExecutors.listeningDecorator(Executors.newCachedThreadPool());
            ListenableFuture<Void> future1 = executor.submit(new Callable<Void>() {
                @Override
                public Void call() throws InterruptedException {
                    Thread.sleep(100);
                    return null;
                }
            });
            future1.addListener(new Runnable() {
                @Override
                public void run() {
                    new CreateTraceEntry().traceEntryMarker();
                }
            }, executor);
            Thread.sleep(200);
            executor.shutdown();
        }
    }

    public static class AddListenerAfterComplete implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            ListeningExecutorService executor =
                    MoreExecutors.listeningDecorator(Executors.newCachedThreadPool());
            ListenableFuture<Void> future1 = executor.submit(new Callable<Void>() {
                @Override
                public Void call() {
                    return null;
                }
            });
            Thread.sleep(100);
            future1.addListener(new Runnable() {
                @Override
                public void run() {
                    new CreateTraceEntry().traceEntryMarker();
                }
            }, executor);
            Thread.sleep(100);
            executor.shutdown();
        }
    }

    public static class AddSameExecutorListenerBeforeComplete
            implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            ListeningExecutorService executor =
                    MoreExecutors.listeningDecorator(Executors.newCachedThreadPool());
            ListenableFuture<Void> future1 = executor.submit(new Callable<Void>() {
                @Override
                public Void call() throws InterruptedException {
                    Thread.sleep(100);
                    return null;
                }
            });
            future1.addListener(new Runnable() {
                @Override
                public void run() {
                    new CreateTraceEntry().traceEntryMarker();
                }
            }, MoreExecutors.directExecutor());
            Thread.sleep(200);
            executor.shutdown();
        }
    }

    public static class AddSameExecutorListenerAfterComplete
            implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            ListeningExecutorService executor =
                    MoreExecutors.listeningDecorator(Executors.newCachedThreadPool());
            ListenableFuture<Void> future1 = executor.submit(new Callable<Void>() {
                @Override
                public Void call() {
                    return null;
                }
            });
            Thread.sleep(100);
            future1.addListener(new Runnable() {
                @Override
                public void run() {
                    new CreateTraceEntry().traceEntryMarker();
                }
            }, MoreExecutors.directExecutor());
            Thread.sleep(100);
            executor.shutdown();
        }
    }

    private static class CreateTraceEntry implements TraceEntryMarker {
        @Override
        public void traceEntryMarker() {}
    }
}
