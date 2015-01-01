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
package org.glowroot;

import org.junit.Test;

import org.glowroot.GlowrootModule.TransactionCollectorProxy;
import org.glowroot.transaction.TransactionCollector;
import org.glowroot.transaction.model.Transaction;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

public class TransactionCollectorProxyTest {

    @Test
    public void testOnCompletedTransaction() {
        // given
        TransactionCollector transactionCollector = mock(TransactionCollector.class);
        Transaction transaction = mock(Transaction.class);
        TransactionCollectorProxy proxy = new TransactionCollectorProxy();
        proxy.setInstance(transactionCollector);
        // when
        proxy.onCompletedTransaction(transaction);
        // then
        verify(transactionCollector).onCompletedTransaction(transaction);
        verifyNoMoreInteractions(transactionCollector);
    }

    @Test
    public void testStorePartialTrace() {
        // given
        TransactionCollector transactionCollector = mock(TransactionCollector.class);
        Transaction transaction = mock(Transaction.class);
        TransactionCollectorProxy proxy = new TransactionCollectorProxy();
        proxy.setInstance(transactionCollector);
        // when
        proxy.storePartialTrace(transaction);
        // then
        verify(transactionCollector).storePartialTrace(transaction);
        verifyNoMoreInteractions(transactionCollector);
    }

    @Test
    public void testOnCompletedTransactionWithNullDelegate() {
        // given
        Transaction transaction = mock(Transaction.class);
        TransactionCollectorProxy proxy = new TransactionCollectorProxy();
        // when
        proxy.onCompletedTransaction(transaction);
        // then
    }

    @Test
    public void testStorePartialTraceWithNullDelegate() {
        // given
        Transaction transaction = mock(Transaction.class);
        TransactionCollectorProxy proxy = new TransactionCollectorProxy();
        // when
        proxy.storePartialTrace(transaction);
        // then
    }
}
