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
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import com.google.common.base.Strings;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ListMultimap;
import com.google.common.primitives.Ints;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.impl.AuxThreadContextImpl;
import org.glowroot.agent.impl.QueryCollector;
import org.glowroot.agent.plugin.api.AsyncQueryEntry;
import org.glowroot.agent.plugin.api.AsyncTraceEntry;
import org.glowroot.agent.plugin.api.AuxThreadContext;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.QueryEntry;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.Timer;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.internal.NopTransactionService.NopAsyncQueryEntry;
import org.glowroot.agent.plugin.api.internal.NopTransactionService.NopAsyncTraceEntry;
import org.glowroot.agent.plugin.api.internal.NopTransactionService.NopQueryEntry;
import org.glowroot.agent.plugin.api.internal.NopTransactionService.NopTimer;
import org.glowroot.agent.plugin.api.internal.NopTransactionService.NopTraceEntry;
import org.glowroot.agent.plugin.api.internal.ReadableMessage;
import org.glowroot.agent.plugin.api.util.FastThreadLocal.Holder;
import org.glowroot.agent.util.ThreadAllocatedBytes;
import org.glowroot.agent.util.Tickers;
import org.glowroot.common.model.ServiceCallCollector;
import org.glowroot.common.util.UsedByGeneratedBytecode;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.glowroot.agent.util.Checkers.castInitialized;

public class ThreadContextImpl implements ThreadContextPlus {

    static final MessageSupplier DETACHED_MESSAGE_SUPPLIER = MessageSupplier
            .from("this auxiliary thread was still running when the transaction ended");

    private static final Logger logger = LoggerFactory.getLogger(ThreadContextImpl.class);

    private final Transaction transaction;
    // this is null for main thread, and non-null for auxiliary threads
    private final @Nullable TraceEntryImpl parentTraceEntry;
    // this is null for main thread, and non-null for auxiliary threads
    // it is used to help place aux thread context in the correct place inside parent
    private final @Nullable TraceEntryImpl parentThreadContextPriorEntry;

    private final TimerImpl rootTimer;
    // only accessed by the thread context's thread
    private @Nullable TimerImpl currentTimer;

    private int currentNestingGroupId;

    private final @Nullable ThreadStatsComponent threadStatsComponent;

    // root entry for this trace
    private final TraceEntryComponent traceEntryComponent;

    // only accessed by the thread context's thread
    private boolean completeAsyncTransaction;

    // linked lists of QueryData instances for safe concurrent access
    private @MonotonicNonNull QueryData headQueryData;
    private @MonotonicNonNull QueryData headServiceCallData;
    // these maps are only accessed by the thread context's thread
    private @MonotonicNonNull QueryDataMap queriesForFirstType;
    private @MonotonicNonNull Map<String, QueryDataMap> allQueryTypesMap;
    private @MonotonicNonNull QueryDataMap serviceCallsForFirstType;
    private @MonotonicNonNull Map<String, QueryDataMap> allServiceCallTypesMap;

    private final long threadId;

    private final boolean auxiliary;

    private final Ticker ticker;

    private final Holder</*@Nullable*/ ThreadContextImpl> threadContextHolder;

    private @Nullable MessageSupplier servletMessageSupplier;

    // this is not used much, so overhead of Long seems good tradeoff for avoiding extra field
    private volatile @MonotonicNonNull Long detachedTime;

    ThreadContextImpl(Transaction transaction, @Nullable TraceEntryImpl parentTraceEntry,
            @Nullable TraceEntryImpl parentThreadContextPriorEntry, MessageSupplier messageSupplier,
            TimerName rootTimerName, long startTick, boolean captureThreadStats,
            @Nullable ThreadAllocatedBytes threadAllocatedBytes, boolean auxiliary, Ticker ticker,
            Holder</*@Nullable*/ ThreadContextImpl> threadContextHolder,
            @Nullable MessageSupplier servletMessageSupplier) {
        this.transaction = transaction;
        this.parentTraceEntry = parentTraceEntry;
        rootTimer = TimerImpl.createRootTimer(castInitialized(this), (TimerNameImpl) rootTimerName);
        rootTimer.start(startTick);
        traceEntryComponent = new TraceEntryComponent(castInitialized(this), messageSupplier,
                rootTimer, startTick);
        this.parentThreadContextPriorEntry = parentThreadContextPriorEntry;
        threadId = Thread.currentThread().getId();
        threadStatsComponent =
                captureThreadStats ? new ThreadStatsComponent(threadAllocatedBytes) : null;
        this.auxiliary = auxiliary;
        this.ticker = ticker;
        this.threadContextHolder = threadContextHolder;
        this.servletMessageSupplier = servletMessageSupplier;
    }

    public Transaction getTransaction() {
        return transaction;
    }

    @Nullable
    TraceEntryImpl getParentThreadContextPriorEntry() {
        return parentThreadContextPriorEntry;
    }

    public TraceEntryImpl getRootEntry() {
        return traceEntryComponent.getRootEntry();
    }

    TimerImpl getRootTimer() {
        return rootTimer;
    }

    ThreadStats getThreadStats() {
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

    private boolean isCompleted(long captureTick) {
        if (!traceEntryComponent.isCompleted()) {
            return false;
        }
        return traceEntryComponent.getEndTick() >= captureTick;
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

    void mergeQueriesInto(QueryCollector queries) {
        QueryData curr = headQueryData;
        while (curr != null) {
            queries.mergeQuery(curr.getQueryType(), curr.getQueryText(),
                    curr.getTotalDurationNanos(), curr.getExecutionCount(), curr.hasTotalRows(),
                    curr.getTotalRows());
            curr = curr.getNextQueryData();
        }
    }

    void mergeServiceCallsInto(ServiceCallCollector serviceCalls) {
        QueryData curr = headServiceCallData;
        while (curr != null) {
            serviceCalls.mergeServiceCall(curr.getQueryType(), curr.getQueryText(),
                    curr.getTotalDurationNanos(), curr.getExecutionCount());
            curr = curr.getNextQueryData();
        }
    }

    boolean getCaptureThreadStats() {
        return threadStatsComponent != null;
    }

    // only called by transaction thread
    private @Nullable QueryData getOrCreateQueryDataIfPossible(String queryType, String queryText) {
        if (headQueryData == null) {
            if (!transaction.allowAnotherAggregateQuery()) {
                return null;
            }
            QueryData queryData = new QueryData(queryType, queryText, null);
            queriesForFirstType = new QueryDataMap(queryType);
            queriesForFirstType.put(queryText, queryData);
            headQueryData = queryData;
            return headQueryData;
        }
        QueryDataMap queriesForCurrentType = checkNotNull(queriesForFirstType);
        if (!queriesForCurrentType.getType().equals(queryType)) {
            queriesForCurrentType = getOrCreateQueriesForType(queryType);
        }
        QueryData queryData = queriesForCurrentType.get(queryText);
        if (queryData == null && transaction.allowAnotherAggregateQuery()) {
            queryData = new QueryData(queryType, queryText, headQueryData);
            queriesForCurrentType.put(queryText, queryData);
            headQueryData = queryData;
        }
        return queryData;
    }

    // only called by transaction thread
    private @Nullable QueryData getOrCreateServiceCallDataIfPossible(String type, String text) {
        if (headServiceCallData == null) {
            if (!transaction.allowAnotherAggregateServiceCall()) {
                return null;
            }
            QueryData serviceCallData = new QueryData(type, text, null);
            serviceCallsForFirstType = new QueryDataMap(type);
            serviceCallsForFirstType.put(text, serviceCallData);
            headServiceCallData = serviceCallData;
            return headServiceCallData;
        }
        QueryDataMap serviceCallsForCurrentType = checkNotNull(serviceCallsForFirstType);
        if (!serviceCallsForCurrentType.getType().equals(type)) {
            serviceCallsForCurrentType = getOrCreateServiceCallsForType(type);
        }
        QueryData serviceCallData = serviceCallsForCurrentType.get(text);
        if (serviceCallData == null && transaction.allowAnotherAggregateServiceCall()) {
            serviceCallData = new QueryData(type, text, headServiceCallData);
            serviceCallsForCurrentType.put(text, serviceCallData);
            headServiceCallData = serviceCallData;
        }
        return serviceCallData;
    }

    private TraceEntryImpl addErrorEntry(long startTick, long endTick,
            @Nullable MessageSupplier messageSupplier, ErrorMessage errorMessage) {
        TraceEntryImpl entry = traceEntryComponent.addErrorEntry(startTick, endTick,
                messageSupplier, errorMessage);
        // memory barrier write ensures partial trace capture will see data collected up to now
        // memory barrier read ensures timely visibility of detach()
        transaction.memoryBarrierReadWrite();
        return entry;
    }

    private TraceEntryImpl startAsyncTraceEntry(long startTick, MessageSupplier messageSupplier,
            TimerImpl syncTimer, AsyncTimerImpl asyncTimer) {
        TraceEntryImpl entry = traceEntryComponent.pushEntry(startTick, messageSupplier, syncTimer,
                asyncTimer, null, 0);
        // memory barrier write ensures partial trace capture will see data collected up to now
        // memory barrier read ensures timely visibility of detach()
        transaction.memoryBarrierReadWrite();
        return entry;
    }

    private TraceEntryImpl startAsyncQueryEntry(long startTick, MessageSupplier messageSupplier,
            TimerImpl syncTimer, AsyncTimerImpl asyncTimer, @Nullable QueryData queryData,
            long queryExecutionCount) {
        TraceEntryImpl entry = traceEntryComponent.pushEntry(startTick, messageSupplier, syncTimer,
                asyncTimer, queryData, queryExecutionCount);
        // memory barrier write ensures partial trace capture will see data collected up to now
        // memory barrier read ensures timely visibility of detach()
        transaction.memoryBarrierReadWrite();
        return entry;
    }

    private TraceEntryImpl startAsyncServiceCallEntry(long startTick,
            MessageSupplier messageSupplier, TimerImpl syncTimer, AsyncTimerImpl asyncTimer,
            @Nullable QueryData queryData) {
        TraceEntryImpl entry = traceEntryComponent.pushEntry(startTick, messageSupplier,
                syncTimer, asyncTimer, queryData, 1);
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
        return new AuxThreadContextImpl(transaction, traceEntryComponent.getActiveEntry(),
                traceEntryComponent.getTailEntry(), servletMessageSupplier,
                transaction.getTransactionRegistry(), transaction.getTransactionService());
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
            if (!auxiliary || completeAsyncTransaction) {
                transaction.end(endTick, completeAsyncTransaction);
            }
            if (threadStatsComponent != null) {
                threadStatsComponent.onComplete();
            }
            threadContextHolder.set(null);
        }
    }

    // typically pop() methods don't require the objects to pop, but for safety, the entry to pop is
    // passed in just to make sure it is the one on top (and if not, then pop until is is found,
    // preventing any nasty bugs from a missed pop, e.g. a trace never being marked as complete)
    void popNonRootEntry(TraceEntryImpl entry) {
        traceEntryComponent.popNonRootEntry(entry);
        // memory barrier write ensures partial trace capture will see data collected up to now
        // memory barrier read ensures timely visibility of detach()
        transaction.memoryBarrierReadWrite();
    }

    // detach is called from another thread
    void detach() {
        // this synchronization protects against clobbering valid thread context in race condition
        // where thread context ends naturally and thread re-starts a new thread context quickly
        // see counterpart to this synchronized block in startAuxThreadContext()
        synchronized (threadContextHolder) {
            if (threadContextHolder.get() == this) {
                threadContextHolder.set(null);
            }
        }
        // memory barrier write is needed to ensure the running thread sees that the thread
        // context holder has been cleared (at least after the thread completes its next trace entry
        // or profile sample, which both perform memory barrier reads)
        transaction.memoryBarrierWrite();
        detachedTime = ticker.read();
    }

    private QueryDataMap getOrCreateQueriesForType(String queryType) {
        if (allQueryTypesMap == null) {
            allQueryTypesMap = new HashMap<String, QueryDataMap>(2);
            QueryDataMap queriesForCurrentType = new QueryDataMap(queryType);
            allQueryTypesMap.put(queryType, queriesForCurrentType);
            return queriesForCurrentType;
        }
        QueryDataMap queriesForCurrentType = allQueryTypesMap.get(queryType);
        if (queriesForCurrentType == null) {
            queriesForCurrentType = new QueryDataMap(queryType);
            allQueryTypesMap.put(queryType, queriesForCurrentType);
        }
        return queriesForCurrentType;
    }

    private QueryDataMap getOrCreateServiceCallsForType(String type) {
        if (allServiceCallTypesMap == null) {
            allServiceCallTypesMap = new HashMap<String, QueryDataMap>(2);
            QueryDataMap serviceCallsForCurrentType = new QueryDataMap(type);
            allServiceCallTypesMap.put(type, serviceCallsForCurrentType);
            return serviceCallsForCurrentType;
        }
        QueryDataMap serviceCallsForCurrentType = allServiceCallTypesMap.get(type);
        if (serviceCallsForCurrentType == null) {
            serviceCallsForCurrentType = new QueryDataMap(type);
            allServiceCallTypesMap.put(type, serviceCallsForCurrentType);
        }
        return serviceCallsForCurrentType;
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
        transaction.getConfigService().readMemoryBarrier();
        long startTick = ticker.read();
        TimerImpl timer = startTimer(timerName, startTick);
        if (transaction.allowAnotherEntry()) {
            return traceEntryComponent.pushEntry(startTick, messageSupplier, timer, null, null, 0);
        } else {
            return new DummyTraceEntryOrQuery(timer, null, startTick, messageSupplier, null, 0);
        }
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
        long startTick = ticker.read();
        TimerImpl timer = startTimer(timerName, startTick);
        if (transaction.allowAnotherEntry()) {
            return traceEntryComponent.pushEntry(startTick, messageSupplier, timer, null, null, 0);
        } else {
            return new DummyTraceEntryOrQuery(timer, null, startTick, messageSupplier, null, 0);
        }
    }

    @Override
    public AsyncTraceEntry startAsyncTraceEntry(MessageSupplier messageSupplier,
            TimerName timerName) {
        if (messageSupplier == null) {
            logger.error("startAsyncTraceEntry(): argument 'messageSupplier' must be non-null");
            return NopAsyncTraceEntry.INSTANCE;
        }
        if (timerName == null) {
            logger.error("startAsyncTraceEntry(): argument 'timerName' must be non-null");
            return NopAsyncTraceEntry.INSTANCE;
        }
        long startTick = ticker.read();
        TimerImpl syncTimer = startTimer(timerName, startTick);
        AsyncTimerImpl asyncTimer = startAsyncTimer(timerName, startTick);
        if (transaction.allowAnotherEntry()) {
            return startAsyncTraceEntry(startTick, messageSupplier, syncTimer, asyncTimer);
        } else {
            return new DummyTraceEntryOrQuery(syncTimer, asyncTimer, startTick, messageSupplier,
                    null, 0);
        }
    }

    @Override
    public QueryEntry startQueryEntry(String queryType, String queryText,
            MessageSupplier messageSupplier, TimerName timerName) {
        if (queryType == null) {
            logger.error("startQueryEntry(): argument 'queryType' must be non-null");
            return NopQueryEntry.INSTANCE;
        }
        if (queryText == null) {
            logger.error("startQueryEntry(): argument 'queryText' must be non-null");
            return NopQueryEntry.INSTANCE;
        }
        if (messageSupplier == null) {
            logger.error("startQueryEntry(): argument 'messageSupplier' must be non-null");
            return NopQueryEntry.INSTANCE;
        }
        if (timerName == null) {
            logger.error("startQueryEntry(): argument 'timerName' must be non-null");
            return NopQueryEntry.INSTANCE;
        }
        long startTick = ticker.read();
        TimerImpl timer = startTimer(timerName, startTick);
        QueryData queryData = getOrCreateQueryDataIfPossible(queryType, queryText);
        if (transaction.allowAnotherEntry()) {
            return traceEntryComponent.pushEntry(startTick, messageSupplier, timer, null, queryData,
                    1);
        } else {
            return new DummyTraceEntryOrQuery(timer, null, startTick, messageSupplier, queryData,
                    1);
        }
    }

    @Override
    public QueryEntry startQueryEntry(String queryType, String queryText, long queryExecutionCount,
            MessageSupplier messageSupplier, TimerName timerName) {
        if (queryType == null) {
            logger.error("startQueryEntry(): argument 'queryType' must be non-null");
            return NopQueryEntry.INSTANCE;
        }
        if (queryText == null) {
            logger.error("startQueryEntry(): argument 'queryText' must be non-null");
            return NopQueryEntry.INSTANCE;
        }
        if (messageSupplier == null) {
            logger.error("startQueryEntry(): argument 'messageSupplier' must be non-null");
            return NopQueryEntry.INSTANCE;
        }
        if (timerName == null) {
            logger.error("startQueryEntry(): argument 'timerName' must be non-null");
            return NopQueryEntry.INSTANCE;
        }
        long startTick = ticker.read();
        TimerImpl timer = startTimer(timerName, startTick);
        QueryData queryData = getOrCreateQueryDataIfPossible(queryType, queryText);
        if (transaction.allowAnotherEntry()) {
            return traceEntryComponent.pushEntry(startTick, messageSupplier, timer, null,
                    queryData, queryExecutionCount);
        } else {
            return new DummyTraceEntryOrQuery(timer, null, startTick, messageSupplier, queryData,
                    queryExecutionCount);
        }
    }

    @Override
    public AsyncQueryEntry startAsyncQueryEntry(String queryType, String queryText,
            MessageSupplier messageSupplier, TimerName timerName) {
        if (queryType == null) {
            logger.error("startAsyncQueryEntry(): argument 'queryType' must be non-null");
            return NopAsyncQueryEntry.INSTANCE;
        }
        if (queryText == null) {
            logger.error("startAsyncQueryEntry(): argument 'queryText' must be non-null");
            return NopAsyncQueryEntry.INSTANCE;
        }
        if (messageSupplier == null) {
            logger.error("startAsyncQueryEntry(): argument 'messageSupplier' must be non-null");
            return NopAsyncQueryEntry.INSTANCE;
        }
        if (timerName == null) {
            logger.error("startAsyncQueryEntry(): argument 'timerName' must be non-null");
            return NopAsyncQueryEntry.INSTANCE;
        }
        long startTick = ticker.read();
        TimerImpl syncTimer = startTimer(timerName, startTick);
        AsyncTimerImpl asyncTimer = startAsyncTimer(timerName, startTick);
        QueryData queryData = getOrCreateQueryDataIfPossible(queryType, queryText);
        if (transaction.allowAnotherEntry()) {
            return startAsyncQueryEntry(startTick, messageSupplier, syncTimer, asyncTimer,
                    queryData, 1);
        } else {
            return new DummyTraceEntryOrQuery(syncTimer, asyncTimer, startTick, messageSupplier,
                    queryData, 1);
        }
    }

    @Override
    public TraceEntry startServiceCallEntry(String type, String text,
            MessageSupplier messageSupplier, TimerName timerName) {
        if (type == null) {
            logger.error("startServiceCallEntry(): argument 'type' must be non-null");
            return NopTraceEntry.INSTANCE;
        }
        if (text == null) {
            logger.error("startServiceCallEntry(): argument 'text' must be non-null");
            return NopTraceEntry.INSTANCE;
        }
        if (messageSupplier == null) {
            logger.error("startServiceCallEntry(): argument 'messageSupplier' must be non-null");
            return NopTraceEntry.INSTANCE;
        }
        if (timerName == null) {
            logger.error("startServiceCallEntry(): argument 'timerName' must be non-null");
            return NopTraceEntry.INSTANCE;
        }
        long startTick = ticker.read();
        TimerImpl timer = startTimer(timerName, startTick);
        QueryData queryData = getOrCreateServiceCallDataIfPossible(type, text);
        if (transaction.allowAnotherEntry()) {
            return traceEntryComponent.pushEntry(startTick, messageSupplier, timer, null, queryData,
                    1);
        } else {
            return new DummyTraceEntryOrQuery(timer, null, startTick, messageSupplier, queryData,
                    1);
        }
    }

    @Override
    public AsyncTraceEntry startAsyncServiceCallEntry(String type, String text,
            MessageSupplier messageSupplier, TimerName timerName) {
        if (type == null) {
            logger.error("startAsyncServiceCallEntry(): argument 'type' must be non-null");
            return NopAsyncTraceEntry.INSTANCE;
        }
        if (text == null) {
            logger.error("startAsyncServiceCallEntry(): argument 'text' must be non-null");
            return NopAsyncTraceEntry.INSTANCE;
        }
        if (messageSupplier == null) {
            logger.error(
                    "startAsyncServiceCallEntry(): argument 'messageSupplier' must be non-null");
            return NopAsyncTraceEntry.INSTANCE;
        }
        if (timerName == null) {
            logger.error("startAsyncServiceCallEntry(): argument 'timerName' must be non-null");
            return NopAsyncTraceEntry.INSTANCE;
        }
        long startTick = ticker.read();
        TimerImpl syncTimer = startTimer(timerName, startTick);
        AsyncTimerImpl asyncTimer = startAsyncTimer(timerName, startTick);
        QueryData queryData = getOrCreateServiceCallDataIfPossible(type, text);
        if (transaction.allowAnotherEntry()) {
            return startAsyncServiceCallEntry(startTick, messageSupplier, syncTimer, asyncTimer,
                    queryData);
        } else {
            return new DummyTraceEntryOrQuery(syncTimer, asyncTimer, startTick, messageSupplier,
                    queryData, 1);
        }
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
    public void setAsyncTransaction() {
        transaction.setAsync();
    }

    @Override
    public void completeAsyncTransaction() {
        completeAsyncTransaction = true;
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

    @Override
    public void setTransactionError(Throwable t) {
        transaction.setError(null, t);
    }

    @Override
    public void setTransactionError(@Nullable String message) {
        if (Strings.isNullOrEmpty(message)) {
            return;
        }
        transaction.setError(message, null);
    }

    @Override
    public void setTransactionError(@Nullable String message, @Nullable Throwable t) {
        transaction.setError(message, t);
    }

    @Override
    public void addErrorEntry(Throwable t) {
        addErrorEntryInternal(null, t);
    }

    @Override
    public void addErrorEntry(@Nullable String message) {
        addErrorEntryInternal(message, null);
    }

    @Override
    public void addErrorEntry(@Nullable String message, Throwable t) {
        addErrorEntryInternal(message, t);
    }

    @Override
    public @Nullable MessageSupplier getServletMessageSupplier() {
        return servletMessageSupplier;
    }

    @Override
    public void setServletMessageSupplier(@Nullable MessageSupplier messageSupplier) {
        this.servletMessageSupplier = messageSupplier;
    }

    private void addErrorEntryInternal(@Nullable String message, @Nullable Throwable t) {
        // use higher entry limit when adding errors, but still need some kind of cap
        if (transaction.allowAnotherErrorEntry()) {
            long currTick = ticker.read();
            ErrorMessage errorMessage =
                    ErrorMessage.from(message, t, transaction.getThrowableFrameLimitCounter());
            org.glowroot.agent.model.TraceEntryImpl entry =
                    addErrorEntry(currTick, currTick, null, errorMessage);
            if (t == null) {
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

    void populateParentChildMap(ListMultimap<TraceEntryImpl, TraceEntryImpl> parentChildMap,
            long captureTick,
            ListMultimap<TraceEntryImpl, ThreadContextImpl> priorEntryAuxThreadContextMap) {
        if (captureTick < traceEntryComponent.getStartTick()) {
            return;
        }
        boolean completed = isCompleted(captureTick);
        TraceEntryImpl entry = getRootEntry();
        boolean entryIsRoot = true;
        // filter out entries that started after the capture tick
        // checking completed is short circuit optimization for the common case
        while (entry != null
                && (completed || Tickers.lessThanOrEqual(entry.getStartTick(), captureTick))) {
            TraceEntryImpl parentTraceEntry = entry.getParentTraceEntry();
            if (parentTraceEntry == null && !entryIsRoot) {
                logFoundNonRootEntryWithNullParent(entry);
                entry = entry.getNextTraceEntry();
                continue;
            }
            if (!entryIsRoot) {
                parentChildMap.put(parentTraceEntry, entry);
            }
            for (ThreadContextImpl auxThreadContext : priorEntryAuxThreadContextMap.get(entry)) {
                TraceEntryImpl auxThreadRootEntry = auxThreadContext.getRootEntry();
                if (completed || Tickers.lessThanOrEqual(auxThreadRootEntry.getStartTick(),
                        captureTick)) {
                    // checkNotNull is safe b/c aux thread contexts have non-null parent trace entry
                    parentChildMap.put(checkNotNull(auxThreadContext.parentTraceEntry),
                            auxThreadRootEntry);
                }
            }
            entry = entry.getNextTraceEntry();
            entryIsRoot = false;
        }
        if (detachedTime != null && !traceEntryComponent.isEmpty()) {
            TraceEntryImpl rootEntry = getRootEntry();
            parentChildMap.put(rootEntry,
                    new TraceEntryImpl(this, rootEntry, DETACHED_MESSAGE_SUPPLIER,
                            null, 0, transaction.getEndTick(), null, null));
        }
    }

    private void logFoundNonRootEntryWithNullParent(TraceEntryImpl entry) {
        MessageSupplier messageSupplier = entry.getMessageSupplier();
        ErrorMessage errorMessage = entry.getErrorMessage();
        String traceEntryMessage = "";
        if (messageSupplier != null) {
            ReadableMessage message = (ReadableMessage) messageSupplier.get();
            traceEntryMessage = message.getText();
        } else if (errorMessage != null) {
            traceEntryMessage = errorMessage.message();
        }
        logger.error("found non-root trace entry with null parent trace entry"
                + "\ntrace entry: {}\ntransaction: {} - {}", traceEntryMessage,
                transaction.getTransactionType(), transaction.getTransactionName());
    }

    // this does not include the root trace entry
    private class DummyTraceEntryOrQuery extends QueryEntryBase implements AsyncQueryEntry, Timer {

        private final TimerImpl syncTimer;
        private final @Nullable AsyncTimerImpl asyncTimer;
        private final long startTick;
        private final MessageSupplier messageSupplier;

        // not volatile, so depends on memory barrier in Transaction for visibility
        private int selfNestingLevel;
        // only used by transaction thread
        private @MonotonicNonNull TimerImpl extendedTimer;

        private boolean initialComplete;

        public DummyTraceEntryOrQuery(TimerImpl syncTimer, @Nullable AsyncTimerImpl asyncTimer,
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
            }
            endInternal(ticker.read());
        }

        @Override
        public void endWithError(Throwable t) {
            endWithErrorInternal(null, t);
        }

        @Override
        public void endWithError(@Nullable String message) {
            endWithErrorInternal(message, null);
        }

        @Override
        public void endWithError(@Nullable String message, Throwable t) {
            endWithErrorInternal(message, t);
        }

        @Override
        public void endWithInfo(Throwable t) {
            endInternal(ticker.read());
        }

        private void endWithErrorInternal(@Nullable String message, @Nullable Throwable t) {
            if (initialComplete) {
                // this guards against end*() being called multiple times on async trace entries
                return;
            }
            long endTick = ticker.read();
            endInternal(endTick);
            if (transaction.allowAnotherErrorEntry()) {
                ErrorMessage errorMessage =
                        ErrorMessage.from(message, t, transaction.getThrowableFrameLimitCounter());
                // entry won't be nested properly, but at least the error will get captured
                org.glowroot.agent.model.TraceEntryImpl entry =
                        addErrorEntry(startTick, endTick, messageSupplier, errorMessage);
                if (t == null) {
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
            if (initialComplete) {
                // this guards against end*() being called multiple times on async trace entries
                return;
            }
            if (asyncTimer == null) {
                syncTimer.end(endTick);
            } else {
                asyncTimer.end(endTick);
            }
            endQueryData(endTick);
            initialComplete = true;
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
        public Timer extendSyncTimer(ThreadContext currThreadContext) {
            if (currThreadContext != this) {
                return NopTimer.INSTANCE;
            }
            return syncTimer.extend();
        }
    }
}
