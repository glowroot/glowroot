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
package org.glowroot.agent.model;

import java.lang.management.ThreadInfo;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import com.google.common.base.Strings;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import com.google.common.primitives.Ints;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.impl.AsyncContextImpl;
import org.glowroot.agent.impl.TransactionRegistry;
import org.glowroot.agent.impl.TransactionServiceImpl;
import org.glowroot.agent.plugin.api.AsyncQueryEntry;
import org.glowroot.agent.plugin.api.AsyncTraceEntry;
import org.glowroot.agent.plugin.api.AuxThreadContext;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.QueryEntry;
import org.glowroot.agent.plugin.api.Timer;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.internal.NopTransactionService.NopAsyncQueryEntry;
import org.glowroot.agent.plugin.api.internal.NopTransactionService.NopAuxThreadContext;
import org.glowroot.agent.plugin.api.internal.NopTransactionService.NopQueryEntry;
import org.glowroot.agent.plugin.api.internal.NopTransactionService.NopTimer;
import org.glowroot.agent.plugin.api.internal.NopTransactionService.NopTraceEntry;
import org.glowroot.agent.plugin.api.util.FastThreadLocal.Holder;
import org.glowroot.agent.util.ThreadAllocatedBytes;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.common.util.UsedByGeneratedBytecode;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.glowroot.agent.fat.storage.util.Checkers.castInitialized;

public class ThreadContextImpl implements ThreadContextPlus {

    private static final Logger logger = LoggerFactory.getLogger(ThreadContextImpl.class);

    private final Transaction transaction;
    // this is null for main thread, and non-null for auxiliary threads
    private final @Nullable TraceEntryImpl parentTraceEntry;

    private final TimerImpl rootTimer;
    // currentTimer doesn't need to be thread safe as it is only accessed by transaction thread
    private @Nullable TimerImpl currentTimer;

    private int currentNestingGroupId;

    private final @Nullable ThreadStatsComponent threadStatsComponent;

    // root entry for this trace
    private final TraceEntryComponent traceEntryComponent;

    // linked list of QueryData instances for safe concurrent access
    private @MonotonicNonNull QueryData headQueryData;
    // these maps are only accessed by the transaction thread
    private @MonotonicNonNull QueryDataMap firstQueryTypeQueries;
    private @MonotonicNonNull Map<String, QueryDataMap> allQueryTypesMap;

    private final long threadId;

    private final boolean auxiliary;

    private final TransactionRegistry transactionRegistry;
    private final TransactionServiceImpl transactionService;
    private final ConfigService configService;

    private final Ticker ticker;

    private final Holder</*@Nullable*/ ThreadContextImpl> threadContextHolder;

    ThreadContextImpl(Transaction transaction, @Nullable TraceEntryImpl parentTraceEntry,
            MessageSupplier messageSupplier, TimerName rootTimerName, long startTick,
            boolean captureThreadStats, @Nullable ThreadAllocatedBytes threadAllocatedBytes,
            boolean auxiliary, TransactionRegistry transactionRegistry,
            TransactionServiceImpl transactionService, ConfigService configService, Ticker ticker,
            Holder</*@Nullable*/ ThreadContextImpl> threadContextHolder) {
        this.transaction = transaction;
        this.parentTraceEntry = parentTraceEntry;
        rootTimer = TimerImpl.createRootTimer(castInitialized(this), (TimerNameImpl) rootTimerName);
        rootTimer.start(startTick);
        traceEntryComponent = new TraceEntryComponent(castInitialized(this), messageSupplier,
                rootTimer, startTick, ticker);
        threadId = Thread.currentThread().getId();
        threadStatsComponent =
                captureThreadStats ? new ThreadStatsComponent(threadAllocatedBytes) : null;
        this.auxiliary = auxiliary;
        this.transactionRegistry = transactionRegistry;
        this.transactionService = transactionService;
        this.configService = configService;
        this.ticker = ticker;
        this.threadContextHolder = threadContextHolder;
    }

    public boolean isAuxiliary() {
        return auxiliary;
    }

    public Transaction getTransaction() {
        return transaction;
    }

    @Nullable
    TraceEntryImpl getParentTraceEntry() {
        return parentTraceEntry;
    }

    public TraceEntryImpl getRootEntry() {
        return traceEntryComponent.getRootEntry();
    }

    public TimerImpl getRootTimer() {
        return rootTimer;
    }

    public List<Trace.Entry> getEntriesProtobuf(long captureTick,
            Multimap<TraceEntryImpl, TraceEntryImpl> asyncRootTraceEntries) {
        return traceEntryComponent.toProto(captureTick, asyncRootTraceEntries);
    }

    public ThreadStats getThreadStats() {
        if (threadStatsComponent == null) {
            return ThreadStats.NA;
        }
        return threadStatsComponent.getThreadStats();
    }

    public long getThreadId() {
        return threadId;
    }

    public boolean isCompleted() {
        return traceEntryComponent.isCompleted();
    }

    public @Nullable TimerImpl getCurrentTimer() {
        return currentTimer;
    }

    void setCurrentTimer(@Nullable TimerImpl currentTimer) {
        this.currentTimer = currentTimer;
    }

    @Override
    @UsedByGeneratedBytecode
    public int getCurrentNestingGroupId() {
        return currentNestingGroupId;
    }

    @Override
    @UsedByGeneratedBytecode
    public void setCurrentNestingGroupId(int nestingGroupId) {
        this.currentNestingGroupId = nestingGroupId;
    }

    public TraceEntryImpl pushEntry(long startTick, MessageSupplier messageSupplier,
            @Nullable String queryType, @Nullable String queryText, long queryExecutionCount,
            TimerImpl timer) {
        QueryData queryData = null;
        if (queryType != null && queryText != null) {
            queryData = getOrCreateQueryDataIfPossible(queryType, queryText);
        }
        return traceEntryComponent.pushEntry(startTick, messageSupplier, queryData,
                queryExecutionCount, timer);
    }

    public void mergeQueriesInto(QueryCollector queries) {
        QueryData curr = headQueryData;
        while (curr != null) {
            queries.mergeQuery(curr.getQueryType(), curr.getQueryText(),
                    curr.getTotalDurationNanos(), curr.getExecutionCount(), curr.getTotalRows());
            curr = curr.getNextQueryData();
        }
    }

    // only called by transaction thread
    public @Nullable QueryData getOrCreateQueryDataIfPossible(String queryType, String queryText) {
        if (headQueryData == null) {
            if (!transaction.allowAnotherAggregateQuery()) {
                return null;
            }
            QueryData queryData = new QueryData(queryType, queryText, null);
            firstQueryTypeQueries = new QueryDataMap();
            firstQueryTypeQueries.put(queryText, queryData);
            headQueryData = queryData;
            return headQueryData;
        }
        QueryDataMap currentQueryTypeQueries;
        if (queryType.equals(headQueryData.getQueryType())) {
            currentQueryTypeQueries = checkNotNull(firstQueryTypeQueries);
        } else {
            currentQueryTypeQueries = getOrCreateQueriesForQueryType(queryType);
        }
        QueryData queryData = currentQueryTypeQueries.get(queryText);
        if (queryData == null && transaction.allowAnotherAggregateQuery()) {
            queryData = new QueryData(queryType, queryText, headQueryData);
            currentQueryTypeQueries.put(queryText, queryData);
            headQueryData = queryData;
        }
        return queryData;
    }

    public TraceEntryImpl addErrorEntry(long startTick, long endTick,
            @Nullable MessageSupplier messageSupplier, ErrorMessage errorMessage) {
        TraceEntryImpl entry = traceEntryComponent.addErrorEntry(startTick, endTick,
                messageSupplier, errorMessage);
        // memory barrier write ensures partial trace capture will see data collected up to now
        // memory barrier read ensures timely visibility of detach()
        transaction.memoryBarrierReadWrite();
        return entry;
    }

    public TraceEntryImpl startAsyncEntry(long startTick, MessageSupplier messageSupplier,
            TimerImpl syncTimer, AsyncTimerImpl asyncTimer, @Nullable String queryType,
            @Nullable String queryText, long queryExecutionCount) {
        QueryData queryData = null;
        if (queryType != null && queryText != null) {
            queryData = getOrCreateQueryDataIfPossible(queryType, queryText);
        }
        TraceEntryImpl entry = traceEntryComponent.startAsyncEntry(startTick, messageSupplier,
                syncTimer, asyncTimer, queryData, queryExecutionCount);
        // memory barrier write ensures partial trace capture will see data collected up to now
        // memory barrier read ensures timely visibility of detach()
        transaction.memoryBarrierReadWrite();
        return entry;
    }

    public void captureStackTrace(ThreadInfo threadInfo, int limit) {
        transaction.captureStackTrace(auxiliary, threadInfo, limit);
        // memory barrier read ensures timely visibility of detach()
        transaction.memoryBarrierRead();
    }

    @Override
    public AuxThreadContext createAuxThreadContext() {
        TraceEntryImpl activeEntry = traceEntryComponent.getActiveEntry();
        if (activeEntry == null) {
            logger.warn("cannot create async context because active entry is null");
            return NopAuxThreadContext.INSTANCE;
        }
        return new AsyncContextImpl(transaction, activeEntry, transactionRegistry,
                transactionService);
    }

    // typically pop() methods don't require the objects to pop, but for safety, the entry to pop is
    // passed in just to make sure it is the one on top (and if not, then pop until is is found,
    // preventing any nasty bugs from a missed pop, e.g. a trace never being marked as complete)
    void popEntry(TraceEntryImpl entry, long endTick) {
        traceEntryComponent.popEntry(entry, endTick);
        // memory barrier write ensures partial trace capture will see data collected up to now
        // memory barrier read ensures timely visibility of detach()
        transaction.memoryBarrierReadWrite();
        if (traceEntryComponent.isCompleted()) {
            if (!auxiliary) {
                transaction.end(endTick);
            }
            if (threadStatsComponent != null) {
                threadStatsComponent.onComplete();
            }
            threadContextHolder.set(null);
        }
    }

    // detach is called from another thread
    void detach() {
        // this synchronization protects against clobbering valid thread context in race condition
        // where thread context ends naturally and thread re-starts a new thread context quickly
        // see counterpart to this synchronized block in Transaction.startAuxThreadContext()
        synchronized (threadContextHolder) {
            if (threadContextHolder.get() == this) {
                threadContextHolder.set(null);
            }
        }
        // memory barrier write is needed to ensure the running thread sees that the thread
        // context holder has been cleared (at least after the thread completes its next trace entry
        // or profile sample, which both perform memory barrier reads)
        transaction.memoryBarrierWrite();
    }

    private QueryDataMap getOrCreateQueriesForQueryType(String queryType) {
        if (allQueryTypesMap == null) {
            allQueryTypesMap = new HashMap<String, QueryDataMap>(2);
            QueryDataMap currentQueryTypeQueries = new QueryDataMap();
            allQueryTypesMap.put(queryType, currentQueryTypeQueries);
            return currentQueryTypeQueries;
        }
        QueryDataMap currentQueryTypeQueries = allQueryTypesMap.get(queryType);
        if (currentQueryTypeQueries == null) {
            currentQueryTypeQueries = new QueryDataMap();
            allQueryTypesMap.put(queryType, currentQueryTypeQueries);
        }
        return currentQueryTypeQueries;
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
        return startTraceEntryInternal(messageSupplier, null, null, 0, timerName);
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
        return startTraceEntryInternal(messageSupplier, null, null, 0, timerName);
    }

    @Override
    public QueryEntry startQueryEntry(String queryType, String queryText,
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
        return startTraceEntryInternal(messageSupplier, queryType, queryText, 1, timerName);
    }

    @Override
    public AsyncQueryEntry startAsyncQueryEntry(String queryType, String queryText,
            MessageSupplier messageSupplier, TimerName syncTimerName, TimerName asyncTimerName) {
        if (queryType == null) {
            logger.error("startQuery(): argument 'queryType' must be non-null");
            return NopAsyncQueryEntry.INSTANCE;
        }
        if (queryText == null) {
            logger.error("startQuery(): argument 'queryText' must be non-null");
            return NopAsyncQueryEntry.INSTANCE;
        }
        if (messageSupplier == null) {
            logger.error("startQuery(): argument 'messageSupplier' must be non-null");
            return NopAsyncQueryEntry.INSTANCE;
        }
        if (syncTimerName == null) {
            logger.error("startQuery(): argument 'syncTimerName' must be non-null");
            return NopAsyncQueryEntry.INSTANCE;
        }
        if (asyncTimerName == null) {
            logger.error("startQuery(): argument 'asyncTimerName' must be non-null");
            return NopAsyncQueryEntry.INSTANCE;
        }
        return startAsyncTraceEntry(messageSupplier, syncTimerName, asyncTimerName, queryType,
                queryText, 1);
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
        return startTraceEntryInternal(messageSupplier, queryType, queryText, queryExecutionCount,
                timerName);
    }

    @Override
    public AsyncTraceEntry startAsyncTraceEntry(MessageSupplier messageSupplier,
            TimerName syncTimerName, TimerName asyncTimerName) {
        if (syncTimerName == null) {
            logger.error("startQuery(): argument 'syncTimerName' must be non-null");
            return NopAsyncQueryEntry.INSTANCE;
        }
        if (asyncTimerName == null) {
            logger.error("startQuery(): argument 'asyncTimerName' must be non-null");
            return NopAsyncQueryEntry.INSTANCE;
        }
        return startAsyncTraceEntry(messageSupplier, syncTimerName, asyncTimerName, null, null, 0);
    }

    @Override
    public Timer startTimer(TimerName timerName) {
        if (timerName == null) {
            logger.error("startTimer(): argument 'timerName' must be non-null");
            return NopTimer.INSTANCE;
        }
        if (currentTimer == null) {
            logger.warn("startTimer(): called on completed thread context");
            return NopTimer.INSTANCE;
        }
        return currentTimer.startNestedTimer(timerName);
    }

    @Override
    public void addErrorEntry(Throwable t) {
        addErrorEntryInternal(ErrorMessage.from(t));
    }

    @Override
    public void addErrorEntry(@Nullable String message) {
        addErrorEntryInternal(ErrorMessage.from(message));
    }

    @Override
    public void addErrorEntry(@Nullable String message, Throwable t) {
        addErrorEntryInternal(ErrorMessage.from(message, t));
    }

    @Override
    public void setTransactionType(@Nullable String transactionType, int priority) {
        if (Strings.isNullOrEmpty(transactionType)) {
            return;
        }
        transaction.setTransactionType(transactionType, priority);
    }

    @Override
    public void setTransactionName(@Nullable String transactionName, int priority) {
        if (Strings.isNullOrEmpty(transactionName)) {
            return;
        }
        transaction.setTransactionName(transactionName, priority);
    }

    @Override
    public void setTransactionError(Throwable t) {
        transaction.setError(ErrorMessage.from(t));
    }

    @Override
    public void setTransactionError(@Nullable String message) {
        if (Strings.isNullOrEmpty(message)) {
            return;
        }
        transaction.setError(ErrorMessage.from(message));
    }

    @Override
    public void setTransactionError(@Nullable String message, Throwable t) {
        transaction.setError(ErrorMessage.from(message, t));
    }

    @Override
    public void setTransactionUser(@Nullable String user, int priority) {
        if (Strings.isNullOrEmpty(user)) {
            return;
        }
        transaction.setUser(user, priority);
    }

    @Override
    public void addTransactionAttribute(String name, @Nullable String value) {
        if (name == null) {
            logger.error("addTransactionAttribute(): argument 'name' must be non-null");
            return;
        }
        transaction.addAttribute(name, value);
    }

    @Override
    public void setTransactionSlowThreshold(long threshold, TimeUnit unit, int priority) {
        if (threshold < 0) {
            logger.error(
                    "setTransactionSlowThreshold(): argument 'threshold' must be non-negative");
            return;
        }
        if (unit == null) {
            logger.error("setTransactionSlowThreshold(): argument 'unit' must be non-null");
            return;
        }
        int thresholdMillis = Ints.saturatedCast(unit.toMillis(threshold));
        transaction.setSlowThresholdMillis(thresholdMillis, priority);
    }

    public boolean isInTransaction() {
        return true;
    }

    QueryEntry startTraceEntryInternal(MessageSupplier messageSupplier, @Nullable String queryType,
            @Nullable String queryText, long queryExecutionCount, TimerName timerName) {
        long startTick = ticker.read();
        if (transaction.allowAnotherEntry()) {
            TimerImpl timer = startTimer(timerName, startTick);
            return pushEntry(startTick, messageSupplier, queryType, queryText, queryExecutionCount,
                    timer);
        }
        // split out to separate method so as not to affect inlining budget of common path
        return startDummyTraceEntry(timerName, messageSupplier, queryType, queryText,
                queryExecutionCount, startTick);
    }

    private AsyncQueryEntry startAsyncTraceEntry(MessageSupplier messageSupplier,
            TimerName syncTimerName, TimerName asyncTimerName, @Nullable String queryType,
            @Nullable String queryText, long queryExecutionCount) {
        long startTick = ticker.read();
        if (transaction.allowAnotherEntry()) {
            TimerImpl syncTimer = startTimer(syncTimerName, startTick);
            AsyncTimerImpl asyncTimer = startAsyncTimer(asyncTimerName, startTick);
            return startAsyncEntry(startTick, messageSupplier, syncTimer, asyncTimer, queryType,
                    queryText, queryExecutionCount);
        }
        // split out to separate method so as not to affect inlining budget of common path
        return startDummyAsyncTraceEntry(messageSupplier, syncTimerName, asyncTimerName, queryType,
                queryText, queryExecutionCount, startTick);
    }

    private void addErrorEntryInternal(ErrorMessage errorMessage) {
        // use higher entry limit when adding errors, but still need some kind of cap
        if (transaction.allowAnotherErrorEntry()) {
            long currTick = ticker.read();
            org.glowroot.agent.model.TraceEntryImpl entry =
                    addErrorEntry(currTick, currTick, null, errorMessage);
            if (errorMessage.throwable() == null) {
                StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
                // need to strip back a few stack calls:
                // skip i=0 which is "java.lang.Thread.getStackTrace()"
                // skip i=1 which is "...TransactionServiceImpl.addErrorEntryInternal()"
                // skip i=2 which is "...TransactionServiceImpl.addErrorEntry()"
                // skip i=3 which is the plugin advice
                entry.setStackTrace(ImmutableList.copyOf(stackTrace).subList(4, stackTrace.length));
            }
        }
    }

    private QueryEntry startDummyTraceEntry(TimerName timerName, MessageSupplier messageSupplier,
            @Nullable String queryType, @Nullable String queryText, long queryExecutionCount,
            long startTick) {
        // the entry limit has been exceeded for this trace
        QueryData queryData = null;
        if (queryType != null && queryText != null) {
            queryData = getOrCreateQueryDataIfPossible(queryType, queryText);
        }
        TimerImpl timer = startTimer(timerName, startTick);
        return new DummyTraceEntryOrQuery(timer, null, startTick, messageSupplier, queryData,
                queryExecutionCount);
    }

    private AsyncQueryEntry startDummyAsyncTraceEntry(MessageSupplier messageSupplier,
            TimerName syncTimerName, TimerName asyncTimerName, @Nullable String queryType,
            @Nullable String queryText, long queryExecutionCount, long startTick) {
        // the entry limit has been exceeded for this trace
        QueryData queryData = null;
        if (queryType != null && queryText != null) {
            queryData = getOrCreateQueryDataIfPossible(queryType, queryText);
        }
        TimerImpl syncTimer = startTimer(syncTimerName, startTick);
        TimerImpl asyncTimer = startTimer(asyncTimerName, startTick);
        return new DummyTraceEntryOrQuery(syncTimer, asyncTimer, startTick, messageSupplier,
                queryData, queryExecutionCount);
    }

    private TimerImpl startTimer(TimerName timerName, long startTick) {
        if (currentTimer == null) {
            // this really shouldn't happen as current timer should be non-null unless transaction
            // has completed
            return TimerImpl.createRootTimer(this, (TimerNameImpl) timerName);
        }
        return currentTimer.startNestedTimer(timerName, startTick);
    }

    private AsyncTimerImpl startAsyncTimer(TimerName asyncTimerName, long startTick) {
        return transaction.startAsyncTimer(asyncTimerName, startTick);
    }

    private class DummyTraceEntryOrQuery extends QueryEntryBase implements AsyncQueryEntry, Timer {

        private final TimerImpl syncTimer;
        private final @Nullable TimerImpl asyncTimer;
        private final long startTick;
        private final MessageSupplier messageSupplier;

        // not volatile, so depends on memory barrier in Transaction for visibility
        private int selfNestingLevel;
        // only used by transaction thread
        private @MonotonicNonNull TimerImpl extendedTimer;

        public DummyTraceEntryOrQuery(TimerImpl syncTimer, @Nullable TimerImpl asyncTimer,
                long startTick, MessageSupplier messageSupplier, @Nullable QueryData queryData,
                long queryExecutionCount) {
            super(queryData);
            this.syncTimer = syncTimer;
            this.asyncTimer = asyncTimer;
            this.startTick = startTick;
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
            if (transaction.allowAnotherErrorEntry()) {
                // entry won't be nested properly, but at least the error will get captured
                org.glowroot.agent.model.TraceEntryImpl entry =
                        addErrorEntry(startTick, endTick, messageSupplier, errorMessage);
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
}
