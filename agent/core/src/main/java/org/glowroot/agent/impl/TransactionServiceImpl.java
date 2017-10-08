/*
 * Copyright 2011-2017 the original author or authors.
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

import javax.annotation.Nullable;

import com.google.common.base.Ticker;

import org.glowroot.agent.config.AdvancedConfig;
import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.impl.Transaction.CompletionCallback;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.ThreadContext.ServletRequestInfo;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.config.ConfigListener;
import org.glowroot.agent.util.IterableWithSelfRemovableEntries.SelfRemovableEntry;
import org.glowroot.agent.util.ThreadAllocatedBytes;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.UsedByGeneratedBytecode;

public class TransactionServiceImpl implements ConfigListener {

    private final TransactionRegistry transactionRegistry;
    private final TransactionCollector transactionCollector;
    private final ConfigService configService;
    private final TimerNameCache timerNameCache;
    private final @Nullable ThreadAllocatedBytes threadAllocatedBytes;
    private final UserProfileScheduler userProfileScheduler;
    private final Clock clock;
    private final Ticker ticker;

    private final TransactionCompletionCallback transactionCompletionCallback =
            new TransactionCompletionCallback();

    // cache for fast read access
    // visibility is provided by memoryBarrier below
    private boolean captureThreadStats;
    private int maxAggregateQueriesPerType;
    private int maxAggregateServiceCallsPerType;
    private int maxTraceEntriesPerTransaction;

    public static void createSingleton(TransactionRegistry transactionRegistry,
            TransactionCollector transactionCollector, ConfigService configService,
            TimerNameCache timerNameCache, @Nullable ThreadAllocatedBytes threadAllocatedBytes,
            UserProfileScheduler userProfileScheduler, Ticker ticker, Clock clock) {
        TransactionServiceImpl transactionServiceImpl =
                new TransactionServiceImpl(transactionRegistry, transactionCollector, configService,
                        timerNameCache, threadAllocatedBytes, userProfileScheduler, ticker, clock);
        configService.addConfigListener(transactionServiceImpl);
        TransactionServiceHolder.transactionService = transactionServiceImpl;
    }

    private TransactionServiceImpl(TransactionRegistry transactionRegistry,
            TransactionCollector transactionCollector, ConfigService configService,
            TimerNameCache timerNameCache, @Nullable ThreadAllocatedBytes threadAllocatedBytes,
            UserProfileScheduler userProfileScheduler, Ticker ticker, Clock clock) {
        this.transactionRegistry = transactionRegistry;
        this.transactionCollector = transactionCollector;
        this.configService = configService;
        this.timerNameCache = timerNameCache;
        this.threadAllocatedBytes = threadAllocatedBytes;
        this.userProfileScheduler = userProfileScheduler;
        this.clock = clock;
        this.ticker = ticker;
    }

    TraceEntryImpl startTransaction(String transactionType, String transactionName,
            MessageSupplier messageSupplier, TimerName timerName,
            ThreadContextThreadLocal.Holder threadContextHolder) {
        // ensure visibility of recent configuration updates
        configService.readMemoryBarrier();
        long startTick = ticker.read();
        Transaction transaction = new Transaction(clock.currentTimeMillis(), startTick,
                transactionType, transactionName, messageSupplier, timerName, captureThreadStats,
                maxTraceEntriesPerTransaction, maxAggregateQueriesPerType,
                maxAggregateServiceCallsPerType, threadAllocatedBytes,
                transactionCompletionCallback, ticker, transactionRegistry, this, configService,
                userProfileScheduler, threadContextHolder);
        SelfRemovableEntry transactionEntry = transactionRegistry.addTransaction(transaction);
        transaction.setTransactionEntry(transactionEntry);
        threadContextHolder.set(transaction.getMainThreadContext());
        return transaction.getMainThreadContext().getRootEntry();
    }

    @Nullable
    ThreadContextImpl startAuxThreadContextInternal(Transaction transaction,
            @Nullable TraceEntryImpl parentTraceEntry,
            @Nullable TraceEntryImpl parentThreadContextPriorEntry,
            @Nullable ServletRequestInfo servletRequestInfo,
            ThreadContextThreadLocal.Holder threadContextHolder) {
        long startTick = ticker.read();
        TimerName auxThreadTimerName = timerNameCache.getAuxThreadTimerName();
        return transaction.startAuxThreadContext(parentTraceEntry, parentThreadContextPriorEntry,
                auxThreadTimerName, startTick, threadContextHolder, servletRequestInfo,
                threadAllocatedBytes);
    }

    @Override
    public void onChange() {
        AdvancedConfig advancedConfig = configService.getAdvancedConfig();
        captureThreadStats = configService.getTransactionConfig().captureThreadStats();
        maxAggregateQueriesPerType = advancedConfig.maxAggregateQueriesPerType();
        maxAggregateServiceCallsPerType = advancedConfig.maxAggregateServiceCallsPerType();
        maxTraceEntriesPerTransaction = advancedConfig.maxTraceEntriesPerTransaction();
    }

    private class TransactionCompletionCallback implements CompletionCallback {

        @Override
        public void completed(Transaction transaction) {
            // send to trace collector before removing from trace registry so that trace
            // collector can cover the gap
            // (via TransactionCollectorImpl.getPendingCompleteTraces())
            // between removing the trace from the registry and storing it
            transactionCollector.onCompletedTransaction(transaction);
        }
    }

    @UsedByGeneratedBytecode
    public static class TransactionServiceHolder {

        private static @Nullable TransactionServiceImpl transactionService;

        private TransactionServiceHolder() {}

        public static @Nullable TransactionServiceImpl getTransactionService() {
            return transactionService;
        }
    }
}
