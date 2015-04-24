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
package org.glowroot.local.ui;

import org.junit.Before;
import org.junit.Test;

import org.glowroot.collector.TransactionCollectorImpl;
import org.glowroot.common.Clock;
import org.glowroot.local.store.TraceDao;
import org.glowroot.transaction.TransactionRegistry;
import org.glowroot.transaction.model.Transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TransactionJsonServiceTest {

    private TransactionJsonService transactionJsonService;
    private TransactionCollectorImpl transactionCollector;

    @Before
    public void beforeEachTest() {
        TransactionCommonService transactionCommonService = mock(TransactionCommonService.class);
        TraceDao traceDao = mock(TraceDao.class);
        TransactionRegistry transactionRegistry = mock(TransactionRegistry.class);
        transactionCollector = mock(TransactionCollectorImpl.class);
        Clock clock = mock(Clock.class);
        transactionJsonService = new TransactionJsonService(transactionCommonService, traceDao,
                transactionRegistry, transactionCollector, clock, 60, 300);
    }

    @Test
    public void testShouldNotStore() throws Exception {
        // given
        Transaction transaction = mock(Transaction.class);
        when(transactionCollector.shouldStore(any(Transaction.class))).thenReturn(false);
        when(transaction.getTransactionType()).thenReturn("tt");
        when(transaction.getTransactionName()).thenReturn("tn");
        TransactionDataRequest request = TransactionDataRequest.builder()
                .transactionType("tt")
                .transactionName("tn")
                .from(0)
                .to(100)
                .build();
        // when
        boolean matches = transactionJsonService.matchesActive(transaction, request);
        // then
        assertThat(matches).isFalse();
    }

    @Test
    public void testShouldStoreButDifferentTransactionType() throws Exception {
        // given
        Transaction transaction = mock(Transaction.class);
        when(transactionCollector.shouldStore(any(Transaction.class))).thenReturn(true);
        when(transaction.getTransactionType()).thenReturn("tt");
        when(transaction.getTransactionName()).thenReturn("tn");
        TransactionDataRequest request = TransactionDataRequest.builder()
                .transactionType("uu")
                .transactionName("tn")
                .from(0)
                .to(100)
                .build();
        // when
        boolean matches = transactionJsonService.matchesActive(transaction, request);
        // then
        assertThat(matches).isFalse();
    }

    @Test
    public void testShouldStoreAndSameTransactionTypeButNullTransactionName() throws Exception {
        // given
        Transaction transaction = mock(Transaction.class);
        when(transactionCollector.shouldStore(any(Transaction.class))).thenReturn(true);
        when(transaction.getTransactionType()).thenReturn("tt");
        when(transaction.getTransactionName()).thenReturn("tn");
        TransactionDataRequest request = TransactionDataRequest.builder()
                .transactionType("tt")
                .transactionName("un")
                .from(0)
                .to(100)
                .build();
        // when
        boolean matches = transactionJsonService.matchesActive(transaction, request);
        // then
        assertThat(matches).isFalse();
    }

    @Test
    public void testShouldStoreAndSameTransactionTypeButDiffTransactionName() throws Exception {
        // given
        Transaction transaction = mock(Transaction.class);
        when(transactionCollector.shouldStore(any(Transaction.class))).thenReturn(true);
        when(transaction.getTransactionType()).thenReturn("tt");
        when(transaction.getTransactionName()).thenReturn("tn");
        TransactionDataRequest request = TransactionDataRequest.builder()
                .transactionType("tt")
                .transactionName("un")
                .from(0)
                .to(100)
                .build();
        // when
        boolean matches = transactionJsonService.matchesActive(transaction, request);
        // then
        assertThat(matches).isFalse();
    }

    @Test
    public void testShouldMatch() throws Exception {
        // given
        Transaction transaction = mock(Transaction.class);
        when(transactionCollector.shouldStore(any(Transaction.class))).thenReturn(true);
        when(transaction.getTransactionType()).thenReturn("tt");
        when(transaction.getTransactionName()).thenReturn("tn");
        TransactionDataRequest request = TransactionDataRequest.builder()
                .transactionType("tt")
                .transactionName("tn")
                .from(0)
                .to(100)
                .build();
        // when
        boolean matches = transactionJsonService.matchesActive(transaction, request);
        // then
        assertThat(matches).isTrue();
    }
}
