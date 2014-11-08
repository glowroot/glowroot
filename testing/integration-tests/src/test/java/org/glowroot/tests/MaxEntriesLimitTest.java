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

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.common.base.Stopwatch;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.TraceMarker;
import org.glowroot.container.config.AdvancedConfig;
import org.glowroot.container.trace.Trace;
import org.glowroot.container.trace.TraceEntry;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

public class MaxEntriesLimitTest {

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
    public void shouldReadActivePartialTrace() throws Exception {
        // given
        AdvancedConfig advancedConfig = container.getConfigService().getAdvancedConfig();
        advancedConfig.setMaxTraceEntriesPerTransaction(100);
        container.getConfigService().updateAdvancedConfig(advancedConfig);
        // when
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        executorService.submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                container.executeAppUnderTest(GenerateLotsOfEntries.class);
                return null;
            }
        });
        // then
        // test harness needs to kick off test, so may need to wait a little
        Stopwatch stopwatch = Stopwatch.createStarted();
        Trace trace = null;
        List<TraceEntry> entries = null;
        while (stopwatch.elapsed(SECONDS) < 2) {
            trace = container.getTraceService().getActiveTrace(0, MILLISECONDS);
            if (trace == null) {
                continue;
            }
            entries = container.getTraceService().getEntries(trace.getId());
            if (entries.size() == 101) {
                break;
            }
            // otherwise continue
        }
        assertThat(trace).isNotNull();
        assertThat(entries).hasSize(101);
        assertThat(entries.get(100).isLimitExceededMarker()).isTrue();

        // part 2 of this test
        advancedConfig = container.getConfigService().getAdvancedConfig();
        advancedConfig.setMaxTraceEntriesPerTransaction(200);
        container.getConfigService().updateAdvancedConfig(advancedConfig);
        stopwatch.stop().reset().start();
        while (stopwatch.elapsed(SECONDS) < 2) {
            trace = container.getTraceService().getActiveTrace(0, MILLISECONDS);
            if (trace == null) {
                continue;
            }
            entries = container.getTraceService().getEntries(trace.getId());
            if (entries.size() == 201) {
                break;
            }
            // otherwise continue
        }
        container.interruptAppUnderTest();
        assertThat(trace).isNotNull();
        assertThat(entries).hasSize(201);
        assertThat(entries.get(100).isLimitExceededMarker()).isTrue();
        assertThat(entries.get(101).isLimitExtendedMarker()).isTrue();
        assertThat(entries.get(200).isLimitExceededMarker()).isTrue();
        // cleanup
        executorService.shutdown();
    }

    public static class GenerateLotsOfEntries implements AppUnderTest, TraceMarker {
        @Override
        public void executeApp() throws Exception {
            traceMarker();
        }
        @Override
        public void traceMarker() {
            while (true) {
                new LevelOne().call("a", "b");
                if (Thread.interrupted()) {
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
