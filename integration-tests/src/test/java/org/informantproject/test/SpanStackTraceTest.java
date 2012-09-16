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

import java.util.List;

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
public class SpanStackTraceTest {

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
    public void shouldReadSpanStackTrace() throws Exception {
        // given
        CoreConfig coreConfig = container.getInformant().getCoreConfig();
        coreConfig.setPersistenceThresholdMillis(0);
        coreConfig.setSpanStackTraceThresholdMillis(100);
        container.getInformant().updateCoreConfig(coreConfig);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithSpanStackTrace.class);
        Trace trace = container.getInformant().getLastTrace();
        assertThat(trace.getSpans()).hasSize(2);
        String stackTraceHash = trace.getSpans().get(1).getStackTraceHash();
        List<String> stackTrace = container.getInformant().getStackTrace(stackTraceHash);
        assertThat(stackTrace).isNotEmpty();
        for (String element : stackTrace) {
            assertThat(element).doesNotContain("$informant$");
            // assert that element contains line number (or is a native method
            assertThat(element).matches(".*\\.java:[0-9]+\\)|.*Native Method\\)");
        }
    }

    public static class ShouldGenerateTraceWithSpanStackTrace implements AppUnderTest, TraceMarker {
        public void executeApp() throws InterruptedException {
            traceMarker();
        }
        public void traceMarker() throws InterruptedException {
            new Pause().pause(150);
        }
    }
}
