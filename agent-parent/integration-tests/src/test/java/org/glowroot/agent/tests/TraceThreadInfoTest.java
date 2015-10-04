/*
 * Copyright 2013-2015 the original author or authors.
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

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.agent.it.harness.trace.Trace;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;

public class TraceThreadInfoTest {

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
    public void shouldTestCpuTime() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldUseCpu.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header.threadCpuNanos().get()).isGreaterThanOrEqualTo(MILLISECONDS.toNanos(10));
    }

    @Test
    public void shouldTestWaitTime() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldWait.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header.threadWaitedNanos().get()).isGreaterThanOrEqualTo(5);
    }

    @Test
    public void shouldTestBlockTime() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldBlock.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header.threadBlockedNanos().get()).isGreaterThanOrEqualTo(5);
    }

    public static class ShouldUseCpu implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws Exception {
            ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
            long start = threadBean.getCurrentThreadCpuTime();
            while (threadBean.getCurrentThreadCpuTime() - start < MILLISECONDS.toNanos(10)) {
                for (int i = 0; i < 1000; i++) {
                    Math.pow(i, i);
                }
            }
        }
    }

    public static class ShouldWait implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws Exception {
            Object object = new Object();
            synchronized (object) {
                object.wait(10);
            }
        }
    }

    public static class ShouldBlock implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws Exception {
            Object lock = new Object();
            Object notify = new Object();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Blocker blocker = new Blocker(lock, notify);
            synchronized (notify) {
                executor.submit(blocker);
                notify.wait();
            }
            // the time spent waiting on lock here is the thread blocked time
            synchronized (lock) {
            }
            executor.shutdownNow();
        }
    }

    private static class Blocker implements Callable<Void> {
        private final Object lock;
        private final Object notify;
        public Blocker(Object lock, Object notify) {
            this.lock = lock;
            this.notify = notify;
        }
        @Override
        public Void call() throws InterruptedException {
            synchronized (lock) {
                synchronized (notify) {
                    notify.notify();
                }
                // sleeping here while holding lock causes thread blocked time in transaction thread
                Thread.sleep(20);
            }
            return null;
        }
    }
}
