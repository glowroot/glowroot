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

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;

import com.google.common.collect.ImmutableList;
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

public class LotsOfNestedAuxThreadContextsIT {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        // tests only work with javaagent container because they need to weave bootstrap classes
        // that implement Executor and ExecutorService
        //
        // restrict stack size to test for StackOverflowError
        // restrict heap size to test for OOM when lots of nested auxiliary thread contexts
        container =
                JavaagentContainer.createWithExtraJvmArgs(ImmutableList.of("-Xss256k", "-Xmx32m"));
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
    public void shouldCaptureSubmitCallable() throws Exception {
        // when
        Trace trace = container.execute(DoSubmitCallable.class);

        // then
        List<Trace.Timer> auxThreadRootTimers = trace.getHeader().getAuxThreadRootTimerList();
        assertThat(auxThreadRootTimers).hasSize(1);
        Trace.Timer auxThreadRootTimer = auxThreadRootTimers.get(0);
        assertThat(auxThreadRootTimer.getCount()).isEqualTo(100000);
        assertThat(auxThreadRootTimer.getActive()).isFalse();
        assertThat(auxThreadRootTimer.getChildTimerCount()).isEqualTo(1);
        assertThat(auxThreadRootTimer.getChildTimer(0).getName())
                .isEqualTo("mock trace entry marker");

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("auxiliary thread");

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(1);
        assertThat(entry.getMessage()).isEqualTo("trace entry marker / CreateTraceEntry");

        assertThat(i.hasNext()).isFalse();
    }

    public static class DoSubmitCallable implements AppUnderTest, TransactionMarker {

        private final ThreadPoolExecutor executor = new ThreadPoolExecutor(100, 100, 0,
                MILLISECONDS, new LinkedBlockingQueue<Runnable>());

        private final CountDownLatch latch = new CountDownLatch(1);

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            passOnToAnotherThread(0);
            latch.await();
            executor.shutdown();
            executor.awaitTermination(10, SECONDS);
        }

        private void passOnToAnotherThread(final int depth) throws Exception {
            if (depth == 100000) {
                new CreateTraceEntry().traceEntryMarker();
                latch.countDown();
                return;
            }
            while (executor.getQueue().size() > 1000) {
                // keep executor backlog from getting too full and adding memory pressure
                // (since restricting heap size to test for leaking aux thread contexts)
                Thread.sleep(1);
            }
            executor.submit(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    passOnToAnotherThread(depth + 1);
                    return null;
                }
            });
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
