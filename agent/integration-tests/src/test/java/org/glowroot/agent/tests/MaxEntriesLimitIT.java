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

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.agent.tests.app.LevelOne;
import org.glowroot.agent.tests.app.LogError;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AdvancedConfig;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

public class MaxEntriesLimitIT {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.create();
        // capture one header to warm up the system since this test involves some timing
        container.execute(WarmupTrace.class);
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
    public void shouldReadIsLimitExceededMarker() throws Exception {
        // given
        container.getConfigService().updateAdvancedConfig(AdvancedConfig.newBuilder()
                .setMaxTraceEntriesPerTransaction(ProtoOptional.of(100))
                .setImmediatePartialStoreThresholdSeconds(ProtoOptional.of(1))
                .build());

        // when
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Trace> future = executor.submit(new Callable<Trace>() {
            @Override
            public Trace call() throws Exception {
                return container.execute(GenerateLotsOfEntries.class);
            }
        });

        // then
        // integration test harness needs to kick off test, so may need to wait a little
        Stopwatch stopwatch = Stopwatch.createStarted();
        Trace trace = null;
        Trace.Header header = null;
        while (stopwatch.elapsed(SECONDS) < 10000) {
            trace = container.getCollectedPartialTrace();
            header = trace.getHeader();
            if (header.getEntryCount() == 100 && header.getEntryLimitExceeded()) {
                break;
            }
            // otherwise continue
            Thread.sleep(10);
        }
        assertThat(header).isNotNull();
        assertThat(header.getPartial()).isTrue();
        assertThat(header.getEntryCount()).isEqualTo(100);
        assertThat(header.getEntryLimitExceeded()).isTrue();

        // cleanup trace
        container.interruptAppUnderTest();
        future.get();
        executor.shutdown();
        if (!executor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate executor");
        }
    }

    @Test
    public void shouldReadLimitBypassedTraceEntries() throws Exception {
        // given
        container.getConfigService().updateAdvancedConfig(AdvancedConfig.newBuilder()
                .setMaxTraceEntriesPerTransaction(ProtoOptional.of(100))
                .build());

        // when
        Trace trace = container.execute(GenerateLimitBypassedEntries.class);

        // then
        assertThat(trace.getHeader().getEntryCount()).isEqualTo(101);
        assertThat(trace.getHeader().getEntryLimitExceeded()).isTrue();

        List<Trace.Entry> entries = trace.getEntryList();

        assertThat(entries).hasSize(101);
        assertThat(entries.get(100).getMessage()).isEqualTo("ERROR -- abc");
    }

    public static class GenerateLotsOfEntries implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            while (true) {
                new LevelOne().call("a", "b");
                if (Thread.interrupted()) {
                    return;
                }
            }
        }
    }

    public static class GenerateLimitBypassedEntries implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }
        @Override
        public void transactionMarker() {
            for (int i = 0; i < 1000; i++) {
                new LevelOne().call("a", "b");
            }
            new LogError().log("abc");
        }
    }

    public static class WarmupTrace implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws InterruptedException {
            transactionMarker();
        }
        @Override
        public void transactionMarker() throws InterruptedException {
            Thread.sleep(1);
        }
    }
}
