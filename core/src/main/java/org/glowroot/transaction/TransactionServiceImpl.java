/*
 * Copyright 2011-2015 the original author or authors.
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
package org.glowroot.transaction;

import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import com.google.common.base.Strings;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.Clock;
import org.glowroot.config.AdvancedConfig;
import org.glowroot.config.ConfigService;
import org.glowroot.config.PluginConfig;
import org.glowroot.jvm.ThreadAllocatedBytes;
import org.glowroot.plugin.api.config.ConfigListener;
import org.glowroot.plugin.api.internal.NopTransactionService.NopQueryEntry;
import org.glowroot.plugin.api.internal.NopTransactionService.NopTimer;
import org.glowroot.plugin.api.internal.NopTransactionService.NopTraceEntry;
import org.glowroot.plugin.api.transaction.ErrorMessage;
import org.glowroot.plugin.api.transaction.MessageSupplier;
import org.glowroot.plugin.api.transaction.QueryEntry;
import org.glowroot.plugin.api.transaction.Timer;
import org.glowroot.plugin.api.transaction.TimerName;
import org.glowroot.plugin.api.transaction.TraceEntry;
import org.glowroot.plugin.api.transaction.TransactionService;
import org.glowroot.plugin.api.transaction.internal.ReadableErrorMessage;
import org.glowroot.transaction.model.QueryData;
import org.glowroot.transaction.model.TimerImpl;
import org.glowroot.transaction.model.TimerNameImpl;
import org.glowroot.transaction.model.Transaction;
import org.glowroot.transaction.model.Transaction.CompletionCallback;

import static com.google.common.base.Preconditions.checkNotNull;

class TransactionServiceImpl implements TransactionService, ConfigListener {

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
    private boolean captureThreadInfo;
    private boolean captureGcInfo;
    private int maxAggregateQueriesPerQueryType;
    private int maxTraceEntriesPerTransaction;
    private @MonotonicNonNull PluginConfig pluginConfig;

    static TransactionServiceImpl create(TransactionRegistry transactionRegistry,
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
    }

    @Override
    public TimerName getTimerName(Class<?> adviceClass) {
        return timerNameCache.getName(adviceClass);
    }

    @Override
    public TraceEntry startTransaction(String transactionType, String transactionName,
            MessageSupplier messageSupplier, TimerName timerName) {
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
                timerName);
    }

    @Override
    public TraceEntry startTraceEntry(MessageSupplier messageSupplier, TimerName timerName) {
        if (messageSupplier == null) {
            logger.error("startTraceEntry(): argument 'messageSupplier' must be non-null");
            return NopTraceEntry.INSTANCE;
        }
        if (timerName == null) {
            logger.error("startTraceEntry(): argument 'timerName' must be non-null");
            return NopTraceEntry.INSTANCE;
        }
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction == null) {
            return NopTraceEntry.INSTANCE;
        }
        return startTraceEntryInternal(transaction, messageSupplier, null, null, 0, timerName);
    }

    @Override
    public QueryEntry startQueryEntry(String queryType, String queryText,
            MessageSupplier messageSupplier, TimerName timerName) {
        return startQueryEntry(queryType, queryText, 1, messageSupplier, timerName);
    }

    @Override
    public QueryEntry startQueryEntry(String queryType, String queryText, long queryExecutionCount,
            MessageSupplier messageSupplier, TimerName timerName) {
        if (queryType == null) {
            logger.error("startQuery(): argument 'queryType' must be non-null");
            return NopQueryEntry.INSTANCE;
        }
        if (queryText == null) {
            logger.error("startQuery(): argument 'queryText' must be non-null");
            return NopQueryEntry.INSTANCE;
        }
        if (messageSupplier == null) {
            logger.error("startQuery(): argument 'messageSupplier' must be non-null");
            return NopQueryEntry.INSTANCE;
        }
        if (timerName == null) {
            logger.error("startQuery(): argument 'timerName' must be non-null");
            return NopQueryEntry.INSTANCE;
        }
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction == null) {
            return NopQueryEntry.INSTANCE;
        }
        return startTraceEntryInternal(transaction, messageSupplier, queryType, queryText,
                queryExecutionCount, timerName);
    }

    @Override
    public Timer startTimer(TimerName timerName) {
        if (timerName == null) {
            logger.error("startTimer(): argument 'timerName' must be non-null");
            return NopTimer.INSTANCE;
        }
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction == null) {
            return NopTimer.INSTANCE;
        }
        TimerImpl currentTimer = transaction.getCurrentTimer();
        if (currentTimer == null) {
            return NopTimer.INSTANCE;
        }
        return currentTimer.startNestedTimer(timerName);
    }

    @Override
    public void addTraceEntry(ErrorMessage errorMessage) {
        if (errorMessage == null) {
            logger.error("addTraceEntry(): argument 'errorMessage' must be non-null");
            return;
        }
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        // use higher entry limit when adding errors, but still need some kind of cap
        if (transaction != null
                && transaction.getEntryCount() < 2 * maxTraceEntriesPerTransaction) {
            long currTick = ticker.read();
            org.glowroot.transaction.model.TraceEntryImpl entry =
                    transaction.addEntry(currTick, currTick, null, errorMessage, true);
            if (((ReadableErrorMessage) errorMessage).getThrowable() == null) {
                StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
                // need to strip back a few stack calls:
                // skip i=0 which is "java.lang.Thread.getStackTrace()"
                // skip i=1 which is "...TransactionServiceImpl.addTraceEntry()"
                // skip i=2 which is the plugin advice
                entry.setStackTrace(ImmutableList.copyOf(stackTrace).subList(3, stackTrace.length));
            }
        }
    }

    @Override
    public void setTransactionType(@Nullable String transactionType) {
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction != null) {
            transaction.setTransactionType(transactionType);
        }
    }

    @Override
    public void setTransactionName(@Nullable String transactionName) {
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction != null) {
            transaction.setTransactionName(transactionName);
        }
    }

    @Override
    public void setTransactionError(ErrorMessage errorMessage) {
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction != null) {
            transaction.setError(errorMessage);
        }
    }

    @Override
    public void setTransactionUser(@Nullable String user) {
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction != null && !Strings.isNullOrEmpty(user)) {
            transaction.setUser(user);
            if (transaction.getUserProfileRunnable() == null) {
                userProfileScheduler.maybeScheduleUserProfiling(transaction, user);
            }
        }
    }

    @Override
    public void addTransactionCustomAttribute(String name, @Nullable String value) {
        if (name == null) {
            logger.error("addTransactionCustomAttribute(): argument 'name' must be non-null");
            return;
        }
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction != null) {
            transaction.addCustomAttribute(name, value);
        }
    }

    @Override
    public void setTransactionSlowThreshold(long threshold, TimeUnit unit) {
        if (threshold < 0) {
            logger.error(
                    "setTransactionSlowThreshold(): argument 'threshold' must be non-negative");
            return;
        }
        if (unit == null) {
            logger.error("setTransactionSlowThreshold(): argument 'unit' must be non-null");
            return;
        }
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction != null) {
            int thresholdMillis = Ints.saturatedCast(unit.toMillis(threshold));
            transaction.setSlowThresholdMillisOverride(thresholdMillis);
        }
    }

    @Override
    public boolean isInTransaction() {
        return transactionRegistry.getCurrentTransaction() != null;
    }

    private TraceEntry startTransactionInternal(String transactionType, String transactionName,
            MessageSupplier messageSupplier, TimerName timerName) {
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction == null) {
            long startTick = ticker.read();
            transaction = new Transaction(clock.currentTimeMillis(), transactionType,
                    transactionName, messageSupplier, timerName, startTick, captureThreadInfo,
                    captureGcInfo, maxAggregateQueriesPerQueryType, threadAllocatedBytes,
                    transactionCompletionCallback, ticker);
            transactionRegistry.addTransaction(transaction);
            return transaction.getRootEntry();
        } else {
            return startTraceEntryInternal(transaction, messageSupplier, null, null, 0, timerName);
        }
    }

    private QueryEntry startTraceEntryInternal(Transaction transaction,
            MessageSupplier messageSupplier, @Nullable String queryType, @Nullable String queryText,
            long queryExecutionCount, TimerName timerName) {
        long startTick = ticker.read();
        if (transaction.getEntryCount() < maxTraceEntriesPerTransaction) {
            TimerImpl timer = startTimer(timerName, startTick, transaction);
            return transaction.pushEntry(startTick, messageSupplier, queryType, queryText,
                    queryExecutionCount, timer);
        }
        // split out to separate method so as not to affect inlining budget of common path
        return startDummyTraceEntry(transaction, timerName, messageSupplier, queryType, queryText,
                queryExecutionCount, startTick);
    }

    private QueryEntry startDummyTraceEntry(Transaction transaction, TimerName timerName,
            MessageSupplier messageSupplier, @Nullable String queryType, @Nullable String queryText,
            long queryExecutionCount, long startTick) {
        // the entry limit has been exceeded for this trace
        QueryData queryData = null;
        if (queryType != null && queryText != null) {
            queryData = transaction.getOrCreateQueryDataIfPossible(queryType, queryText);
        }
        transaction.addEntryLimitExceededMarkerIfNeeded();
        TimerImpl timer = startTimer(timerName, startTick, transaction);
        return new DummyTraceEntryOrQuery(timer, startTick, transaction, messageSupplier, queryData,
                queryExecutionCount);
    }

    private TimerImpl startTimer(TimerName timerName, long startTick, Transaction transaction) {
        TimerImpl currentTimer = transaction.getCurrentTimer();
        if (currentTimer == null) {
            // this really shouldn't happen as current timer should be non-null unless transaction
            // has completed
            return TimerImpl.createRootTimer(transaction, (TimerNameImpl) timerName);
        }
        return currentTimer.startNestedTimer(timerName, startTick);
    }

    @Override
    public void onChange() {
        AdvancedConfig advancedConfig = configService.getAdvancedConfig();
        maxAggregateQueriesPerQueryType = advancedConfig.maxAggregateQueriesPerQueryType();
        maxTraceEntriesPerTransaction = advancedConfig.maxTraceEntriesPerTransaction();
        captureThreadInfo = advancedConfig.captureThreadInfo();
        captureGcInfo = advancedConfig.captureGcInfo();
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

    private class DummyTraceEntryOrQuery implements QueryEntry, Timer {

        private final TimerImpl timer;
        private final long startTick;
        private final Transaction transaction;
        private final MessageSupplier messageSupplier;

        // not volatile, so depends on memory barrier in Transaction for visibility
        private int selfNestingLevel;
        // only used by transaction thread
        private @MonotonicNonNull TimerImpl extendedTimer;

        // queryData, currRow and maxRow are only used by query entries
        private final @Nullable QueryData queryData;
        // row numbers start at 1
        private long currRow = -1;
        private long maxRow;

        public DummyTraceEntryOrQuery(TimerImpl timer, long startTick, Transaction transaction,
                MessageSupplier messageSupplier, @Nullable QueryData queryData,
                long queryExecutionCount) {
            this.timer = timer;
            this.startTick = startTick;
            this.transaction = transaction;
            this.messageSupplier = messageSupplier;
            this.queryData = queryData;
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
        public void endWithError(ErrorMessage errorMessage) {
            if (errorMessage == null) {
                logger.error("endWithError(): argument 'errorMessage' must be non-null");
                // fallback to end() without error
                end();
                return;
            }
            long endTick = ticker.read();
            endInternal(endTick);
            // use higher entry limit when adding errors, but still need some kind of cap
            if (transaction.getEntryCount() < 2 * maxTraceEntriesPerTransaction) {
                // entry won't be nested properly, but at least the error will get captured
                transaction.addEntry(startTick, endTick, messageSupplier, errorMessage, true);
            }
        }

        private void endInternal(long endTick) {
            timer.end(endTick);
            if (queryData != null) {
                queryData.end(endTick);
            }
        }

        @Override
        public Timer extend() {
            if (selfNestingLevel++ == 0) {
                long currTick = ticker.read();
                extendedTimer = timer.extend(currTick);
                if (queryData != null) {
                    queryData.extend(currTick);
                }
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
                if (queryData != null) {
                    queryData.end(stopTick);
                }
            }
        }

        @Override
        public MessageSupplier getMessageSupplier() {
            return messageSupplier;
        }

        @Override
        public void incrementCurrRow() {
            if (currRow == -1) {
                currRow = 1;
                maxRow = 1;
                if (queryData != null) {
                    // queryData can be null here if the aggregated query limit is exceeded
                    // (though typically query limit is larger than trace entry limit)
                    queryData.incrementRowCount(1);
                }
            } else if (currRow == maxRow) {
                currRow++;
                maxRow = currRow;
                if (queryData != null) {
                    // queryData can be null here if the aggregated query limit is exceeded
                    // (though typically query limit is larger than trace entry limit)
                    queryData.incrementRowCount(1);
                }
            } else {
                currRow++;
            }
        }

        @Override
        public void setCurrRow(long row) {
            if (row > maxRow) {
                if (queryData != null) {
                    // queryData can be null here if the query limit is exceeded
                    // (though typically query limit is larger than trace entry limit)
                    queryData.incrementRowCount(row - maxRow);
                }
                maxRow = row;
            }
            currRow = row;
        }
    }
}
