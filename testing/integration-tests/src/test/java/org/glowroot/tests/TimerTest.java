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
import org.glowroot.container.TransactionMarker;
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
        Trace.Header header = container.getTraceService().getLastTrace();
        assertThat(header.rootTimer().childTimers()).isEmpty();
        assertThat(header.rootTimer().name()).isEqualTo("mock trace marker");
    }

    @Test
    public void shouldReadTimersWithRootAndSelfNested() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithRootAndSelfNestedTimer.class);
        // then
        Trace.Header header = container.getTraceService().getLastTrace();
        assertThat(header.rootTimer().childTimers()).isEmpty();
        assertThat(header.rootTimer().name()).isEqualTo("mock trace marker");
        assertThat(header.rootTimer().count()).isEqualTo(1);
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
        Trace.Header header = container.getTraceService().getActiveTrace(5, SECONDS);
        assertThat(header).isNotNull();
        assertThat(header.rootTimer().childTimers()).isEmpty();
        assertThat(header.rootTimer().name()).isEqualTo("mock trace marker");
        assertThat(header.rootTimer().count()).isEqualTo(1);
        assertThat(header.rootTimer().active().or(false)).isTrue();
        // cleanup
        // interrupt header
        container.interruptAppUnderTest();
        future.get();
        executorService.shutdown();
    }

    public static class ShouldGenerateTraceWithTimers implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws InterruptedException {
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws InterruptedException {
            Thread.sleep(1);
        }
    }

    public static class ShouldGenerateTraceWithRootAndSelfNestedTimer
            implements AppUnderTest, TransactionMarker {
        private int nestingLevel = 0;
        @Override
        public void executeApp() throws InterruptedException {
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws InterruptedException {
            Thread.sleep(1);
            if (nestingLevel < 10) {
                nestingLevel++;
                transactionMarker();
            }
        }
    }

    public static class ShouldGenerateActiveTraceWithTimers
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
}
