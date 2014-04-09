/*
 * Copyright 2014 the original author or authors.
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
package org.glowroot.collector;

import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import org.glowroot.api.MetricName;
import org.glowroot.api.weaving.Pointcut;
import org.glowroot.common.Clock;
import org.glowroot.trace.model.Metric;
import org.glowroot.trace.model.Trace;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyMapOf;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class AggregatorTest {

    @Test
    public void shouldFlushWithNoTraces() throws InterruptedException {
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
        AggregateRepository aggregateRepository = mock(AggregateRepository.class);
        new Aggregator(scheduledExecutorService, aggregateRepository, Clock.systemClock(), 1);
        // when
        Thread.sleep(2100);
        // then
        verify(aggregateRepository).store(any(Long.class), any(AggregateBuilder.class),
                anyMapOf(String.class, AggregateBuilder.class), any(AggregateBuilder.class),
                anyMapOf(String.class, AggregateBuilder.class));
    }

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
        MockAggregateRepository aggregateRepository = new MockAggregateRepository();
        Aggregator aggregator = new Aggregator(scheduledExecutorService, aggregateRepository,
                Clock.systemClock(), 1);

        Trace trace = mock(Trace.class);
        Metric metric = mock(Metric.class);
        when(metric.getMetricName()).thenReturn(MetricName.get(OnlyForThePointcutMetricName.class));
        when(trace.getDuration()).thenReturn(MILLISECONDS.toNanos(123));
        when(trace.getRootMetric()).thenReturn(metric);
        // when
        int count = 0;
        long firstCaptureTime = aggregator.add(trace, false);
        long aggregateCaptureTime = (long) Math.ceil(firstCaptureTime / 1000.0) * 1000;
        while (true) {
            long captureTime = aggregator.add(trace, false);
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
            if (aggregateRepository.getTotalMicros() > 0) {
                break;
            }
        }
        assertThat(aggregateRepository.getTotalMicros()).isEqualTo(count * 123 * 1000);
        aggregator.close();
    }

    private static class MockAggregateRepository implements AggregateRepository {

        private long totalMicros;

        @Override
        public void store(long captureTime, AggregateBuilder overallAggregate,
                Map<String, AggregateBuilder> transactionAggregates,
                AggregateBuilder bgOverallAggregate,
                Map<String, AggregateBuilder> bgTransactionAggregates) {
            // only capture first non-zero value
            if (totalMicros == 0) {
                totalMicros = overallAggregate.getTotalMicros();
            }
        }

        private long getTotalMicros() {
            return totalMicros;
        }
    }

    @Pointcut(typeName = "", methodName = "", metricName = "glowroot weaving")
    private static class OnlyForThePointcutMetricName {}
}
