/*
 * Copyright 2011-2015 the original author or authors.
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

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.TraceMarker;
import org.glowroot.container.config.PluginConfig;
import org.glowroot.container.config.TransactionConfig;
import org.glowroot.container.trace.Trace;
import org.glowroot.container.trace.TraceEntry;

import static org.assertj.core.api.Assertions.assertThat;

public class TraceEntryStackTraceTest {

    private static final String PLUGIN_ID = "glowroot-integration-tests";

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.getSharedContainer();
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
    public void shouldReadTraceEntryStackTrace() throws Exception {
        // given
        TransactionConfig transactionConfig = container.getConfigService().getTransactionConfig();
        container.getConfigService().updateTransactionConfig(transactionConfig);
        PluginConfig pluginConfig = container.getConfigService().getPluginConfig(PLUGIN_ID);
        pluginConfig.setProperty("captureTraceEntryStackTraces", true);
        container.getConfigService().updatePluginConfig(PLUGIN_ID, pluginConfig);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithTraceEntryStackTrace.class);
        Trace trace = container.getTraceService().getLastTrace();
        List<TraceEntry> entries = container.getTraceService().getEntries(trace.getId());
        assertThat(entries).hasSize(1);
        List<StackTraceElement> stackTrace = entries.get(0).getStackTrace();
        assertThat(stackTrace).isNotEmpty();
        assertThat(stackTrace.get(0).getClassName()).isEqualTo(Pause.class.getName());
        assertThat(stackTrace.get(0).getMethodName()).isEqualTo("pauseOneMillisecond");
        assertThat(stackTrace.get(0).getFileName())
                .isEqualTo(Pause.class.getSimpleName() + ".java");
        for (StackTraceElement element : stackTrace) {
            assertThat(element.getMethodName()).doesNotContain("$glowroot$");
        }
    }

    public static class ShouldGenerateTraceWithTraceEntryStackTrace
            implements AppUnderTest, TraceMarker {
        @Override
        public void executeApp() throws InterruptedException {
            traceMarker();
        }
        @Override
        public void traceMarker() throws InterruptedException {
            new Pause().pauseOneMillisecond();
        }
    }
}
