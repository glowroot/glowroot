/*
 * Copyright 2011-2018 the original author or authors.
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
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import org.glowroot.agent.bytecode.api.ThreadContextThreadLocal;
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

public class TransactionService implements ConfigListener {

    private final TransactionRegistry transactionRegistry;
    private final ConfigService configService;
    private final TimerNameCache timerNameCache;
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

    // intentionally not volatile for small optimization
    private @MonotonicNonNull TransactionCollector transactionCollector;
    // intentionally not volatile for small optimization
    private @Nullable ThreadAllocatedBytes threadAllocatedBytes;

    public static TransactionService create(TransactionRegistry transactionRegistry,
            ConfigService configService, TimerNameCache timerNameCache,
            UserProfileScheduler userProfileScheduler, Ticker ticker, Clock clock) {
        TransactionService transactionService = new TransactionService(transactionRegistry,
                configService, timerNameCache, userProfileScheduler, ticker, clock);
        configService.addConfigListener(transactionService);
        return transactionService;
    }

    private TransactionService(TransactionRegistry transactionRegistry, ConfigService configService,
            TimerNameCache timerNameCache, UserProfileScheduler userProfileScheduler, Ticker ticker,
            Clock clock) {
        this.transactionRegistry = transactionRegistry;
        this.configService = configService;
        this.timerNameCache = timerNameCache;
        this.userProfileScheduler = userProfileScheduler;
        this.clock = clock;
        this.ticker = ticker;
    }

    public void setTransactionCollector(TransactionCollector transactionCollector) {
        this.transactionCollector = transactionCollector;
    }

    public void setThreadAllocatedBytes(@Nullable ThreadAllocatedBytes threadAllocatedBytes) {
        this.threadAllocatedBytes = threadAllocatedBytes;
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
            if (transactionCollector != null) {
                // send to trace collector before removing from trace registry so that trace
                // collector can cover the gap
                // (via TransactionCollectorImpl.getPendingCompleteTraces())
                // between removing the trace from the registry and storing it
                transactionCollector.onCompletedTransaction(transaction);
            }
        }
    }
}
