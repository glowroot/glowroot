/*
 * Copyright 2011-2014 the original author or authors.
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

import checkers.nullness.quals.Nullable;
import com.google.common.base.Stopwatch;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.TraceMarker;
import org.glowroot.container.config.GeneralConfig;
import org.glowroot.container.trace.Trace;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class StuckTraceTest {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.getSharedContainer();
        // capture one trace to warm up the system since this test involves some timing
        container.executeAppUnderTest(WarmupTrace.class);
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
    public void shouldReadActiveStuckTrace() throws Exception {
        // given
        GeneralConfig generalConfig = container.getConfigService().getGeneralConfig();
        generalConfig.setStuckThresholdSeconds(0);
        container.getConfigService().updateGeneralConfig(generalConfig);
        // when
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future<Void> future = executorService.submit(new Callable<Void>() {
            @Override
            @Nullable
            public Void call() throws Exception {
                container.executeAppUnderTest(ShouldGenerateStuckTrace.class);
                return null;
            }
        });
        // then
        // wait for trace to be marked stuck
        Stopwatch stopwatch = Stopwatch.createStarted();
        Trace trace = null;
        while (stopwatch.elapsed(SECONDS) < 5) {
            trace = container.getTraceService().getActiveTraceSummary(0, MILLISECONDS);
            if (trace != null && trace.isStuck()) {
                break;
            }
            Thread.sleep(10);
        }
        assertThat(trace).isNotNull();
        assertThat(trace.isActive()).isTrue();
        assertThat(trace.isStuck()).isTrue();
        // interrupt trace
        container.interruptAppUnderTest();
        future.get();
        // should now be reported as unstuck
        trace = container.getTraceService().getLastTraceSummary();
        assertThat(trace.isActive()).isFalse();
        assertThat(trace.isStuck()).isFalse();
        // cleanup
        executorService.shutdown();
    }

    public static class ShouldGenerateStuckTrace implements AppUnderTest, TraceMarker {
        @Override
        public void executeApp() throws InterruptedException {
            traceMarker();
        }
        @Override
        public void traceMarker() throws InterruptedException {
            while (true) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    public static class WarmupTrace implements AppUnderTest, TraceMarker {
        @Override
        public void executeApp() throws InterruptedException {
            traceMarker();
        }
        @Override
        public void traceMarker() throws InterruptedException {
            Thread.sleep(1);
        }
    }
}
