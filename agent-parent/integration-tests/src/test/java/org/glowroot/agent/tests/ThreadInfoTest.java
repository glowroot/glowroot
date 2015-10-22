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
package org.glowroot.agent.tests;

import java.lang.management.ManagementFactory;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.agent.it.harness.model.ConfigUpdate.AdvancedConfigUpdate;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

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
        Trace trace = container.execute(Normal.class);
        // then
        assertThat(trace.getHeader().hasThreadCpuNanos()).isTrue();
        assertThat(trace.getHeader().hasThreadBlockedNanos()).isTrue();
        assertThat(trace.getHeader().hasThreadWaitedNanos()).isTrue();
    }

    @Test
    public void shouldReadTraceThreadInfoConfigDisabled() throws Exception {
        // given
        container.getConfigService().updateAdvancedConfig(
                AdvancedConfigUpdate.newBuilder()
                        .setCaptureThreadInfo(ProtoOptional.of(false))
                        .build());
        // when
        Trace trace = container.execute(Normal.class);
        // then
        assertThat(trace.getHeader().hasThreadCpuNanos()).isFalse();
        assertThat(trace.getHeader().hasThreadBlockedNanos()).isFalse();
        assertThat(trace.getHeader().hasThreadWaitedNanos()).isFalse();
    }

    @Test
    public void shouldReadTraceCpuTimeDisabled() throws Exception {
        // given
        // when
        Trace trace = container.execute(ThreadCpuTimeDisabled.class);
        // then
        assertThat(trace.getHeader().hasThreadCpuNanos()).isFalse();
        assertThat(trace.getHeader().hasThreadBlockedNanos()).isTrue();
        assertThat(trace.getHeader().hasThreadWaitedNanos()).isTrue();
    }

    @Test
    public void shouldReadTraceCpuTimeDisabledMid() throws Exception {
        // given
        // when
        Trace trace = container.execute(ThreadCpuTimeDisabledMid.class);
        // then
        assertThat(trace.getHeader().hasThreadCpuNanos()).isFalse();
        assertThat(trace.getHeader().hasThreadBlockedNanos()).isTrue();
        assertThat(trace.getHeader().hasThreadWaitedNanos()).isTrue();
    }

    @Test
    public void shouldReadTraceCpuTimeEnabledMid() throws Exception {
        // given
        // when
        Trace trace = container.execute(ThreadCpuTimeEnabledMid.class);
        // then
        assertThat(trace.getHeader().hasThreadCpuNanos()).isFalse();
        assertThat(trace.getHeader().hasThreadBlockedNanos()).isTrue();
        assertThat(trace.getHeader().hasThreadWaitedNanos()).isTrue();
    }

    @Test
    public void shouldReadTraceContentionMonitoringDisabled() throws Exception {
        // given
        // when
        Trace trace = container.execute(ThreadContentionMonitoringDisabled.class);
        // then
        assertThat(trace.getHeader().hasThreadCpuNanos()).isTrue();
        assertThat(trace.getHeader().hasThreadBlockedNanos()).isFalse();
        assertThat(trace.getHeader().hasThreadWaitedNanos()).isFalse();
    }

    @Test
    public void shouldReadTraceContentionMonitoringDisabledMid() throws Exception {
        // given
        // when
        Trace trace = container.execute(ThreadContentionMonitoringDisabledMid.class);
        // then
        assertThat(trace.getHeader().hasThreadCpuNanos()).isTrue();
        assertThat(trace.getHeader().hasThreadBlockedNanos()).isFalse();
        assertThat(trace.getHeader().hasThreadWaitedNanos()).isFalse();
    }

    @Test
    public void shouldReadTraceContentionMonitoringEnabledMid() throws Exception {
        // given
        // when
        Trace trace = container.execute(ThreadContentionMonitoringEnabledMid.class);
        // then
        assertThat(trace.getHeader().hasThreadCpuNanos()).isTrue();
        assertThat(trace.getHeader().hasThreadBlockedNanos()).isFalse();
        assertThat(trace.getHeader().hasThreadWaitedNanos()).isFalse();
    }

    @Test
    public void shouldReadTraceBothDisabled() throws Exception {
        // given
        // when
        Trace trace = container.execute(BothDisabled.class);
        // then
        assertThat(trace.getHeader().hasThreadCpuNanos()).isFalse();
        assertThat(trace.getHeader().hasThreadBlockedNanos()).isFalse();
        assertThat(trace.getHeader().hasThreadWaitedNanos()).isFalse();
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
            boolean original = ManagementFactory.getThreadMXBean().isThreadCpuTimeEnabled();
            ManagementFactory.getThreadMXBean().setThreadCpuTimeEnabled(false);
            try {
                new LevelOne().call("a", "b");
            } finally {
                ManagementFactory.getThreadMXBean().setThreadCpuTimeEnabled(original);
            }
        }
    }

    public static class ThreadCpuTimeDisabledMid implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() {
            boolean original = ManagementFactory.getThreadMXBean().isThreadCpuTimeEnabled();
            try {
                transactionMarker();
            } finally {
                ManagementFactory.getThreadMXBean().setThreadCpuTimeEnabled(original);
            }
        }
        @Override
        public void transactionMarker() {
            ManagementFactory.getThreadMXBean().setThreadCpuTimeEnabled(false);
        }
    }

    public static class ThreadCpuTimeEnabledMid implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() {
            boolean original = ManagementFactory.getThreadMXBean().isThreadCpuTimeEnabled();
            ManagementFactory.getThreadMXBean().setThreadCpuTimeEnabled(false);
            try {
                transactionMarker();
            } finally {
                ManagementFactory.getThreadMXBean().setThreadCpuTimeEnabled(original);
            }
        }
        @Override
        public void transactionMarker() {
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

    public static class ThreadContentionMonitoringDisabledMid
            implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() {
            boolean original =
                    ManagementFactory.getThreadMXBean().isThreadContentionMonitoringEnabled();
            try {
                transactionMarker();
            } finally {
                ManagementFactory.getThreadMXBean().setThreadContentionMonitoringEnabled(original);
            }
        }
        @Override
        public void transactionMarker() {
            ManagementFactory.getThreadMXBean().setThreadContentionMonitoringEnabled(false);
        }
    }

    public static class ThreadContentionMonitoringEnabledMid
            implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() {
            boolean original =
                    ManagementFactory.getThreadMXBean().isThreadContentionMonitoringEnabled();
            ManagementFactory.getThreadMXBean().setThreadContentionMonitoringEnabled(false);
            try {
                transactionMarker();
            } finally {
                ManagementFactory.getThreadMXBean().setThreadContentionMonitoringEnabled(original);
            }
        }
        @Override
        public void transactionMarker() {
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
