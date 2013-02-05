/**
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.test;

import static org.fest.assertions.api.Assertions.assertThat;
import io.informant.testkit.AppUnderTest;
import io.informant.testkit.GeneralConfig;
import io.informant.testkit.InformantContainer;
import io.informant.testkit.Trace;
import io.informant.testkit.TraceMarker;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import checkers.nullness.quals.Nullable;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class StuckTraceTest {

    private static InformantContainer container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = InformantContainer.create();
        // capture one trace to warm up the system since this test involves some timing
        container.getInformant().setStoreThresholdMillis(0);
        container.executeAppUnderTest(WarmupTrace.class);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
    }

    @After
    public void afterEachTest() throws Exception {
        container.getInformant().cleanUpAfterEachTest();
    }

    @Test
    public void shouldReadActiveStuckTrace() throws Exception {
        // given
        GeneralConfig generalConfig = container.getInformant().getGeneralConfig();
        generalConfig.setStoreThresholdMillis(0);
        generalConfig.setStuckThresholdSeconds(0);
        container.getInformant().updateGeneralConfig(generalConfig);
        // when
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future<Void> future = executorService.submit(new Callable<Void>() {
            @Nullable
            public Void call() throws Exception {
                container.executeAppUnderTest(ShouldGenerateStuckTrace.class);
                return null;
            }
        });
        // then
        // test harness needs to kick off test and stuck trace collector polls and marks stuck
        // traces every 100 milliseconds, so may need to wait a little
        long startAt = System.currentTimeMillis();
        Trace trace = null;
        while (true) {
            trace = container.getInformant().getActiveTrace(0);
            if ((trace != null && trace.isStuck())
                    || System.currentTimeMillis() - startAt >= 2000) {
                break;
            }
            Thread.sleep(10);
        }
        assertThat(trace).isNotNull();
        assertThat(trace.isStuck()).isTrue();
        assertThat(trace.isActive()).isTrue();
        assertThat(trace.isCompleted()).isFalse();
        future.get();
        // should now be reported as unstuck
        trace = container.getInformant().getLastTraceSummary();
        assertThat(trace.isStuck()).isFalse();
        assertThat(trace.isCompleted()).isTrue();
        // cleanup
        executorService.shutdown();
    }

    public static class ShouldGenerateStuckTrace implements AppUnderTest, TraceMarker {
        public void executeApp() throws InterruptedException {
            traceMarker();
        }
        public void traceMarker() throws InterruptedException {
            // stuck trace collector polls for stuck traces every 100 milliseconds,
            // and this test polls for active stuck traces every 10 milliseconds
            Thread.sleep(500);
        }
    }

    public static class WarmupTrace implements AppUnderTest, TraceMarker {
        public void executeApp() throws InterruptedException {
            traceMarker();
        }
        public void traceMarker() throws InterruptedException {
            Thread.sleep(1);
        }
    }
}
