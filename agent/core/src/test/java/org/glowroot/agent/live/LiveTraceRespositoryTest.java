/*
 * Copyright 2015-2017 the original author or authors.
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
package org.glowroot.agent.live;

import com.google.common.base.Ticker;
import org.junit.Before;
import org.junit.Test;

import org.glowroot.agent.impl.Transaction;
import org.glowroot.agent.impl.TransactionCollector;
import org.glowroot.agent.impl.TransactionRegistry;
import org.glowroot.common.util.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class LiveTraceRespositoryTest {

    private LiveTraceRepositoryImpl liveTraceRepository;
    private TransactionCollector transactionCollector;

    @Before
    public void beforeEachTest() {
        TransactionRegistry transactionRegistry = mock(TransactionRegistry.class);
        transactionCollector = mock(TransactionCollector.class);
        Clock clock = mock(Clock.class);
        Ticker ticker = mock(Ticker.class);
        liveTraceRepository = new LiveTraceRepositoryImpl(transactionRegistry, transactionCollector,
                clock, ticker);
    }

    @Test
    public void testShouldNotStore() throws Exception {
        // given
        Transaction transaction = mock(Transaction.class);
        when(transactionCollector.shouldStoreSlow(any(Transaction.class))).thenReturn(false);
        when(transaction.getTransactionType()).thenReturn("tt");
        when(transaction.getTransactionName()).thenReturn("tn");
        // when
        boolean matches = liveTraceRepository.matchesActive(transaction, "tt", "tn");
        // then
        assertThat(matches).isFalse();
    }

    @Test
    public void testShouldStoreButDifferentTransactionType() throws Exception {
        // given
        Transaction transaction = mock(Transaction.class);
        when(transactionCollector.shouldStoreSlow(any(Transaction.class))).thenReturn(true);
        when(transaction.getTransactionType()).thenReturn("tt");
        when(transaction.getTransactionName()).thenReturn("tn");
        // when
        boolean matches = liveTraceRepository.matchesActive(transaction, "uu", "tn");
        // then
        assertThat(matches).isFalse();
    }

    @Test
    public void testShouldStoreAndSameTransactionTypeButNullTransactionName() throws Exception {
        // given
        Transaction transaction = mock(Transaction.class);
        when(transactionCollector.shouldStoreSlow(any(Transaction.class))).thenReturn(true);
        when(transaction.getTransactionType()).thenReturn("tt");
        when(transaction.getTransactionName()).thenReturn("tn");
        // when
        boolean matches = liveTraceRepository.matchesActive(transaction, "tt", "un");
        // then
        assertThat(matches).isFalse();
    }

    @Test
    public void testShouldStoreAndSameTransactionTypeButDiffTransactionName() throws Exception {
        // given
        Transaction transaction = mock(Transaction.class);
        when(transactionCollector.shouldStoreSlow(any(Transaction.class))).thenReturn(true);
        when(transaction.getTransactionType()).thenReturn("tt");
        when(transaction.getTransactionName()).thenReturn("tn");
        // when
        boolean matches = liveTraceRepository.matchesActive(transaction, "tt", "un");
        // then
        assertThat(matches).isFalse();
    }

    @Test
    public void testShouldMatch() throws Exception {
        // given
        Transaction transaction = mock(Transaction.class);
        when(transactionCollector.shouldStoreSlow(any(Transaction.class))).thenReturn(true);
        when(transaction.getTransactionType()).thenReturn("tt");
        when(transaction.getTransactionName()).thenReturn("tn");
        // when
        boolean matches = liveTraceRepository.matchesActive(transaction, "tt", "tn");
        // then
        assertThat(matches).isTrue();
    }
}
