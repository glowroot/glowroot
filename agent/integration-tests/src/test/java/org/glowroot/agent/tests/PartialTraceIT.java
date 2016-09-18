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

import com.google.common.base.Stopwatch;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.agent.tests.app.Pause;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AdvancedConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.TransactionConfig;
import org.glowroot.wire.api.model.Proto.OptionalInt32;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

public class PartialTraceIT {

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
    public void shouldReadPartialTraceStuckOnRootTimer() throws Exception {
        shouldReadPartialTrace(ShouldGenerateActiveTraceStuckOnRootTimer.class, false);
    }

    @Test
    public void shouldReadPartialTraceStuckOnNonRootTimer() throws Exception {
        shouldReadPartialTrace(ShouldGenerateActiveTraceStuckOnNonRootTimer.class, true);
    }

    private Trace shouldReadPartialTrace(final Class<? extends AppUnderTest> appUnderTest,
            boolean stuckOnNonRoot) throws Exception {
        // given
        container.getConfigService().updateTransactionConfig(
                TransactionConfig.newBuilder()
                        .setSlowThresholdMillis(OptionalInt32.newBuilder().setValue(0))
                        .setProfilingIntervalMillis(ProtoOptional.of(10))
                        .build());
        container.getConfigService().updateAdvancedConfig(
                AdvancedConfig.newBuilder()
                        .setImmediatePartialStoreThresholdSeconds(ProtoOptional.of(1))
                        .build());

        // when
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Trace> future = executor.submit(new Callable<Trace>() {
            @Override
            public Trace call() throws Exception {
                try {
                    return container.execute(appUnderTest);
                } catch (Throwable t) {
                    t.printStackTrace();
                }
                return null;
            }
        });

        // then
        Stopwatch stopwatch = Stopwatch.createStarted();
        Trace trace = null;
        while (stopwatch.elapsed(SECONDS) < 10) {
            trace = container.getCollectedPartialTrace();
            if (trace.getHeader().getMainThreadProfileSampleCount() > 0) {
                break;
            }
            Thread.sleep(10);
        }
        if (stuckOnNonRoot) {
            // wait for trace to get into nested timer
            stopwatch = Stopwatch.createStarted();
            while (stopwatch.elapsed(SECONDS) < 10) {
                trace = container.getCollectedPartialTrace();
                if (!trace.getHeader().getMainThreadRootTimer().getChildTimerList().isEmpty()) {
                    break;
                }
                Thread.sleep(10);
            }
        }
        assertThat(trace).isNotNull();
        assertThat(trace.getHeader().getPartial()).isTrue();
        if (stuckOnNonRoot) {
            assertThat(trace.getEntryList()).hasSize(2);
        } else {
            assertThat(trace.getEntryList()).isEmpty();
        }
        assertThat(trace.hasMainThreadProfile()).isTrue();
        // interrupt trace
        container.interruptAppUnderTest();
        trace = future.get();
        // should now be reported as complete (not partial)
        assertThat(trace.getHeader().getPartial()).isFalse();
        // cleanup
        executor.shutdown();
        if (!executor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate executor");
        }
        return trace;
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
            }
        }
    }
}
