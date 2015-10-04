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
package org.glowroot.agent.impl;

import com.google.common.base.Ticker;
import org.junit.Before;
import org.junit.Test;

import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.model.ImmutableTimerNameImpl;
import org.glowroot.agent.model.Transaction;
import org.glowroot.agent.plugin.api.transaction.MessageSupplier;
import org.glowroot.agent.plugin.api.transaction.TimerName;
import org.glowroot.agent.plugin.api.transaction.TraceEntry;
import org.glowroot.agent.util.ThreadAllocatedBytes;
import org.glowroot.common.config.AdvancedConfig;
import org.glowroot.common.config.ImmutableAdvancedConfig;
import org.glowroot.common.config.ImmutableTransactionConfig;
import org.glowroot.common.config.TransactionConfig;
import org.glowroot.common.util.Clock;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TransactionServiceImplMoreDefensiveCheckTest {

    private TransactionServiceImpl transactionService;
    private Transaction mockTransaction;

    @Before
    public void beforeEachTest() {
        TransactionRegistry transactionRegistry = mock(TransactionRegistry.class);
        TransactionCollector transactionCollector = mock(TransactionCollector.class);
        mockTransaction = mock(Transaction.class);
        ConfigService configService = mock(ConfigService.class);
        TransactionConfig transactionConfig = ImmutableTransactionConfig.builder().build();
        AdvancedConfig advancedConfig =
                ImmutableAdvancedConfig.builder().maxTraceEntriesPerTransaction(100).build();
        when(transactionRegistry.getCurrentTransaction()).thenReturn(mockTransaction);
        when(configService.getTransactionConfig()).thenReturn(transactionConfig);
        when(configService.getAdvancedConfig()).thenReturn(advancedConfig);

        TimerNameCache timerNameCache = mock(TimerNameCache.class);
        ThreadAllocatedBytes threadAllocatedBytes = mock(ThreadAllocatedBytes.class);
        UserProfileScheduler userProfileScheduler = mock(UserProfileScheduler.class);
        Ticker ticker = mock(Ticker.class);
        Clock clock = mock(Clock.class);
        transactionService = TransactionServiceImpl.create(transactionRegistry,
                transactionCollector, configService, timerNameCache, threadAllocatedBytes,
                userProfileScheduler, ticker, clock);
    }

    @Test
    public void testEndDummyWithStackTrace() {
        when(mockTransaction.getEntryCount()).thenReturn(100);
        MessageSupplier messageSupplier = mock(MessageSupplier.class);
        TimerName timerName = ImmutableTimerNameImpl.of("test", false);
        TraceEntry traceEntry = transactionService.startTraceEntry(messageSupplier, timerName);
        traceEntry.endWithStackTrace(-1, MILLISECONDS);
    }

    @Test
    public void testEndDummyWithStackTraceGood() {
        when(mockTransaction.getEntryCount()).thenReturn(100);
        MessageSupplier messageSupplier = mock(MessageSupplier.class);
        TimerName timerName = ImmutableTimerNameImpl.of("test", false);
        TraceEntry traceEntry = transactionService.startTraceEntry(messageSupplier, timerName);
        traceEntry.endWithStackTrace(1, MILLISECONDS);
    }

    @Test
    public void testEndDummyWithErrorGood() {
        when(mockTransaction.getEntryCount()).thenReturn(100);
        MessageSupplier messageSupplier = mock(MessageSupplier.class);
        TimerName timerName = ImmutableTimerNameImpl.of("test", false);
        TraceEntry traceEntry = transactionService.startTraceEntry(messageSupplier, timerName);
        traceEntry.endWithError("");
    }
}
