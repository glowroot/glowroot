/*
 * Copyright 2015-2016 the original author or authors.
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

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import org.glowroot.agent.impl.Transaction;
import org.glowroot.agent.impl.TransactionCollector;
import org.glowroot.agent.init.ImmediateTraceStoreWatcher.ImmediateTraceStoreRunnable;
import org.glowroot.common.util.ScheduledRunnable.TerminateSubsequentExecutionsException;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

public class ImmediateTraceStoreRunnableTest {

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Test
    public void testOneCallBeyondComplete() {
        // given
        Transaction transaction = mock(Transaction.class);
        when(transaction.isCompleted()).thenReturn(true);
        TransactionCollector transactionCollector = mock(TransactionCollector.class);
        ImmediateTraceStoreRunnable immediateTraceStoreRunnable =
                new ImmediateTraceStoreRunnable(transaction, transactionCollector);
        // when
        immediateTraceStoreRunnable.run();
        // then
        verifyZeroInteractions(transactionCollector);
    }

    @Test
    public void testTwoCallsBeyondComplete() {
        // given
        thrown.expect(TerminateSubsequentExecutionsException.class);
        Transaction transaction = mock(Transaction.class);
        when(transaction.isCompleted()).thenReturn(true);
        TransactionCollector transactionCollector = mock(TransactionCollector.class);
        ImmediateTraceStoreRunnable immediateTraceStoreRunnable =
                new ImmediateTraceStoreRunnable(transaction, transactionCollector);
        // when
        immediateTraceStoreRunnable.run();
        immediateTraceStoreRunnable.run();
    }
}
