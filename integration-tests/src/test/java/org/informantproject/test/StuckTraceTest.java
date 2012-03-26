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

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.informantproject.core.util.DaemonExecutors;
import org.informantproject.testkit.AppUnderTest;
import org.informantproject.testkit.Configuration.CoreConfiguration;
import org.informantproject.testkit.InformantContainer;
import org.informantproject.testkit.RootSpanMarker;
import org.informantproject.testkit.Trace;
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
        CoreConfiguration coreConfiguration = container.getInformant().getCoreConfiguration();
        coreConfiguration.setThresholdMillis(0);
        coreConfiguration.setStuckThresholdMillis(100);
        container.getInformant().updateCoreConfiguration(coreConfiguration);
        // when
        ExecutorService executorService = DaemonExecutors.newSingleThreadExecutor("StackTraceTest");
        Future<Void> future = executorService.submit(new Callable<Void>() {
            public Void call() throws Exception {
                container.executeAppUnderTest(ShouldGenerateStuckTrace.class);
                return null;
            }
        });
        // then
        // need to give container enough time to start up and for the trace to get stuck
        // loop in order to not wait too little or too much
        Trace trace = null;
        for (int i = 0; i < 100; i++) {
            trace = container.getInformant().getActiveTrace();
            if (trace != null && trace.isStuck()) {
                break;
            }
            Thread.sleep(50);
        }
        assertThat(trace.isStuck(), is(true));
        assertThat(trace.isCompleted(), is(false));
        future.get();
        // should now be reported as unstuck
        trace = container.getInformant().getLastTrace();
        assertThat(trace.isStuck(), is(false));
        assertThat(trace.isCompleted(), is(true));
        // cleanup
        executorService.shutdown();
    }

    public static class ShouldGenerateStuckTrace implements AppUnderTest, RootSpanMarker {
        public void executeApp() throws InterruptedException {
            rootSpanMarker();
        }
        public void rootSpanMarker() throws InterruptedException {
            Thread.sleep(300);
        }
    }
}
