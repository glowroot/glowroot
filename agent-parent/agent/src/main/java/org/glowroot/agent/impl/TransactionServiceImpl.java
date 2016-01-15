/*
 * Copyright 2011-2016 the original author or authors.
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

import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.config.AdvancedConfig;
import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.model.ErrorMessage;
import org.glowroot.agent.model.QueryData;
import org.glowroot.agent.model.QueryEntryBase;
import org.glowroot.agent.model.ThreadContextImpl;
import org.glowroot.agent.model.TimerImpl;
import org.glowroot.agent.model.TimerNameImpl;
import org.glowroot.agent.model.TraceEntryImpl;
import org.glowroot.agent.model.Transaction;
import org.glowroot.agent.model.Transaction.CompletionCallback;
import org.glowroot.agent.model.Transaction.OverrideSource;
import org.glowroot.agent.plugin.api.AdvancedService;
import org.glowroot.agent.plugin.api.AsyncQueryEntry;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.QueryEntry;
import org.glowroot.agent.plugin.api.Timer;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.config.ConfigListener;
import org.glowroot.agent.plugin.api.internal.NopTransactionService.NopTraceEntry;
import org.glowroot.agent.plugin.api.util.FastThreadLocal.Holder;
import org.glowroot.agent.util.ThreadAllocatedBytes;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.UsedByGeneratedBytecode;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.glowroot.agent.fat.storage.util.Checkers.castInitialized;

public class TransactionServiceImpl implements AdvancedService, ConfigListener {

    private static final Logger logger = LoggerFactory.getLogger(TransactionServiceImpl.class);

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
    private int maxAggregateQueriesPerQueryType;
    private int maxTraceEntriesPerTransaction;

    public static TransactionServiceImpl create(TransactionRegistry transactionRegistry,
            TransactionCollector transactionCollector, ConfigService configService,
            TimerNameCache timerNameCache, @Nullable ThreadAllocatedBytes threadAllocatedBytes,
            UserProfileScheduler userProfileScheduler, Ticker ticker, Clock clock) {
        TransactionServiceImpl transactionServiceImpl =
                new TransactionServiceImpl(transactionRegistry, transactionCollector, configService,
                        timerNameCache, threadAllocatedBytes, userProfileScheduler, ticker, clock);
        configService.addConfigListener(transactionServiceImpl);
        return transactionServiceImpl;
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
        TransactionServiceHolder.transactionService = castInitialized(this);
    }

    // this is used by OptionalThreadContextImpl
    public TraceEntry startTransaction(String transactionType, String transactionName,
            MessageSupplier messageSupplier, TimerName timerName,
            Holder</*@Nullable*/ ThreadContextImpl> threadContextHolder) {
        if (transactionType == null) {
            logger.error("startTransaction(): argument 'transactionType' must be non-null");
            return NopTraceEntry.INSTANCE;
        }
        if (transactionName == null) {
            logger.error("startTransaction(): argument 'transactionName' must be non-null");
            return NopTraceEntry.INSTANCE;
        }
        if (messageSupplier == null) {
            logger.error("startTransaction(): argument 'messageSupplier' must be non-null");
            return NopTraceEntry.INSTANCE;
        }
        if (timerName == null) {
            logger.error("startTransaction(): argument 'timerName' must be non-null");
            return NopTraceEntry.INSTANCE;
        }
        // ensure visibility of recent configuration updates
        configService.readMemoryBarrier();
        return startTransactionInternal(transactionType, transactionName, messageSupplier,
                timerName, threadContextHolder);
    }

    @Override
    public boolean isInTransaction() {
        return transactionRegistry.getCurrentTransaction() != null;
    }

    private TraceEntry startTransactionInternal(String transactionType, String transactionName,
            MessageSupplier messageSupplier, TimerName timerName,
            Holder</*@Nullable*/ ThreadContextImpl> threadContextHolder) {
        long startTick = ticker.read();
        Transaction transaction = new Transaction(clock.currentTimeMillis(), startTick,
                transactionType, transactionName, messageSupplier, timerName, captureThreadStats,
                maxTraceEntriesPerTransaction, maxAggregateQueriesPerQueryType,
                threadAllocatedBytes, transactionCompletionCallback, ticker, transactionRegistry,
                this, configService, userProfileScheduler, threadContextHolder);
        if (transactionType.equals("Startup")) {
            transaction.setSlowThresholdMillis(0, OverrideSource.STARTUP);
        }
        transactionRegistry.addTransaction(transaction);
        threadContextHolder.set(transaction.getMainThreadContext());
        return transaction.getMainThreadContext().getRootEntry();
    }

    TraceEntry startAuxThreadContextInternal(Transaction transaction,
            TraceEntryImpl parentTraceEntry,
            Holder</*@Nullable*/ ThreadContextImpl> threadContextHolder) {
        long startTick = ticker.read();
        // FIXME implement limit on auxiliary thread context creation for a given transaction
        TimerName auxThreadTimerName = timerNameCache.getAuxThreadTimerName();
        return transaction.startAuxThreadContext(parentTraceEntry, auxThreadTimerName,
                startTick, threadContextHolder, threadAllocatedBytes);
    }

    QueryEntry startTraceEntryInternal(ThreadContextImpl threadContext,
            MessageSupplier messageSupplier, @Nullable String queryType, @Nullable String queryText,
            long queryExecutionCount, TimerName timerName) {
        long startTick = ticker.read();
        if (threadContext.getTransaction().allowAnotherEntry()) {
            TimerImpl timer = startTimer(timerName, startTick, threadContext);
            return threadContext.pushEntry(startTick, messageSupplier, queryType, queryText,
                    queryExecutionCount, timer);
        }
        // split out to separate method so as not to affect inlining budget of common path
        return startDummyTraceEntry(threadContext, timerName, messageSupplier, queryType, queryText,
                queryExecutionCount, startTick);
    }

    private QueryEntry startDummyTraceEntry(ThreadContextImpl threadContext, TimerName timerName,
            MessageSupplier messageSupplier, @Nullable String queryType, @Nullable String queryText,
            long queryExecutionCount, long startTick) {
        // the entry limit has been exceeded for this trace
        QueryData queryData = null;
        if (queryType != null && queryText != null) {
            queryData = threadContext.getOrCreateQueryDataIfPossible(queryType, queryText);
        }
        TimerImpl timer = startTimer(timerName, startTick, threadContext);
        return new DummyTraceEntryOrQuery(timer, null, startTick, threadContext, messageSupplier,
                queryData, queryExecutionCount);
    }

    private TimerImpl startTimer(TimerName timerName, long startTick,
            ThreadContextImpl threadContext) {
        TimerImpl currentTimer = threadContext.getCurrentTimer();
        if (currentTimer == null) {
            // this really shouldn't happen as current timer should be non-null unless transaction
            // has completed
            return TimerImpl.createRootTimer(threadContext, (TimerNameImpl) timerName);
        }
        return currentTimer.startNestedTimer(timerName, startTick);
    }

    @Override
    public void onChange() {
        AdvancedConfig advancedConfig = configService.getAdvancedConfig();
        captureThreadStats = configService.getTransactionConfig().captureThreadStats();
        maxAggregateQueriesPerQueryType = advancedConfig.maxAggregateQueriesPerQueryType();
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
            transactionRegistry.removeTransaction(transaction);
        }
    }

    private class DummyTraceEntryOrQuery extends QueryEntryBase implements AsyncQueryEntry, Timer {

        private final TimerImpl syncTimer;
        private final @Nullable TimerImpl asyncTimer;
        private final long startTick;
        private final ThreadContextImpl threadContext;
        private final MessageSupplier messageSupplier;

        // not volatile, so depends on memory barrier in Transaction for visibility
        private int selfNestingLevel;
        // only used by transaction thread
        private @MonotonicNonNull TimerImpl extendedTimer;

        public DummyTraceEntryOrQuery(TimerImpl syncTimer, @Nullable TimerImpl asyncTimer,
                long startTick, ThreadContextImpl threadContext, MessageSupplier messageSupplier,
                @Nullable QueryData queryData, long queryExecutionCount) {
            super(queryData);
            this.syncTimer = syncTimer;
            this.asyncTimer = asyncTimer;
            this.startTick = startTick;
            this.threadContext = threadContext;
            this.messageSupplier = messageSupplier;
            if (queryData != null) {
                queryData.start(startTick, queryExecutionCount);
            }
        }

        @Override
        public void end() {
            endInternal(ticker.read());
        }

        @Override
        public void endWithStackTrace(long threshold, TimeUnit unit) {
            if (threshold < 0) {
                logger.error("endWithStackTrace(): argument 'threshold' must be non-negative");
                end();
                return;
            }
            endInternal(ticker.read());
        }

        @Override
        public void endWithError(Throwable t) {
            endWithErrorInternal(ErrorMessage.from(t));
        }

        @Override
        public void endWithError(@Nullable String message) {
            endWithErrorInternal(ErrorMessage.from(message));
        }

        @Override
        public void endWithError(@Nullable String message, Throwable t) {
            endWithErrorInternal(ErrorMessage.from(message, t));
        }

        private void endWithErrorInternal(ErrorMessage errorMessage) {
            long endTick = ticker.read();
            endInternal(endTick);
            if (threadContext.getTransaction().allowAnotherErrorEntry()) {
                // entry won't be nested properly, but at least the error will get captured
                org.glowroot.agent.model.TraceEntryImpl entry =
                        threadContext.addErrorEntry(startTick, endTick, messageSupplier,
                                errorMessage);
                if (errorMessage.throwable() == null) {
                    StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
                    // need to strip back a few stack calls:
                    // skip i=0 which is "java.lang.Thread.getStackTrace()"
                    // skip i=1 which is "...DummyTraceEntryOrQuery.endWithErrorInternal()"
                    // skip i=2 which is "...DummyTraceEntryOrQuery.endWithError()"
                    // skip i=3 which is the plugin advice
                    entry.setStackTrace(
                            ImmutableList.copyOf(stackTrace).subList(4, stackTrace.length));
                }
            }
        }

        private void endInternal(long endTick) {
            if (asyncTimer == null) {
                syncTimer.end(endTick);
            } else {
                asyncTimer.end(endTick);
            }
            endQueryData(endTick);
        }

        @Override
        public Timer extend() {
            if (selfNestingLevel++ == 0) {
                long currTick = ticker.read();
                extendedTimer = syncTimer.extend(currTick);
                extendQueryData(currTick);
            }
            return this;
        }

        // this is called for stopping an extension
        @Override
        public void stop() {
            // the timer interface for this class is only expose through return value of extend()
            if (--selfNestingLevel == 0) {
                long stopTick = ticker.read();
                checkNotNull(extendedTimer);
                extendedTimer.end(stopTick);
                endQueryData(stopTick);
            }
        }

        @Override
        public MessageSupplier getMessageSupplier() {
            return messageSupplier;
        }

        @Override
        public void stopSyncTimer() {
            syncTimer.stop();
        }

        @Override
        public Timer extendSyncTimer() {
            return syncTimer.extend();
        }
    }

    @UsedByGeneratedBytecode
    public static class TransactionServiceHolder {

        private static @Nullable TransactionServiceImpl transactionService;

        public static @Nullable TransactionServiceImpl getTransactionService() {
            return transactionService;
        }
    }
}
