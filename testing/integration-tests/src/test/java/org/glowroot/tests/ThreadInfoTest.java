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

import java.lang.management.ManagementFactory;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.TraceMarker;
import org.glowroot.container.config.AdvancedConfig;
import org.glowroot.container.trace.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class ThreadInfoTest {

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
    public void shouldReadTraceThreadInfoConfigEnabled() throws Exception {
        // given
        // when
        container.executeAppUnderTest(Normal.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getThreadCpuTime()).isNotNull();
        assertThat(trace.getThreadBlockedTime()).isNotNull();
        assertThat(trace.getThreadWaitedTime()).isNotNull();
    }

    @Test
    public void shouldReadTraceThreadInfoConfigDisabled() throws Exception {
        // given
        AdvancedConfig advancedConfig = container.getConfigService().getAdvancedConfig();
        advancedConfig.setCaptureThreadInfo(false);
        container.getConfigService().updateAdvancedConfig(advancedConfig);
        // when
        container.executeAppUnderTest(Normal.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getThreadCpuTime()).isNull();
        assertThat(trace.getThreadBlockedTime()).isNull();
        assertThat(trace.getThreadWaitedTime()).isNull();
    }

    @Test
    public void shouldReadTraceCpuTimeDisabled() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ThreadCpuTimeDisabled.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getThreadCpuTime()).isNull();
        assertThat(trace.getThreadBlockedTime()).isNotNull();
        assertThat(trace.getThreadWaitedTime()).isNotNull();
    }

    @Test
    public void shouldReadTraceCpuTimeDisabledMid() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ThreadCpuTimeDisabledMid.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getThreadCpuTime()).isNull();
        assertThat(trace.getThreadBlockedTime()).isNotNull();
        assertThat(trace.getThreadWaitedTime()).isNotNull();
    }

    @Test
    public void shouldReadTraceCpuTimeEnabledMid() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ThreadCpuTimeEnabledMid.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getThreadCpuTime()).isNull();
        assertThat(trace.getThreadBlockedTime()).isNotNull();
        assertThat(trace.getThreadWaitedTime()).isNotNull();
    }

    @Test
    public void shouldReadTraceContentionMonitoringDisabled() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ThreadContentionMonitoringDisabled.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getThreadCpuTime()).isNotNull();
        assertThat(trace.getThreadBlockedTime()).isNull();
        assertThat(trace.getThreadWaitedTime()).isNull();
    }

    @Test
    public void shouldReadTraceContentionMonitoringDisabledMid() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ThreadContentionMonitoringDisabledMid.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getThreadCpuTime()).isNotNull();
        assertThat(trace.getThreadBlockedTime()).isNull();
        assertThat(trace.getThreadWaitedTime()).isNull();
    }

    @Test
    public void shouldReadTraceContentionMonitoringEnabledMid() throws Exception {
        // given
        // when
        container.executeAppUnderTest(ThreadContentionMonitoringEnabledMid.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getThreadCpuTime()).isNotNull();
        assertThat(trace.getThreadBlockedTime()).isNull();
        assertThat(trace.getThreadWaitedTime()).isNull();
    }

    @Test
    public void shouldReadTraceBothDisabled() throws Exception {
        // given
        // when
        container.executeAppUnderTest(BothDisabled.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getThreadCpuTime()).isNull();
        assertThat(trace.getThreadBlockedTime()).isNull();
        assertThat(trace.getThreadWaitedTime()).isNull();
    }

    public static class Normal implements AppUnderTest {
        @Override
        public void executeApp() {
            new LevelOne().call("a", "b");
        }
    }

    public static class ThreadCpuTimeDisabled implements AppUnderTest {
        @Override
        public void executeApp() {
            boolean original =
                    ManagementFactory.getThreadMXBean().isThreadCpuTimeEnabled();
            ManagementFactory.getThreadMXBean().setThreadCpuTimeEnabled(false);
            try {
                new LevelOne().call("a", "b");
            } finally {
                ManagementFactory.getThreadMXBean().setThreadCpuTimeEnabled(original);
            }
        }
    }

    public static class ThreadCpuTimeDisabledMid implements AppUnderTest, TraceMarker {
        @Override
        public void executeApp() {
            boolean original =
                    ManagementFactory.getThreadMXBean().isThreadCpuTimeEnabled();
            try {
                traceMarker();
            } finally {
                ManagementFactory.getThreadMXBean().setThreadCpuTimeEnabled(original);
            }
        }
        @Override
        public void traceMarker() {
            ManagementFactory.getThreadMXBean().setThreadCpuTimeEnabled(false);
        }
    }

    public static class ThreadCpuTimeEnabledMid implements AppUnderTest, TraceMarker {
        @Override
        public void executeApp() {
            boolean original =
                    ManagementFactory.getThreadMXBean().isThreadCpuTimeEnabled();
            ManagementFactory.getThreadMXBean().setThreadCpuTimeEnabled(false);
            try {
                traceMarker();
            } finally {
                ManagementFactory.getThreadMXBean().setThreadCpuTimeEnabled(original);
            }
        }
        @Override
        public void traceMarker() {
            ManagementFactory.getThreadMXBean().setThreadCpuTimeEnabled(true);
        }
    }

    public static class ThreadContentionMonitoringDisabled implements AppUnderTest {
        @Override
        public void executeApp() {
            boolean original =
                    ManagementFactory.getThreadMXBean().isThreadContentionMonitoringEnabled();
            ManagementFactory.getThreadMXBean().setThreadContentionMonitoringEnabled(false);
            try {
                new LevelOne().call("c", "d");
            } finally {
                ManagementFactory.getThreadMXBean().setThreadContentionMonitoringEnabled(original);
            }
        }
    }

    public static class ThreadContentionMonitoringDisabledMid implements AppUnderTest, TraceMarker {
        @Override
        public void executeApp() {
            boolean original =
                    ManagementFactory.getThreadMXBean().isThreadContentionMonitoringEnabled();
            try {
                traceMarker();
            } finally {
                ManagementFactory.getThreadMXBean().setThreadContentionMonitoringEnabled(original);
            }
        }
        @Override
        public void traceMarker() {
            ManagementFactory.getThreadMXBean().setThreadContentionMonitoringEnabled(false);
        }
    }

    public static class ThreadContentionMonitoringEnabledMid implements AppUnderTest, TraceMarker {
        @Override
        public void executeApp() {
            boolean original =
                    ManagementFactory.getThreadMXBean().isThreadContentionMonitoringEnabled();
            ManagementFactory.getThreadMXBean().setThreadContentionMonitoringEnabled(false);
            try {
                traceMarker();
            } finally {
                ManagementFactory.getThreadMXBean().setThreadContentionMonitoringEnabled(original);
            }
        }
        @Override
        public void traceMarker() {
            ManagementFactory.getThreadMXBean().setThreadContentionMonitoringEnabled(true);
        }
    }

    public static class BothDisabled implements AppUnderTest {
        @Override
        public void executeApp() {
            boolean original1 = ManagementFactory.getThreadMXBean().isThreadCpuTimeEnabled();
            boolean original2 =
                    ManagementFactory.getThreadMXBean().isThreadContentionMonitoringEnabled();
            ManagementFactory.getThreadMXBean().setThreadCpuTimeEnabled(false);
            ManagementFactory.getThreadMXBean().setThreadContentionMonitoringEnabled(false);
            try {
                new LevelOne().call("c", "d");
            } finally {
                ManagementFactory.getThreadMXBean().setThreadCpuTimeEnabled(original1);
                ManagementFactory.getThreadMXBean().setThreadContentionMonitoringEnabled(original2);
            }
        }
    }
}
