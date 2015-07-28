/*
 * Copyright 2015 the original author or authors.
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

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.api.PluginServices;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.TraceMarker;
import org.glowroot.container.config.GeneralConfig;
import org.glowroot.container.trace.Trace;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;

public class SetTraceStoreThresholdTest {

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
    public void shouldNotReadTrace() throws Exception {
        // given
        // when
        container.executeAppUnderTest(SetLargeTraceStoreThreshold.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace).isNull();
    }

    @Test
    public void shouldReadTrace() throws Exception {
        // given
        GeneralConfig config = container.getConfigService().getGeneralConfig();
        config.setSlowTraceThresholdMillis(Integer.MAX_VALUE);
        container.getConfigService().updateGeneralConfig(config);
        // when
        container.executeAppUnderTest(SetLargeAndThenSmallTraceStoreThreshold.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace).isNotNull();
    }

    @Test
    public void shouldReadTrace2() throws Exception {
        // given
        GeneralConfig config = container.getConfigService().getGeneralConfig();
        config.setSlowTraceThresholdMillis(Integer.MAX_VALUE);
        container.getConfigService().updateGeneralConfig(config);
        // when
        container.executeAppUnderTest(SetSmallAndThenLargeTraceStoreThreshold.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace).isNotNull();
    }

    public static class SetLargeTraceStoreThreshold implements AppUnderTest, TraceMarker {
        private static final PluginServices pluginServices =
                PluginServices.get("glowroot-integration-tests");
        @Override
        public void executeApp() {
            traceMarker();
        }
        @Override
        public void traceMarker() {
            pluginServices.setSlowTraceThreshold(Long.MAX_VALUE, MILLISECONDS);
            new LevelOne().call("a", "b");
        }
    }

    public static class SetLargeAndThenSmallTraceStoreThreshold implements AppUnderTest,
            TraceMarker {
        private static final PluginServices pluginServices =
                PluginServices.get("glowroot-integration-tests");
        @Override
        public void executeApp() {
            traceMarker();
        }
        @Override
        public void traceMarker() {
            pluginServices.setSlowTraceThreshold(Long.MAX_VALUE, MILLISECONDS);
            pluginServices.setSlowTraceThreshold(0, MILLISECONDS);
            new LevelOne().call("a", "b");
        }
    }

    public static class SetSmallAndThenLargeTraceStoreThreshold implements AppUnderTest,
            TraceMarker {
        private static final PluginServices pluginServices =
                PluginServices.get("glowroot-integration-tests");
        @Override
        public void executeApp() {
            traceMarker();
        }
        @Override
        public void traceMarker() {
            pluginServices.setSlowTraceThreshold(0, MILLISECONDS);
            pluginServices.setSlowTraceThreshold(Long.MAX_VALUE, MILLISECONDS);
            new LevelOne().call("a", "b");
        }
    }
}
