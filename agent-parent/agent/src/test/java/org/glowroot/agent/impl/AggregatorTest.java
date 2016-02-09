/*
 * Copyright 2014-2016 the original author or authors.
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
package org.glowroot.agent.impl;

import java.io.File;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.config.ImmutableAdvancedConfig;
import org.glowroot.agent.model.QueryData;
import org.glowroot.agent.model.ThreadStats;
import org.glowroot.agent.model.TimerImpl;
import org.glowroot.agent.model.Transaction;
import org.glowroot.common.util.Clock;
import org.glowroot.wire.api.Collector;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AggregateOuterClass.AggregatesByType;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValue;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.LogEvent;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.SystemInfo;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AggregatorTest {

    @Test
    public void shouldFlushWithTrace() throws InterruptedException {
        // given
        ScheduledExecutorService scheduledExecutorService = mock(ScheduledExecutorService.class);
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Runnable runnable = (Runnable) invocation.getArguments()[0];
                runnable.run();
                return null;
            }
        }).when(scheduledExecutorService).execute(any(Runnable.class));
        MockCollector aggregateCollector = new MockCollector();
        ConfigService configService = mock(ConfigService.class);
        when(configService.getAdvancedConfig())
                .thenReturn(ImmutableAdvancedConfig.builder().build());
        Aggregator aggregator = new Aggregator(scheduledExecutorService, aggregateCollector,
                configService, 1000, Clock.systemClock());

        Transaction transaction = mock(Transaction.class);
        TimerImpl mainThreadRootTimer = mock(TimerImpl.class);
        when(mainThreadRootTimer.getName()).thenReturn("mock timer");
        when(mainThreadRootTimer.getChildTimers())
                .thenReturn(ImmutableList.<TimerImpl>of().iterator());
        when(transaction.getTransactionType()).thenReturn("a type");
        when(transaction.getTransactionName()).thenReturn("a name");
        when(transaction.getDurationNanos()).thenReturn(MILLISECONDS.toNanos(123));
        when(transaction.getMainThreadRootTimer()).thenReturn(mainThreadRootTimer);
        when(transaction.getQueries()).thenReturn(ImmutableList.<QueryData>of().iterator());
        when(transaction.getMainThreadStats()).thenReturn(ThreadStats.NA);
        when(transaction.getAuxThreadRootTimers()).thenReturn(ImmutableList.<TimerImpl>of());
        when(transaction.getAuxThreadStats()).thenReturn(ImmutableList.<ThreadStats>of());
        // when
        int count = 0;
        long firstCaptureTime = aggregator.add(transaction);
        long aggregateCaptureTime = (long) Math.ceil(firstCaptureTime / 1000.0) * 1000;
        while (true) {
            long captureTime = aggregator.add(transaction);
            count++;
            if (captureTime > aggregateCaptureTime) {
                break;
            }
            Thread.sleep(1);
        }
        // then
        // aggregation is done in a separate thread, so give it a little time to complete
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 5000) {
            if (aggregateCollector.getTotalNanos() > 0) {
                break;
            }
        }
        assertThat(aggregateCollector.getTotalNanos()).isEqualTo(count * 123 * 1000000.0);
        aggregator.close();
    }

    private static class MockCollector implements Collector {

        // volatile needed for visibility from other thread
        private volatile double totalNanos;

        private double getTotalNanos() {
            return totalNanos;
        }

        @Override
        public void init(File glowrootBaseDir, SystemInfo systemInfo, AgentConfig agentConfig,
                AgentConfigUpdater agentConfigUpdater) {}

        @Override
        public void collectAggregates(long captureTime, List<AggregatesByType> aggregatesByType) {
            // only capture first non-zero value
            if (totalNanos == 0 && !aggregatesByType.isEmpty()) {
                totalNanos = aggregatesByType.get(0).getOverallAggregate().getTotalDurationNanos();
            }
        }

        @Override
        public void collectGaugeValues(List<GaugeValue> gaugeValues) {}

        @Override
        public void collectTrace(Trace trace) {}

        @Override
        public void log(LogEvent logEvent) {}
    }
}
