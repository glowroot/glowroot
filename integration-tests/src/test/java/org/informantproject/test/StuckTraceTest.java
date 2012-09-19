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
import org.informantproject.testkit.Config.CoreConfig;
import org.informantproject.testkit.InformantContainer;
import org.informantproject.testkit.Trace;
import org.informantproject.testkit.TraceMarker;
import org.junit.After;
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
        container.closeAndDeleteFiles();
    }

    @After
    public void afterEachTest() throws Exception {
        container.getInformant().deleteAllTraces();
    }

    @Test
    public void shouldReadActiveStuckTrace() throws Exception {
        // given
        CoreConfig coreConfig = container.getInformant().getCoreConfig();
        coreConfig.setPersistenceThresholdMillis(0);
        coreConfig.setStuckThresholdSeconds(0);
        container.getInformant().updateCoreConfig(coreConfig);
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
        // stuck trace collector polls and marks stuck traces every 100 milliseconds
        // so may need to wait a little
        Trace trace = null;
        for (int i = 0; i < 100; i++) {
            trace = container.getInformant().getActiveTrace(5000);
            if (trace.isStuck()) {
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
            // and this test polls for active stuck traces every 25 milliseconds
            Thread.sleep(150);
        }
    }
}
