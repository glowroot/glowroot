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

public class TimerTest {

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
    public void shouldReadTimers() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithTimers.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getRootTimer().getChildNodes()).isEmpty();
        assertThat(trace.getRootTimer().getName()).isEqualTo("mock trace marker");
    }

    @Test
    public void shouldReadTimersWithRootAndSelfNested() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithRootAndSelfNestedTimer.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getRootTimer().getChildNodes()).isEmpty();
        assertThat(trace.getRootTimer().getName()).isEqualTo("mock trace marker");
        assertThat(trace.getRootTimer().getCount()).isEqualTo(1);
    }

    @Test
    public void shouldReadActiveTimers() throws Exception {
        // given
        // when
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future<Void> future = executorService.submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                container.executeAppUnderTest(ShouldGenerateActiveTraceWithTimers.class);
                return null;
            }
        });
        // then
        Trace trace = container.getTraceService().getActiveTrace(5, SECONDS);
        assertThat(trace).isNotNull();
        assertThat(trace.getRootTimer().getChildNodes()).isEmpty();
        assertThat(trace.getRootTimer().getName()).isEqualTo("mock trace marker");
        assertThat(trace.getRootTimer().getCount()).isEqualTo(1);
        assertThat(trace.getRootTimer().isActive()).isTrue();
        // cleanup
        // interrupt trace
        container.interruptAppUnderTest();
        future.get();
        executorService.shutdown();
    }

    public static class ShouldGenerateTraceWithTimers implements AppUnderTest, TraceMarker {
        @Override
        public void executeApp() throws InterruptedException {
            traceMarker();
        }
        @Override
        public void traceMarker() throws InterruptedException {
            Thread.sleep(1);
        }
    }

    public static class ShouldGenerateTraceWithRootAndSelfNestedTimer
            implements AppUnderTest, TraceMarker {
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

    public static class ShouldGenerateActiveTraceWithTimers implements AppUnderTest, TraceMarker {
        @Override
        public void executeApp() {
            traceMarker();
        }
        @Override
        public void traceMarker() {
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
            }
        }
    }
}
