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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;

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

public class ExecutorWithProxiesIT {

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
        Trace trace = container.execute(DoExecuteRunnableWithProxy.class);

        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.hasAuxThreadRootTimer()).isTrue();
        assertThat(header.getAsyncTimerCount()).isZero();
        assertThat(header.getAuxThreadRootTimer().getName()).isEqualTo("auxiliary thread");
        assertThat(header.getAuxThreadRootTimer().getCount()).isEqualTo(1);
        // should be 100ms, but margin of error, esp. in travis builds is high
        assertThat(header.getAuxThreadRootTimer().getTotalNanos())
                .isGreaterThanOrEqualTo(MILLISECONDS.toNanos(50));
        assertThat(header.getAuxThreadRootTimer().getChildTimerCount()).isEqualTo(1);
        assertThat(header.getAuxThreadRootTimer().getChildTimer(0).getName())
                .isEqualTo("mock trace entry marker");
        List<Trace.Entry> entries = trace.getEntryList();

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).getDepth()).isEqualTo(0);
        assertThat(entries.get(0).getMessage()).isEqualTo("auxiliary thread");

        assertThat(entries.get(1).getDepth()).isEqualTo(1);
        assertThat(entries.get(1).getMessage()).isEqualTo("trace entry marker / CreateTraceEntry");
    }

    private static ExecutorService createExecutorService() throws Exception {
        ThreadPoolExecutor executor = newFixedThreadPool(1);
        executor.prestartAllCoreThreads();
        return executor;
    }

    private static ThreadPoolExecutor newFixedThreadPool(int nThreads) {
        return new ThreadPoolExecutor(nThreads, nThreads, 0L, MILLISECONDS,
                new LinkedBlockingQueue<Runnable>());
    }

    public static class DoExecuteRunnableWithProxy implements AppUnderTest, TransactionMarker {

        private ExecutorService executor;

        @Override
        public void executeApp() throws Exception {
            executor = createExecutorService();
            transactionMarker();
            executor.shutdown();
            executor.awaitTermination(10, SECONDS);
        }

        @Override
        public void transactionMarker() throws Exception {
            final CountDownLatch latch = new CountDownLatch(1);
            Runnable wrap = wrap(new Runnable() {
                @Override
                public void run() {
                    new CreateTraceEntry().traceEntryMarker();
                    latch.countDown();
                }
            });
            executor.execute(wrap);
            latch.await();
        }

        private static Runnable wrap(Runnable runnable) {
            return (Runnable) Proxy.newProxyInstance(
                    DoExecuteRunnableWithProxy.class.getClassLoader(), new Class[] {Runnable.class},
                    new DelegatingInvocationHandler(runnable));
        }
    }

    private static class DelegatingInvocationHandler implements InvocationHandler {

        private final Runnable delegate;

        private DelegatingInvocationHandler(Runnable delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return method.invoke(delegate, args);
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
