/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.test;

import static org.fest.assertions.api.Assertions.assertThat;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import javax.annotation.Nullable;

import org.informantproject.core.util.DaemonExecutors;
import org.informantproject.testkit.AppUnderTest;
import org.informantproject.testkit.Configuration.CoreProperties;
import org.informantproject.testkit.InformantContainer;
import org.informantproject.testkit.Trace;
import org.informantproject.testkit.TraceMarker;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class StuckTraceTest {

    private static InformantContainer container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = InformantContainer.create();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.shutdown();
    }

    @Test
    public void shouldReadTraces() throws Exception {
        // given
        CoreProperties coreProperties = container.getInformant().getCoreProperties();
        coreProperties.setThresholdMillis(0);
        coreProperties.setStuckThresholdSeconds(1);
        container.getInformant().updateCoreProperties(coreProperties);
        // when
        ExecutorService executorService = DaemonExecutors.newSingleThreadExecutor("StackTraceTest");
        Future<Void> future = executorService.submit(new Callable<Void>() {
            @Nullable
            public Void call() throws Exception {
                container.executeAppUnderTest(ShouldGenerateStuckTrace.class);
                return null;
            }
        });
        // then
        // need to give container enough time to start up and for the trace to get stuck
        // loop in order to not wait too little or too much
        Trace trace = null;
        // sleep a bit, no point in over polling
        Thread.sleep(1000);
        for (int i = 0; i < 10; i++) {
            trace = container.getInformant().getActiveTrace();
            if (trace != null && trace.isStuck()) {
                break;
            }
            Thread.sleep(50);
        }
        if (trace == null) {
            throw new AssertionError("no active trace found");
        }
        assertThat(trace.isStuck()).isTrue();
        assertThat(trace.isCompleted()).isFalse();
        future.get();
        // should now be reported as unstuck
        trace = container.getInformant().getLastTrace();
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
            Thread.sleep(1200);
        }
    }
}
