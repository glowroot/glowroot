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

import org.glowroot.common.Clock;
import org.glowroot.trace.model.Trace;
import org.glowroot.trace.model.TraceMetric;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class AggregatorTest {

    @Test
    public void shouldNotFlushWithNoTraces() throws InterruptedException {
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
        TransactionPointRepository transactionPointRepository =
                mock(TransactionPointRepository.class);
        new TransactionCollector(scheduledExecutorService, transactionPointRepository,
                Clock.systemClock(), 1);
        // when
        Thread.sleep(2100);
        // then
        verifyZeroInteractions(transactionPointRepository);
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
        MockTransactionPointRepository transactionPointRepository =
                new MockTransactionPointRepository();
        TransactionCollector transactionCollector = new TransactionCollector(
                scheduledExecutorService, transactionPointRepository, Clock.systemClock(), 1);

        Trace trace = mock(Trace.class);
        TraceMetric metric = mock(TraceMetric.class);
        when(metric.getName()).thenReturn("test 123");
        when(trace.getDuration()).thenReturn(MILLISECONDS.toNanos(123));
        when(trace.getRootMetric()).thenReturn(metric);
        // when
        int count = 0;
        long firstCaptureTime = transactionCollector.add(trace, false);
        long aggregateCaptureTime = (long) Math.ceil(firstCaptureTime / 1000.0) * 1000;
        while (true) {
            long captureTime = transactionCollector.add(trace, false);
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
            if (transactionPointRepository.getTotalMicros() > 0) {
                break;
            }
        }
        assertThat(transactionPointRepository.getTotalMicros()).isEqualTo(count * 123 * 1000);
        transactionCollector.close();
    }

    private static class MockTransactionPointRepository implements TransactionPointRepository {

        // volatile needed for visibility from other thread
        private volatile long totalMicros;

        @Override
        public void store(String transactionType, TransactionPoint overall,
                Map<String, TransactionPoint> transactions) {
            // only capture first non-zero value
            if (totalMicros == 0) {
                totalMicros = overall.getTotalMicros();
            }
        }

        private long getTotalMicros() {
            return totalMicros;
        }
    }
}
