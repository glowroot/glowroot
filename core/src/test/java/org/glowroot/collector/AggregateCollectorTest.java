/*
 * Copyright 2014-2015 the original author or authors.
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

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import org.glowroot.common.Clock;
import org.glowroot.config.AdvancedConfig;
import org.glowroot.config.ConfigService;
import org.glowroot.transaction.model.QueryData;
import org.glowroot.transaction.model.TimerImpl;
import org.glowroot.transaction.model.Transaction;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AggregateCollectorTest {

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
        ConfigService configService = mock(ConfigService.class);
        when(configService.getAdvancedConfig()).thenReturn(AdvancedConfig.builder().build());
        AggregateCollector aggregateCollector = new AggregateCollector(scheduledExecutorService,
                aggregateRepository, configService, 1000, Clock.systemClock());

        Transaction transaction = mock(Transaction.class);
        TimerImpl timer = mock(TimerImpl.class);
        when(timer.getName()).thenReturn("test 123");
        when(timer.getNestedTimers()).thenReturn(ImmutableList.<TimerImpl>of());
        when(transaction.getTransactionType()).thenReturn("a type");
        when(transaction.getTransactionName()).thenReturn("a name");
        when(transaction.getDuration()).thenReturn(MILLISECONDS.toNanos(123));
        when(transaction.getRootTimer()).thenReturn(timer);
        when(transaction.getQueries()).thenReturn(ImmutableList.<QueryData>of());
        // when
        int count = 0;
        long firstCaptureTime = aggregateCollector.add(transaction);
        long aggregateCaptureTime = (long) Math.ceil(firstCaptureTime / 1000.0) * 1000;
        while (true) {
            long captureTime = aggregateCollector.add(transaction);
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
        aggregateCollector.close();
    }

    private static class MockAggregateRepository implements AggregateRepository {

        // volatile needed for visibility from other thread
        private volatile long totalMicros;

        @Override
        public void store(List<Aggregate> overallAggregates,
                List<Aggregate> transactionAggregates, long captureTime) {
            // only capture first non-zero value
            if (totalMicros == 0 && !overallAggregates.isEmpty()) {
                totalMicros = overallAggregates.get(0).totalMicros();
            }
        }

        private long getTotalMicros() {
            return totalMicros;
        }
    }
}
