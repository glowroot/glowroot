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

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.google.common.base.Stopwatch;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.harness.AppUnderTest;
import org.glowroot.agent.harness.Container;
import org.glowroot.agent.harness.Containers;
import org.glowroot.agent.harness.TransactionMarker;
import org.glowroot.agent.harness.config.TransactionConfig;
import org.glowroot.agent.harness.trace.ProfileTree;
import org.glowroot.agent.harness.trace.Trace;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

public class ActiveTraceTest {

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
    public void shouldReadActiveTraceStuckOnRootTimer() throws Exception {
        shouldReadActiveTrace(ShouldGenerateActiveTraceStuckOnRootTimer.class, false);
    }

    @Test
    public void shouldReadActiveTraceStuckOnNonRootTimer() throws Exception {
        shouldReadActiveTrace(ShouldGenerateActiveTraceStuckOnNonRootTimer.class, true);
    }

    private Trace.Header shouldReadActiveTrace(final Class<? extends AppUnderTest> appUnderTest,
            boolean stuckOnNonRoot) throws Exception {
        // given
        TransactionConfig transactionConfig = container.getConfigService().getTransactionConfig();
        transactionConfig.setProfilingIntervalMillis(10);
        container.getConfigService().updateTransactionConfig(transactionConfig);
        // when
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future<Void> future = executorService.submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                try {
                    container.executeAppUnderTest(appUnderTest);
                } catch (Throwable t) {
                    t.printStackTrace();
                }
                return null;
            }
        });
        // then
        Stopwatch stopwatch = Stopwatch.createStarted();
        Trace.Header header = null;
        ProfileTree profileTree = null;
        while (stopwatch.elapsed(SECONDS) < 5) {
            header = container.getTraceService().getActiveHeader(0, MILLISECONDS);
            if (header != null && header.profileSampleCount() > 0) {
                profileTree = container.getTraceService().getProfile(header.id());
                if (profileTree != null) {
                    break;
                }
            }
            Thread.sleep(10);
        }
        if (stuckOnNonRoot) {
            // wait for trace to get into nested timer
            stopwatch = Stopwatch.createStarted();
            while (stopwatch.elapsed(SECONDS) < 5) {
                header = container.getTraceService().getActiveHeader(0, MILLISECONDS);
                if (!header.rootTimer().childTimers().isEmpty()) {
                    break;
                }
                Thread.sleep(10);
            }
        }
        Thread.sleep(20);
        header = container.getTraceService().getActiveHeader(0, MILLISECONDS);
        assertThat(header).isNotNull();
        assertThat(header.partial().or(false)).isTrue();
        if (stuckOnNonRoot) {
            assertThat(header.entryCount()).isEqualTo(2);
        } else {
            assertThat(header.entryCount()).isZero();
        }
        assertThat(profileTree).isNotNull();
        // interrupt trace
        container.interruptAppUnderTest();
        future.get();
        // should now be reported as complete (not partial)
        header = container.getTraceService().getLastHeader();
        assertThat(header.partial().or(false)).isFalse();
        // cleanup
        executorService.shutdown();
        return header;
    }

    public static class ShouldGenerateActiveTraceStuckOnRootTimer
            implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
            }
        }
    }

    public static class ShouldGenerateActiveTraceStuckOnNonRootTimer
            implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws InterruptedException {
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws InterruptedException {
            new Pause().pauseOneMillisecond();
            try {
                new Pause().pauseMaxMilliseconds();
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
