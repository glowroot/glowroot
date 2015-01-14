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

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.google.common.base.Stopwatch;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.TraceMarker;
import org.glowroot.container.config.ProfilingConfig;
import org.glowroot.container.trace.ProfileNode;
import org.glowroot.container.trace.Trace;
import org.glowroot.container.trace.TraceEntry;

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
    public void shouldReadActiveTraceStuckOnRootMetric() throws Exception {
        shouldReadActiveTrace(ShouldGenerateActiveTraceStuckOnRootMetric.class, false);
    }

    @Test
    public void shouldReadActiveTraceStuckOnNonRootMetric() throws Exception {
        shouldReadActiveTrace(ShouldGenerateActiveTraceStuckOnNonRootMetric.class, true);
    }

    private Trace shouldReadActiveTrace(final Class<? extends AppUnderTest> appUnderTest,
            boolean stuckOnNonRoot) throws Exception {
        // given
        ProfilingConfig profilingConfig = container.getConfigService().getProfilingConfig();
        profilingConfig.setIntervalMillis(10);
        container.getConfigService().updateProfilingConfig(profilingConfig);
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
        Trace trace = null;
        ProfileNode profile = null;
        while (stopwatch.elapsed(SECONDS) < 5) {
            trace = container.getTraceService().getActiveTrace(0, MILLISECONDS);
            if (trace != null) {
                profile = container.getTraceService().getProfile(trace.getId());
                if (profile != null) {
                    break;
                }
            }
            Thread.sleep(10);
        }
        // sleep once more to make sure trace gets into proper nested entry
        Thread.sleep(20);
        trace = container.getTraceService().getActiveTrace(0, MILLISECONDS);
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(trace).isNotNull();
        assertThat(trace.isActive()).isTrue();
        assertThat(trace.isPartial()).isFalse();
        assertThat(trace.getRootMetric().isMaxActive()).isTrue();
        if (stuckOnNonRoot) {
            assertThat(trace.getRootMetric().getNestedMetrics().get(0).isMaxActive()).isTrue();
            assertThat(entries).hasSize(3);
        } else {
            assertThat(entries).hasSize(1);
        }
        assertThat(profile).isNotNull();
        // interrupt trace
        container.interruptAppUnderTest();
        future.get();
        // should now be reported as complete (not partial)
        trace = container.getTraceService().getLastTrace();
        assertThat(trace.isActive()).isFalse();
        assertThat(trace.isPartial()).isFalse();
        // cleanup
        executorService.shutdown();
        return trace;
    }

    public static class ShouldGenerateActiveTraceStuckOnRootMetric implements AppUnderTest,
            TraceMarker {
        @Override
        public void executeApp() {
            traceMarker();
        }
        @Override
        public void traceMarker() {
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    public static class ShouldGenerateActiveTraceStuckOnNonRootMetric implements AppUnderTest,
            TraceMarker {
        @Override
        public void executeApp() throws InterruptedException {
            traceMarker();
        }
        @Override
        public void traceMarker() throws InterruptedException {
            new Pause().pauseOneMillisecond();
            try {
                new Pause().pauseMaxMilliseconds();
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
