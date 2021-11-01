/*
 * Copyright 2016-2018 the original author or authors.
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.agent.it.harness.impl.JavaagentContainer;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

public class LotsOfNonNestedAuxThreadContextsIT {

    private static Container container;

    @BeforeAll
    public static void setUp() throws Exception {
        // tests only work with javaagent container because they need to weave bootstrap classes
        // that implement Executor and ExecutorService
        //
        // restrict heap size to test for OOM when lots of auxiliary thread contexts
        container = JavaagentContainer.createWithExtraJvmArgs(ImmutableList.of("-Xmx32m"));
    }

    @AfterAll
    public static void tearDown() throws Exception {
        container.close();
    }

    @AfterEach
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldCaptureSubmitCallable() throws Exception {
        // when
        Trace trace = container.execute(DoSubmitCallable.class);

        // then
        assertThat(trace.getEntryList()).isEmpty();
        assertThat(trace.getHeader().hasAuxThreadRootTimer()).isTrue();
        Trace.Timer auxThreadRootTimer = trace.getHeader().getAuxThreadRootTimer();
        assertThat(auxThreadRootTimer.getCount()).isEqualTo(100000);
        assertThat(auxThreadRootTimer.getActive()).isFalse();
        assertThat(auxThreadRootTimer.getChildTimerCount()).isZero();
    }

    public static class DoSubmitCallable implements AppUnderTest, TransactionMarker {

        private final ThreadPoolExecutor executor = new ThreadPoolExecutor(100, 100, 0,
                MILLISECONDS, new LinkedBlockingQueue<Runnable>());

        private final CountDownLatch latch = new CountDownLatch(100000);

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            for (int i = 0; i < 100000; i++) {
                while (executor.getQueue().size() > 1000) {
                    // keep executor backlog from getting too full and adding memory pressure
                    // (since restricting heap size to test for leaking aux thread contexts)
                    MILLISECONDS.sleep(1);
                }
                executor.submit(new Callable<Void>() {
                    @Override
                    public Void call() throws Exception {
                        latch.countDown();
                        return null;
                    }
                });
            }
            latch.await();
            executor.shutdown();
            executor.awaitTermination(10, SECONDS);
        }
    }
}
