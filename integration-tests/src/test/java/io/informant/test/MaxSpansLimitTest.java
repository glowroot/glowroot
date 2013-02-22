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

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.fest.assertions.api.Assertions.assertThat;
import io.informant.testkit.AppUnderTest;
import io.informant.testkit.GeneralConfig;
import io.informant.testkit.InformantContainer;
import io.informant.testkit.Trace;
import io.informant.testkit.TraceMarker;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import checkers.nullness.quals.Nullable;

import com.google.common.base.Stopwatch;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class MaxSpansLimitTest {

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
        generalConfig.setMaxSpans(100);
        container.getInformant().updateGeneralConfig(generalConfig);
        // when
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        executorService.submit(new Callable<Void>() {
            @Nullable
            public Void call() throws Exception {
                container.executeAppUnderTest(GenerateLotsOfSpans.class);
                return null;
            }
        });
        // then
        // test harness needs to kick off test, so may need to wait a little
        Stopwatch stopwatch = new Stopwatch().start();
        Trace trace = null;
        while (stopwatch.elapsed(SECONDS) < 2) {
            trace = container.getInformant().getActiveTrace(0, MILLISECONDS);
            if (trace != null && trace.getSpans().size() == 101) {
                break;
            }
        }
        assertThat(trace).isNotNull();
        assertThat(trace.getSpans()).hasSize(101);
        assertThat(trace.getSpans().get(100).isLimitExceededMarker()).isTrue();

        // part 2 of this test
        generalConfig = container.getInformant().getGeneralConfig();
        generalConfig.setMaxSpans(200);
        container.getInformant().updateGeneralConfig(generalConfig);
        stopwatch.stop().reset().start();
        while (stopwatch.elapsed(SECONDS) < 2) {
            trace = container.getInformant().getActiveTrace(0, MILLISECONDS);
            if (trace != null && trace.getSpans().size() == 201) {
                break;
            }
        }
        container.interruptAppUnderTest();
        assertThat(trace).isNotNull();
        assertThat(trace.getSpans()).hasSize(201);
        assertThat(trace.getSpans().get(100).isLimitExceededMarker()).isTrue();
        assertThat(trace.getSpans().get(101).isLimitExtendedMarker()).isTrue();
        assertThat(trace.getSpans().get(200).isLimitExceededMarker()).isTrue();
        // cleanup
        executorService.shutdown();
    }

    public static class GenerateLotsOfSpans implements AppUnderTest, TraceMarker {
        public void executeApp() throws Exception {
            traceMarker();
        }
        public void traceMarker() throws Exception {
            while (true) {
                new LevelOne().call("a", "b");
                if (Thread.interrupted()) {
                    return;
                }
            }
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
