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

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import io.informant.Containers;
import io.informant.container.AppUnderTest;
import io.informant.container.Container;
import io.informant.container.TraceMarker;
import io.informant.container.config.FineProfilingConfig;
import io.informant.container.trace.Trace;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ThreadInterruptTest {

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
    public void shouldNotInterfereWithInterrupt() throws Exception {
        // given
        container.getConfigService().setStoreThresholdMillis(0);
        FineProfilingConfig fineProfilingConfig = container.getConfigService()
                .getFineProfilingConfig();
        fineProfilingConfig.setEnabled(true);
        fineProfilingConfig.setTracePercentage(100);
        fineProfilingConfig.setIntervalMillis(10);
        container.getConfigService().updateFineProfilingConfig(fineProfilingConfig);
        // when
        container.executeAppUnderTest(ShouldInterrupt.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
    }

    public static class ShouldInterrupt implements AppUnderTest, TraceMarker {
        public void executeApp() throws Exception {
            traceMarker();
            if (!Thread.interrupted()) {
                throw new IllegalStateException("Interrupt was expected");
            }
        }
        public void traceMarker() throws Exception {
            Thread.currentThread().interrupt();
        }
    }
}
