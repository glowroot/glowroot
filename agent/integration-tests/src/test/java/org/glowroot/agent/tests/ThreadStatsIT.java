/*
 * Copyright 2011-2023 the original author or authors.
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.agent.tests.app.LevelOne;
import org.glowroot.agent.util.JavaVersion;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.TransactionConfig;
import org.glowroot.wire.api.model.Proto.OptionalInt32;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class ThreadStatsIT {

    private static Container container;

    @BeforeAll
    public static void setUp() throws Exception {
        container = Containers.create();
    }

    @AfterAll
    public static void tearDown() throws Exception {
        container.close();
    }

    @AfterEach
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldReadTraceThreadStatsConfigEnabled() throws Exception {
        // when
        Trace trace = container.execute(Normal.class);

        // then
        assertThat(trace.getHeader().getMainThreadStats().getCpuNanos()).isNotEqualTo(-1);
        assertThat(trace.getHeader().getMainThreadStats().getBlockedNanos()).isNotEqualTo(-1);
        assertThat(trace.getHeader().getMainThreadStats().getWaitedNanos()).isNotEqualTo(-1);
        if (!JavaVersion.isJ9Jvm()) {
            assertThat(trace.getHeader().getMainThreadStats().getAllocatedBytes())
                    .isNotEqualTo(-1);
        }
    }

    @Test
    public void shouldReadTraceThreadStatsConfigDisabled() throws Exception {
        // given
        disableCaptureThreadStats();
        // when
        Trace trace = container.execute(Normal.class);
        // then
        assertThat(trace.getHeader().getMainThreadStats().getCpuNanos()).isEqualTo(-1);
        assertThat(trace.getHeader().getMainThreadStats().getBlockedNanos()).isEqualTo(-1);
        assertThat(trace.getHeader().getMainThreadStats().getWaitedNanos()).isEqualTo(-1);
        assertThat(trace.getHeader().getMainThreadStats().getAllocatedBytes()).isEqualTo(-1);
    }

    @Test
    public void shouldReadTraceCpuTimeDisabled() throws Exception {
        // when
        Trace trace = container.execute(ThreadCpuTimeDisabled.class);

        // then
        assertThat(trace.getHeader().getMainThreadStats().getCpuNanos()).isEqualTo(-1);
        assertThat(trace.getHeader().getMainThreadStats().getBlockedNanos()).isNotEqualTo(-1);
        assertThat(trace.getHeader().getMainThreadStats().getWaitedNanos()).isNotEqualTo(-1);
        if (!JavaVersion.isJ9Jvm()) {
            assertThat(trace.getHeader().getMainThreadStats().getAllocatedBytes())
                    .isNotEqualTo(-1);
        }
    }

    @Test
    public void shouldReadTraceCpuTimeDisabledMid() throws Exception {
        // when
        Trace trace = container.execute(ThreadCpuTimeDisabledMid.class);

        // then
        assertThat(trace.getHeader().getMainThreadStats().getCpuNanos()).isEqualTo(-1);
        assertThat(trace.getHeader().getMainThreadStats().getBlockedNanos()).isNotEqualTo(-1);
        assertThat(trace.getHeader().getMainThreadStats().getWaitedNanos()).isNotEqualTo(-1);
        if (!JavaVersion.isJ9Jvm()) {
            assertThat(trace.getHeader().getMainThreadStats().getAllocatedBytes())
                    .isNotEqualTo(-1);
        }
    }

    @Test
    public void shouldReadTraceCpuTimeEnabledMid() throws Exception {
        // when
        Trace trace = container.execute(ThreadCpuTimeEnabledMid.class);

        // then
        assertThat(trace.getHeader().getMainThreadStats().getCpuNanos()).isEqualTo(-1);
        assertThat(trace.getHeader().getMainThreadStats().getBlockedNanos()).isNotEqualTo(-1);
        assertThat(trace.getHeader().getMainThreadStats().getWaitedNanos()).isNotEqualTo(-1);
        if (!JavaVersion.isJ9Jvm()) {
            assertThat(trace.getHeader().getMainThreadStats().getAllocatedBytes())
                    .isNotEqualTo(-1);
        }
    }

    @Test
    public void shouldReadTraceContentionMonitoringDisabled() throws Exception {
        // when
        Trace trace = container.execute(ThreadContentionMonitoringDisabled.class);

        // then
        assertThat(trace.getHeader().getMainThreadStats().getCpuNanos()).isNotEqualTo(-1);
        assertThat(trace.getHeader().getMainThreadStats().getBlockedNanos()).isEqualTo(-1);
        assertThat(trace.getHeader().getMainThreadStats().getWaitedNanos()).isEqualTo(-1);
        if (!JavaVersion.isJ9Jvm()) {
            assertThat(trace.getHeader().getMainThreadStats().getAllocatedBytes())
                    .isNotEqualTo(-1);
        }
    }

    @Test
    public void shouldReadTraceContentionMonitoringDisabledMid() throws Exception {
        // when
        Trace trace = container.execute(ThreadContentionMonitoringDisabledMid.class);

        // then
        assertThat(trace.getHeader().getMainThreadStats().getCpuNanos()).isNotEqualTo(-1);
        assertThat(trace.getHeader().getMainThreadStats().getBlockedNanos()).isEqualTo(-1);
        assertThat(trace.getHeader().getMainThreadStats().getWaitedNanos()).isEqualTo(-1);
        if (!JavaVersion.isJ9Jvm()) {
            assertThat(trace.getHeader().getMainThreadStats().getAllocatedBytes())
                    .isNotEqualTo(-1);
        }
    }

    @Test
    public void shouldReadTraceContentionMonitoringEnabledMid() throws Exception {
        // when
        Trace trace = container.execute(ThreadContentionMonitoringEnabledMid.class);

        // then
        assertThat(trace.getHeader().getMainThreadStats().getCpuNanos()).isNotEqualTo(-1);
        assertThat(trace.getHeader().getMainThreadStats().getBlockedNanos()).isEqualTo(-1);
        assertThat(trace.getHeader().getMainThreadStats().getWaitedNanos()).isEqualTo(-1);
        if (!JavaVersion.isJ9Jvm()) {
            assertThat(trace.getHeader().getMainThreadStats().getAllocatedBytes())
                    .isNotEqualTo(-1);
        }
    }

    @Test
    public void shouldReadTraceBothDisabled() throws Exception {
        // given
        disableCaptureThreadStats();
        // when
        Trace trace = container.execute(BothDisabled.class);
        // then
        assertThat(trace.getHeader().getMainThreadStats().getCpuNanos()).isEqualTo(-1);
        assertThat(trace.getHeader().getMainThreadStats().getBlockedNanos()).isEqualTo(-1);
        assertThat(trace.getHeader().getMainThreadStats().getWaitedNanos()).isEqualTo(-1);
        assertThat(trace.getHeader().getMainThreadStats().getAllocatedBytes()).isEqualTo(-1);
    }

    private static void disableCaptureThreadStats() throws Exception {
        container.getConfigService().updateTransactionConfig(
                TransactionConfig.newBuilder()
                        .setSlowThresholdMillis(OptionalInt32.newBuilder().setValue(0))
                        .setProfilingIntervalMillis(OptionalInt32.newBuilder().setValue(1000))
                        .setCaptureThreadStats(false)
                        .build());
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
