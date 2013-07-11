/**
 * Copyright 2013 the original author or authors.
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
package io.informant.tests;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import io.informant.Containers;
import io.informant.container.AppUnderTest;
import io.informant.container.Container;
import io.informant.container.TraceMarker;
import io.informant.container.trace.JvmInfo.GarbageCollectorInfo;
import io.informant.container.trace.Trace;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.fest.assertions.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class JvmInfoTest {

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
    public void shouldTestCpuTime() throws Exception {
        // given
        container.getConfigService().setStoreThresholdMillis(0);
        // when
        container.executeAppUnderTest(ShouldUseCpu.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getJvmInfo().getThreadCpuTime())
                .isGreaterThan(MILLISECONDS.toNanos(10));
    }

    @Test
    public void shouldTestWaitTime() throws Exception {
        // given
        container.getConfigService().setStoreThresholdMillis(0);
        // when
        container.executeAppUnderTest(ShouldWait.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getJvmInfo().getThreadWaitedTime()).isGreaterThan(5);
    }

    @Test
    public void shouldTestBlockTime() throws Exception {
        // given
        container.getConfigService().setStoreThresholdMillis(0);
        // when
        container.executeAppUnderTest(ShouldBlock.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getJvmInfo().getThreadBlockedTime()).isGreaterThan(5);
    }

    @Test
    public void shouldTestGarbageCollection() throws Exception {
        // given
        container.getConfigService().setStoreThresholdMillis(0);
        // when
        container.executeAppUnderTest(ShouldGenerateGarbage.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        List<GarbageCollectorInfo> garbageCollectorInfos =
                trace.getJvmInfo().getGarbageCollectorInfos();
        long collectionCount = 0;
        long collectionTime = 0;
        for (GarbageCollectorInfo garbageCollectionInfo : garbageCollectorInfos) {
            collectionCount += garbageCollectionInfo.getCollectionCount();
            collectionTime += garbageCollectionInfo.getCollectionTime();
        }
        assertThat(collectionCount).isGreaterThanOrEqualTo(5);
        assertThat(collectionTime).isGreaterThanOrEqualTo(5);
    }

    public static class ShouldUseCpu implements AppUnderTest, TraceMarker {
        public void executeApp() throws Exception {
            traceMarker();
        }
        public void traceMarker() throws Exception {
            ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
            long start = threadBean.getCurrentThreadCpuTime();
            while (threadBean.getCurrentThreadCpuTime() - start < 10) {
                for (int i = 0; i < 1000; i++) {
                    Math.pow(i, i);
                }
            }
        }
    }

    public static class ShouldWait implements AppUnderTest, TraceMarker {
        public void executeApp() throws Exception {
            traceMarker();
        }
        public void traceMarker() throws Exception {
            Object object = new Object();
            synchronized (object) {
                object.wait(10);
            }
        }
    }

    public static class ShouldBlock implements AppUnderTest, TraceMarker {
        public void executeApp() throws Exception {
            traceMarker();
        }
        public void traceMarker() throws Exception {
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

    public static class ShouldGenerateGarbage implements AppUnderTest, TraceMarker {
        public void executeApp() throws Exception {
            traceMarker();
        }
        public void traceMarker() throws Exception {
            long collectionCountStart = collectionCount();
            long collectionTimeStart = collectionTime();
            while (collectionCount() - collectionCountStart < 5
                    || collectionTime() - collectionTimeStart < 5) {
                for (int i = 0; i < 1000; i++) {
                    createGarbage();
                }
            }
        }
        private static long collectionCount() {
            List<GarbageCollectorMXBean> garbageCollectorBeans =
                    ManagementFactory.getGarbageCollectorMXBeans();
            long total = 0;
            for (GarbageCollectorMXBean garbageCollectorBean : garbageCollectorBeans) {
                total += garbageCollectorBean.getCollectionCount();
            }
            return total;
        }
        private static long collectionTime() {
            List<GarbageCollectorMXBean> garbageCollectorBeans =
                    ManagementFactory.getGarbageCollectorMXBeans();
            long total = 0;
            for (GarbageCollectorMXBean garbageCollectorBean : garbageCollectorBeans) {
                total += garbageCollectorBean.getCollectionTime();
            }
            return total;
        }
        private Object createGarbage() {
            return new char[10000];
        }
    }

    private static class Blocker implements Callable<Void> {
        private final Object lock;
        private final Object notify;
        public Blocker(Object lock, Object notify) {
            this.lock = lock;
            this.notify = notify;
        }
        public Void call() throws InterruptedException {
            synchronized (lock) {
                synchronized (notify) {
                    notify.notify();
                }
                Thread.sleep(10);
            }
            return null;
        }
    }
}
