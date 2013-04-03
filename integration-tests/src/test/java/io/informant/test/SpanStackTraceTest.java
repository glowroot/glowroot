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

import java.util.List;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import io.informant.Containers;
import io.informant.container.AppUnderTest;
import io.informant.container.Container;
import io.informant.container.TraceMarker;
import io.informant.container.config.GeneralConfig;
import io.informant.container.config.PluginConfig;
import io.informant.container.trace.Trace;

import static org.fest.assertions.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class SpanStackTraceTest {

    private static final String PLUGIN_ID = "io.informant:informant-integration-tests";

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
    public void shouldReadSpanStackTrace() throws Exception {
        // given
        GeneralConfig generalConfig = container.getConfigService().getGeneralConfig();
        generalConfig.setStoreThresholdMillis(0);
        container.getConfigService().updateGeneralConfig(generalConfig);
        PluginConfig pluginConfig = container.getConfigService().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("captureSpanStackTraces", true);
        container.getConfigService().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithSpanStackTrace.class);
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getSpans()).hasSize(2);
        List<String> stackTrace = trace.getSpans().get(1).getStackTrace();
        assertThat(stackTrace).isNotEmpty();
        assertThat(stackTrace.get(0)).startsWith(Pause.class.getName()
                + ".pauseOneMillisecond(" + Pause.class.getSimpleName() + ".java:");
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
            new Pause().pauseOneMillisecond();
        }
    }
}
