/*
 * Copyright 2011-2016 the original author or authors.
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

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AdvancedConfig;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

public class TimerIT {

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
    public void shouldReadTimers() throws Exception {
        // when
        Trace trace = container.execute(ShouldGenerateTraceWithTimers.class);

        // then
        Trace.Header header = trace.getHeader();
        Trace.Timer rootTimer = header.getMainThreadRootTimer();
        assertThat(rootTimer.getChildTimerList()).isEmpty();
        assertThat(rootTimer.getName()).isEqualTo("mock trace marker");
    }

    @Test
    public void shouldReadTimersWithRootAndSelfNested() throws Exception {
        // when
        Trace trace = container.execute(ShouldGenerateTraceWithRootAndSelfNestedTimer.class);

        // then
        Trace.Header header = trace.getHeader();
        Trace.Timer rootTimer = header.getMainThreadRootTimer();
        assertThat(rootTimer.getChildTimerList()).isEmpty();
        assertThat(rootTimer.getName()).isEqualTo("mock trace marker");
        assertThat(rootTimer.getCount()).isEqualTo(1);
    }

    @Test
    public void shouldReadActiveTimers() throws Exception {
        // given
        container.getConfigService().updateAdvancedConfig(
                AdvancedConfig.newBuilder()
                        .setImmediatePartialStoreThresholdSeconds(ProtoOptional.of(1))
                        .build());

        // when
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Void> future = executor.submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                container.execute(ShouldGenerateActiveTraceWithTimers.class);
                return null;
            }
        });

        // then
        Trace trace = container.getCollectedPartialTrace();
        Trace.Header header = trace.getHeader();
        Trace.Timer rootTimer = header.getMainThreadRootTimer();
        assertThat(rootTimer.getChildTimerList()).isEmpty();
        assertThat(rootTimer.getName()).isEqualTo("mock trace marker");
        assertThat(rootTimer.getCount()).isEqualTo(1);
        assertThat(rootTimer.getActive()).isTrue();

        // cleanup
        container.interruptAppUnderTest();
        future.get();
        executor.shutdown();
        if (!executor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate executor");
        }
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
