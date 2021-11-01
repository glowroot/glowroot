/*
 * Copyright 2015-2019 the original author or authors.
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
package org.glowroot.agent.init;

import org.junit.jupiter.api.Test;

import org.glowroot.agent.impl.TraceCollector;
import org.glowroot.agent.impl.Transaction;
import org.glowroot.agent.init.ImmediateTraceStoreWatcher.ImmediateTraceStoreRunnable;
import org.glowroot.common.util.ScheduledRunnable.TerminateSubsequentExecutionsException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class ImmediateTraceStoreRunnableTest {

    @Test
    public void testOneCallBeyondComplete() {
        // given
        Transaction transaction = mock(Transaction.class);
        when(transaction.isCompleted()).thenReturn(true);
        TraceCollector traceCollector = mock(TraceCollector.class);
        ImmediateTraceStoreRunnable immediateTraceStoreRunnable =
                new ImmediateTraceStoreRunnable(transaction, traceCollector);
        // when
        immediateTraceStoreRunnable.run();
        // then
        verifyNoInteractions(traceCollector);
    }

    @Test
    public void testTwoCallsBeyondComplete() {
        assertThrows(TerminateSubsequentExecutionsException.class, () -> {
            // given
            Transaction transaction = mock(Transaction.class);
            when(transaction.isCompleted()).thenReturn(true);
            TraceCollector traceCollector = mock(TraceCollector.class);
            ImmediateTraceStoreRunnable immediateTraceStoreRunnable =
                    new ImmediateTraceStoreRunnable(transaction, traceCollector);
            // when
            immediateTraceStoreRunnable.run();
            immediateTraceStoreRunnable.run();
        });
    }
}
