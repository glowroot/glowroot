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

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.TraceMarker;
import org.glowroot.container.trace.Trace;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class TraceMetricTest {

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
    public void shouldReadTraceMetrics() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithTraceMetrics.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getRootTraceMetric().getNestedMetrics()).isEmpty();
        assertThat(trace.getRootTraceMetric().getName()).isEqualTo("mock trace marker");
    }

    @Test
    public void shouldReadTraceMetricsWithRootAndSelfNested() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithRootAndSelfNestedTraceMetric.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getRootTraceMetric().getNestedMetrics()).isEmpty();
        assertThat(trace.getRootTraceMetric().getName()).isEqualTo("mock trace marker");
        assertThat(trace.getRootTraceMetric().getCount()).isEqualTo(1);
    }

    @Test
    public void shouldReadActiveTraceMetrics() throws Exception {
        // given
        // when
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future<Void> future = executorService.submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                container.executeAppUnderTest(ShouldGenerateActiveTraceWithTraceMetrics.class);
                return null;
            }
        });
        // then
        Trace trace = container.getTraceService().getActiveTrace(5, SECONDS);
        assertThat(trace).isNotNull();
        assertThat(trace.getRootTraceMetric().getNestedMetrics()).isEmpty();
        assertThat(trace.getRootTraceMetric().getName()).isEqualTo("mock trace marker");
        assertThat(trace.getRootTraceMetric().getCount()).isEqualTo(1);
        assertThat(trace.getRootTraceMetric().isActive()).isTrue();
        assertThat(trace.getRootTraceMetric().isMinActive()).isTrue();
        assertThat(trace.getRootTraceMetric().isMaxActive()).isTrue();
        // cleanup
        future.get();
        executorService.shutdown();
    }

    public static class ShouldGenerateTraceWithTraceMetrics implements AppUnderTest, TraceMarker {
        @Override
        public void executeApp() throws InterruptedException {
            traceMarker();
        }
        @Override
        public void traceMarker() throws InterruptedException {
            Thread.sleep(1);
        }
    }

    public static class ShouldGenerateTraceWithRootAndSelfNestedTraceMetric implements
            AppUnderTest,
            TraceMarker {
        private int nestingLevel = 0;
        @Override
        public void executeApp() throws InterruptedException {
            traceMarker();
        }
        @Override
        public void traceMarker() throws InterruptedException {
            Thread.sleep(1);
            if (nestingLevel < 10) {
                nestingLevel++;
                traceMarker();
            }
        }
    }

    public static class ShouldGenerateActiveTraceWithTraceMetrics implements AppUnderTest,
            TraceMarker {
        @Override
        public void executeApp() throws InterruptedException {
            traceMarker();
        }
        @Override
        public void traceMarker() throws InterruptedException {
            // need to sleep long enough for active trace request to find this trace
            Thread.sleep(100);
        }
    }
}
