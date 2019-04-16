/*
 * Copyright 2015-2019 the original author or authors.
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

import java.lang.management.ThreadInfo;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Strings;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ListMultimap;
import com.google.common.primitives.Ints;
import org.checkerframework.checker.nullness.qual.EnsuresNonNullIf;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.bytecode.api.BytecodeServiceHolder;
import org.glowroot.agent.bytecode.api.ThreadContextPlus;
import org.glowroot.agent.bytecode.api.ThreadContextThreadLocal;
import org.glowroot.agent.impl.NopTransactionService.NopTimer;
import org.glowroot.agent.model.AsyncQueryData;
import org.glowroot.agent.model.AsyncTimer;
import org.glowroot.agent.model.ErrorMessage;
import org.glowroot.agent.model.QueryCollector;
import org.glowroot.agent.model.QueryData;
import org.glowroot.agent.model.QueryDataMap;
import org.glowroot.agent.model.QueryEntryBase;
import org.glowroot.agent.model.ServiceCallCollector;
import org.glowroot.agent.model.SyncQueryData;
import org.glowroot.agent.model.ThreadStats;
import org.glowroot.agent.model.ThreadStatsComponent;
import org.glowroot.agent.model.TimerNameImpl;
import org.glowroot.agent.plugin.api.AsyncQueryEntry;
import org.glowroot.agent.plugin.api.AsyncTraceEntry;
import org.glowroot.agent.plugin.api.AuxThreadContext;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.QueryEntry;
import org.glowroot.agent.plugin.api.QueryMessageSupplier;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.Timer;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.util.ThreadAllocatedBytes;
import org.glowroot.agent.util.Tickers;
import org.glowroot.common.config.AdvancedConfig;
import org.glowroot.common.util.NotAvailableAware;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.glowroot.agent.util.Checkers.castInitialized;

public class ThreadContextImpl implements ThreadContextPlus {

    private static final boolean CAPTURE_AUXILIARY_THREAD_LOCATION_STACK_TRACES =
            Boolean.getBoolean("glowroot.debug.captureAuxiliaryThreadLocationStackTraces");

    private static final String LIMIT_EXCEEDED_BUCKET = "LIMIT EXCEEDED BUCKET";

    private static final MessageSupplier DETACHED_MESSAGE_SUPPLIER = MessageSupplier
            .create("this auxiliary thread was still running when the transaction ended");

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
    private int currentSuppressionKeyId;

    private final @Nullable ThreadStatsComponent threadStatsComponent;

    // root entry for this trace
    private final TraceEntryComponent traceEntryComponent;

    // only accessed by the thread context's thread
    private boolean transactionAsyncComplete;

    // linked lists of SyncQueryData instances for safe concurrent access
    private @MonotonicNonNull SyncQueryData headQueryData;
    private @MonotonicNonNull SyncQueryData headServiceCallData;
    // these maps are only accessed by the thread context's thread
    private @MonotonicNonNull QueryDataMap queriesForFirstType;
    private @MonotonicNonNull Map<String, QueryDataMap> allQueryTypesMap;
    private @MonotonicNonNull QueryDataMap serviceCallsForFirstType;
    private @MonotonicNonNull Map<String, QueryDataMap> allServiceCallTypesMap;

    private int queryAggregateCounter;
    private int serviceCallAggregateCounter;

    private final int maxQueryAggregates;
    private final int maxServiceCallAggregates;

    private final long threadId;

    private final boolean limitExceededAuxThreadContext;

    private final Ticker ticker;

    private final ThreadContextThreadLocal.Holder threadContextHolder;

    private @Nullable ServletRequestInfo servletRequestInfo;

    private volatile boolean mayHaveChildAuxThreadContext;

    private volatile boolean detached;

    // only ever non-null for main thread context
    private final @Nullable ThreadContextImpl outerTransactionThreadContext;

    // this is needed in for pointcuts that startTransaction() on an outer transaction thread
    // context, and then proceed to immediately call setTransaction...() on that same outer
    // transaction thread context
    private @Nullable ThreadContextImpl innerTransactionThreadContext;

    ThreadContextImpl(Transaction transaction, @Nullable TraceEntryImpl parentTraceEntry,
            @Nullable TraceEntryImpl parentThreadContextPriorEntry, MessageSupplier messageSupplier,
            TimerName rootTimerName, long startTick, boolean captureThreadStats,
            int maxQueryAggregates, int maxServiceCallAggregates,
            @Nullable ThreadAllocatedBytes threadAllocatedBytes,
            boolean limitExceededAuxThreadContext, Ticker ticker,
            ThreadContextThreadLocal.Holder threadContextHolder,
            @Nullable ServletRequestInfo servletRequestInfo, int rootNestingGroupId,
            int rootSuppressionKeyId) {
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
        this.maxQueryAggregates = maxQueryAggregates;
        this.maxServiceCallAggregates = maxServiceCallAggregates;
        this.limitExceededAuxThreadContext = limitExceededAuxThreadContext;
        this.ticker = ticker;
        this.threadContextHolder = threadContextHolder;
        this.servletRequestInfo = servletRequestInfo;
        this.outerTransactionThreadContext = (ThreadContextImpl) threadContextHolder.get();
        currentNestingGroupId = rootNestingGroupId;
        currentSuppressionKeyId = rootSuppressionKeyId;
    }

    public Transaction getTransaction() {
        return transaction;
    }

    @Nullable
    TraceEntryImpl getParentThreadContextPriorEntry() {
        return parentThreadContextPriorEntry;
    }

    TraceEntryImpl getTailEntry() {
        return traceEntryComponent.getTailEntry();
    }

    TraceEntryImpl getRootEntry() {
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

    long getCpuNanos() {
        if (threadStatsComponent == null) {
            return NotAvailableAware.NA;
        }
        return threadStatsComponent.getCpuNanos();
    }

    public long getThreadId() {
        return threadId;
    }

    boolean isCompleted() {
        return traceEntryComponent.isCompleted();
    }

    public boolean isActive() {
        // checking threadContextHolder.get() to make sure this isn't an outer transaction on hold
        // while inner transaction is executing
        return !traceEntryComponent.isCompleted() && threadContextHolder.get() == this;
    }

    public @Nullable TimerImpl getCurrentTimer() {
        return currentTimer;
    }

    void setCurrentTimer(@Nullable TimerImpl currentTimer) {
        this.currentTimer = currentTimer;
    }

    @Override
    public int getCurrentNestingGroupId() {
        return currentNestingGroupId;
    }

    @Override
    public void setCurrentNestingGroupId(int nestingGroupId) {
        this.currentNestingGroupId = nestingGroupId;
    }

    @Override
    public int getCurrentSuppressionKeyId() {
        return currentSuppressionKeyId;
    }

    @Override
    public void setCurrentSuppressionKeyId(int suppressionKeyId) {
        this.currentSuppressionKeyId = suppressionKeyId;
    }

    // FIXME why is this safe when called from another thread?
    boolean isMergeable() {
        return !mayHaveChildAuxThreadContext && traceEntryComponent.isEmpty();
    }

    void mergeQueriesInto(QueryCollector collector) {
        SyncQueryData curr = headQueryData;
        while (curr != null) {
            collector.mergeQuery(curr.getQueryType(), curr.getQueryText(),
                    curr.getTotalDurationNanos(ticker), curr.getExecutionCount(),
                    curr.hasTotalRows(), curr.getTotalRows(), curr.isActive());
            curr = curr.getNextQueryData();
        }
    }

    void mergeServiceCallsInto(ServiceCallCollector collector) {
        SyncQueryData curr = headServiceCallData;
        while (curr != null) {
            collector.mergeServiceCall(curr.getQueryType(), curr.getQueryText(),
                    curr.getTotalDurationNanos(ticker), curr.getExecutionCount());
            curr = curr.getNextQueryData();
        }
    }

    boolean getCaptureThreadStats() {
        return threadStatsComponent != null;
    }

    private boolean isCompleted(long captureTick) {
        if (!traceEntryComponent.isCompleted()) {
            return false;
        }
        return traceEntryComponent.getEndTick() >= captureTick;
    }

    // only called by transaction thread
    private SyncQueryData getOrCreateQueryData(String queryType, String queryText,
            boolean bypassLimit) {
        if (headQueryData == null) {
            queriesForFirstType = new QueryDataMap(queryType);
            return createQueryData(queriesForFirstType, queryType, queryText, bypassLimit);
        }
        QueryDataMap queriesForType = checkNotNull(queriesForFirstType);
        if (!queriesForType.getType().equals(queryType)) {
            queriesForType = getOrCreateQueriesForType(queryType);
        }
        SyncQueryData queryData = queriesForType.get(queryText);
        if (queryData == null) {
            queryData = createQueryData(queriesForType, queryType, queryText, bypassLimit);
        }
        return queryData;
    }

    private SyncQueryData createQueryData(QueryDataMap queriesForType, String queryType,
            String queryText, boolean bypassLimit) {
        if (allowAnotherQueryAggregate(bypassLimit)) {
            return createQueryData(queriesForType, queryType, queryText);
        } else {
            SyncQueryData limitExceededBucket = queriesForType.get(LIMIT_EXCEEDED_BUCKET);
            if (limitExceededBucket == null) {
                limitExceededBucket = createQueryData(queriesForType, queryType,
                        LIMIT_EXCEEDED_BUCKET);
            }
            return new SyncQueryData(queryType, queryText, null, limitExceededBucket);
        }
    }

    private SyncQueryData createQueryData(QueryDataMap queriesForType, String queryType,
            String queryText) {
        SyncQueryData queryData = new SyncQueryData(queryType, queryText, headQueryData, null);
        queriesForType.put(queryText, queryData);
        headQueryData = queryData;
        return queryData;
    }

    // only called by transaction thread
    private SyncQueryData getOrCreateServiceCallData(String serviceCallType, String serviceCallText,
            boolean bypassLimit) {
        if (headServiceCallData == null) {
            serviceCallsForFirstType = new QueryDataMap(serviceCallType);
            return createServiceCallData(serviceCallsForFirstType, serviceCallType, serviceCallText,
                    bypassLimit);
        }
        QueryDataMap serviceCallsForType = checkNotNull(serviceCallsForFirstType);
        if (!serviceCallsForType.getType().equals(serviceCallType)) {
            serviceCallsForType = getOrCreateServiceCallsForType(serviceCallType);
        }
        SyncQueryData serviceCallData = serviceCallsForType.get(serviceCallText);
        if (serviceCallData == null) {
            serviceCallData = createServiceCallData(serviceCallsForType, serviceCallType,
                    serviceCallText, bypassLimit);
        }
        return serviceCallData;
    }

    private SyncQueryData createServiceCallData(QueryDataMap serviceCallsForType,
            String serviceCallType, String serviceCallText, boolean bypassLimit) {
        if (allowAnotherServiceCallAggregate(bypassLimit)) {
            return createServiceCallData(serviceCallsForType, serviceCallType,
                    serviceCallText);
        } else {
            SyncQueryData limitExceededBucket =
                    serviceCallsForType.get(LIMIT_EXCEEDED_BUCKET);
            if (limitExceededBucket == null) {
                limitExceededBucket =
                        createServiceCallData(serviceCallsForType, serviceCallType,
                                LIMIT_EXCEEDED_BUCKET);
            }
            return new SyncQueryData(serviceCallType, serviceCallText, null, limitExceededBucket);
        }
    }

    private SyncQueryData createServiceCallData(QueryDataMap serviceCallsForType,
            String serviceCallType, String serviceCallText) {
        SyncQueryData serviceCallData =
                new SyncQueryData(serviceCallType, serviceCallText, headServiceCallData, null);
        serviceCallsForType.put(serviceCallText, serviceCallData);
        headServiceCallData = serviceCallData;
        return serviceCallData;
    }

    // this method has side effect of incrementing counter
    private boolean allowAnotherQueryAggregate(boolean bypassLimit) {
        return queryAggregateCounter++ < maxQueryAggregates
                * AdvancedConfig.OVERALL_AGGREGATE_QUERIES_HARD_LIMIT_MULTIPLIER
                || bypassLimit;
    }

    // this method has side effect of incrementing counter
    private boolean allowAnotherServiceCallAggregate(boolean bypassLimit) {
        return serviceCallAggregateCounter++ < maxServiceCallAggregates
                * AdvancedConfig.OVERALL_AGGREGATE_SERVICE_CALLS_HARD_LIMIT_MULTIPLIER
                || bypassLimit;
    }

    TraceEntryImpl addErrorEntry(long startTick, long endTick, @Nullable Object messageSupplier,
            @Nullable QueryData queryData, ErrorMessage errorMessage) {
        TraceEntryImpl entry = traceEntryComponent.addErrorEntry(startTick, endTick,
                messageSupplier, queryData, errorMessage);
        // memory barrier write ensures partial trace capture will see data collected up to now
        // memory barrier read ensures timely visibility of detach()
        transaction.memoryBarrierReadWrite();
        return entry;
    }

    private TraceEntryImpl startAsyncTraceEntry(long startTick, MessageSupplier messageSupplier,
            TimerImpl syncTimer, AsyncTimer asyncTimer) {
        TraceEntryImpl entry = traceEntryComponent.pushEntry(startTick, messageSupplier, syncTimer,
                asyncTimer, null, 0);
        // memory barrier write ensures partial trace capture will see data collected up to now
        // memory barrier read ensures timely visibility of detach()
        transaction.memoryBarrierReadWrite();
        return entry;
    }

    private TraceEntryImpl startAsyncQueryEntry(long startTick,
            QueryMessageSupplier queryMessageSupplier, TimerImpl syncTimer,
            AsyncTimer asyncTimer, @Nullable QueryData queryData, long queryExecutionCount) {
        TraceEntryImpl entry =
                traceEntryComponent.pushEntry(startTick, queryMessageSupplier, syncTimer,
                        asyncTimer, queryData, queryExecutionCount);
        // memory barrier write ensures partial trace capture will see data collected up to now
        // memory barrier read ensures timely visibility of detach()
        transaction.memoryBarrierReadWrite();
        return entry;
    }

    private TraceEntryImpl startAsyncServiceCallEntry(long startTick,
            MessageSupplier messageSupplier, TimerImpl syncTimer, AsyncTimer asyncTimer,
            @Nullable QueryData queryData) {
        TraceEntryImpl entry = traceEntryComponent.pushEntry(startTick, messageSupplier,
                syncTimer, asyncTimer, queryData, 1);
        // memory barrier write ensures partial trace capture will see data collected up to now
        // memory barrier read ensures timely visibility of detach()
        transaction.memoryBarrierReadWrite();
        return entry;
    }

    void captureStackTrace(ThreadInfo threadInfo) {
        transaction.captureStackTrace(isAuxiliary(), threadInfo);
        // memory barrier read ensures timely visibility of detach()
        transaction.memoryBarrierRead();
    }

    @Override
    public AuxThreadContext createAuxThreadContext() {
        ImmutableList<StackTraceElement> locationStackTrace = null;
        if (CAPTURE_AUXILIARY_THREAD_LOCATION_STACK_TRACES) {
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            // strip up through this method, plus 1 additional method (the plugin advice method)
            int index = getNormalizedStartIndex(stackTrace, "createAuxThreadContext", 1);
            locationStackTrace = ImmutableList.copyOf(stackTrace).subList(index, stackTrace.length);
        }
        if (limitExceededAuxThreadContext) {
            // no auxiliary thread context hierarchy after limit exceeded in order to limit the
            // retention of auxiliary thread contexts
            return new AuxThreadContextImpl(transaction, null, null, servletRequestInfo,
                    locationStackTrace, transaction.getTransactionRegistry(),
                    transaction.getTransactionService());
        } else {
            mayHaveChildAuxThreadContext = true;
            return new AuxThreadContextImpl(transaction, traceEntryComponent.getActiveEntry(),
                    traceEntryComponent.getTailEntry(), servletRequestInfo, locationStackTrace,
                    transaction.getTransactionRegistry(), transaction.getTransactionService());
        }
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
            if (threadStatsComponent != null) {
                threadStatsComponent.onComplete();
            }
            if (limitExceededAuxThreadContext) {
                // this is a limit exceeded auxiliary thread context
                transaction.mergeLimitExceededAuxThreadContext(this);
            }
            if (!isAuxiliary() || transactionAsyncComplete) {
                transaction.end(endTick, transactionAsyncComplete, false);
            }
            threadContextHolder.set(outerTransactionThreadContext);
            if (outerTransactionThreadContext != null) {
                outerTransactionThreadContext.innerTransactionThreadContext = null;
            }
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
        detached = true;
    }

    private QueryDataMap getOrCreateQueriesForType(String queryType) {
        if (allQueryTypesMap == null) {
            allQueryTypesMap = new HashMap<String, QueryDataMap>(2);
            QueryDataMap queriesForType = new QueryDataMap(queryType);
            allQueryTypesMap.put(queryType, queriesForType);
            return queriesForType;
        }
        QueryDataMap queriesForType = allQueryTypesMap.get(queryType);
        if (queriesForType == null) {
            queriesForType = new QueryDataMap(queryType);
            allQueryTypesMap.put(queryType, queriesForType);
        }
        return queriesForType;
    }

    private QueryDataMap getOrCreateServiceCallsForType(String type) {
        if (allServiceCallTypesMap == null) {
            allServiceCallTypesMap = new HashMap<String, QueryDataMap>(2);
            QueryDataMap serviceCallsForType = new QueryDataMap(type);
            allServiceCallTypesMap.put(type, serviceCallsForType);
            return serviceCallsForType;
        }
        QueryDataMap serviceCallsForType = allServiceCallTypesMap.get(type);
        if (serviceCallsForType == null) {
            serviceCallsForType = new QueryDataMap(type);
            allServiceCallTypesMap.put(type, serviceCallsForType);
        }
        return serviceCallsForType;
    }

    @Override
    public boolean isInTransaction() {
        return true;
    }

    @Override
    public TraceEntry startTransaction(String transactionType, String transactionName,
            MessageSupplier messageSupplier, TimerName timerName) {
        return startTransaction(transactionType, transactionName, messageSupplier, timerName,
                AlreadyInTransactionBehavior.CAPTURE_TRACE_ENTRY);
    }

    @Override
    public TraceEntry startTransaction(String transactionType, String transactionName,
            MessageSupplier messageSupplier, TimerName timerName,
            AlreadyInTransactionBehavior alreadyInTransactionBehavior) {
        if (transactionType == null) {
            logger.error("startTransaction(): argument 'transactionType' must be non-null");
            return NopTransactionService.TRACE_ENTRY;
        }
        if (transactionName == null) {
            logger.error("startTransaction(): argument 'transactionName' must be non-null");
            return NopTransactionService.TRACE_ENTRY;
        }
        if (messageSupplier == null) {
            logger.error("startTransaction(): argument 'messageSupplier' must be non-null");
            return NopTransactionService.TRACE_ENTRY;
        }
        if (timerName == null) {
            logger.error("startTransaction(): argument 'timerName' must be non-null");
            return NopTransactionService.TRACE_ENTRY;
        }
        // ensure visibility of recent configuration updates
        transaction.getConfigService().readMemoryBarrier();
        if (transaction.isOuter()
                || alreadyInTransactionBehavior == AlreadyInTransactionBehavior.CAPTURE_NEW_TRANSACTION) {
            TraceEntryImpl traceEntry = transaction.startInnerTransaction(transactionType,
                    transactionName, messageSupplier, timerName, threadContextHolder,
                    currentNestingGroupId, currentSuppressionKeyId);
            innerTransactionThreadContext =
                    (ThreadContextImpl) checkNotNull(threadContextHolder.get());
            return traceEntry;
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
    public TraceEntry startTraceEntry(MessageSupplier messageSupplier, TimerName timerName) {
        if (messageSupplier == null) {
            logger.error("startTraceEntry(): argument 'messageSupplier' must be non-null");
            return NopTransactionService.TRACE_ENTRY;
        }
        if (timerName == null) {
            logger.error("startTraceEntry(): argument 'timerName' must be non-null");
            return NopTransactionService.TRACE_ENTRY;
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
            return NopTransactionService.ASYNC_TRACE_ENTRY;
        }
        if (timerName == null) {
            logger.error("startAsyncTraceEntry(): argument 'timerName' must be non-null");
            return NopTransactionService.ASYNC_TRACE_ENTRY;
        }
        long startTick = ticker.read();
        TimerImpl syncTimer = startTimer(timerName, startTick);
        AsyncTimer asyncTimer = transaction.startAsyncTimer(timerName, startTick);
        if (transaction.allowAnotherEntry()) {
            return startAsyncTraceEntry(startTick, messageSupplier, syncTimer, asyncTimer);
        } else {
            return new DummyTraceEntryOrQuery(syncTimer, asyncTimer, startTick, messageSupplier,
                    null, 0);
        }
    }

    @Override
    public QueryEntry startQueryEntry(String queryType, String queryText,
            QueryMessageSupplier queryMessageSupplier, TimerName timerName) {
        if (queryType == null) {
            logger.error("startQueryEntry(): argument 'queryType' must be non-null");
            return NopTransactionService.QUERY_ENTRY;
        }
        if (queryText == null) {
            logger.error("startQueryEntry(): argument 'queryText' must be non-null");
            return NopTransactionService.QUERY_ENTRY;
        }
        if (queryMessageSupplier == null) {
            logger.error("startQueryEntry(): argument 'queryMessageSupplier' must be non-null");
            return NopTransactionService.QUERY_ENTRY;
        }
        if (timerName == null) {
            logger.error("startQueryEntry(): argument 'timerName' must be non-null");
            return NopTransactionService.QUERY_ENTRY;
        }
        long startTick = ticker.read();
        TimerImpl timer = startTimer(timerName, startTick);
        if (transaction.allowAnotherEntry()) {
            SyncQueryData queryData = getOrCreateQueryData(queryType, queryText, true);
            return traceEntryComponent.pushEntry(startTick, queryMessageSupplier, timer, null,
                    queryData, 1);
        } else {
            SyncQueryData queryData = getOrCreateQueryData(queryType, queryText, false);
            return new DummyTraceEntryOrQuery(timer, null, startTick, queryMessageSupplier,
                    queryData, 1);
        }
    }

    @Override
    public QueryEntry startQueryEntry(String queryType, String queryText, long queryExecutionCount,
            QueryMessageSupplier queryMessageSupplier, TimerName timerName) {
        if (queryType == null) {
            logger.error("startQueryEntry(): argument 'queryType' must be non-null");
            return NopTransactionService.QUERY_ENTRY;
        }
        if (queryText == null) {
            logger.error("startQueryEntry(): argument 'queryText' must be non-null");
            return NopTransactionService.QUERY_ENTRY;
        }
        if (queryExecutionCount <= 0) {
            logger.error("startQueryEntry(): argument 'queryExecutionCount' must be positive");
            return NopTransactionService.QUERY_ENTRY;
        }
        if (queryMessageSupplier == null) {
            logger.error("startQueryEntry(): argument 'queryMessageSupplier' must be non-null");
            return NopTransactionService.QUERY_ENTRY;
        }
        if (timerName == null) {
            logger.error("startQueryEntry(): argument 'timerName' must be non-null");
            return NopTransactionService.QUERY_ENTRY;
        }
        long startTick = ticker.read();
        TimerImpl timer = startTimer(timerName, startTick);
        if (transaction.allowAnotherEntry()) {
            SyncQueryData queryData = getOrCreateQueryData(queryType, queryText, true);
            return traceEntryComponent.pushEntry(startTick, queryMessageSupplier, timer, null,
                    queryData, queryExecutionCount);
        } else {
            SyncQueryData queryData = getOrCreateQueryData(queryType, queryText, false);
            return new DummyTraceEntryOrQuery(timer, null, startTick, queryMessageSupplier,
                    queryData, queryExecutionCount);
        }
    }

    @Override
    public AsyncQueryEntry startAsyncQueryEntry(String queryType, String queryText,
            QueryMessageSupplier queryMessageSupplier, TimerName timerName) {
        if (queryType == null) {
            logger.error("startAsyncQueryEntry(): argument 'queryType' must be non-null");
            return NopTransactionService.ASYNC_QUERY_ENTRY;
        }
        if (queryText == null) {
            logger.error("startAsyncQueryEntry(): argument 'queryText' must be non-null");
            return NopTransactionService.ASYNC_QUERY_ENTRY;
        }
        if (queryMessageSupplier == null) {
            logger.error(
                    "startAsyncQueryEntry(): argument 'queryMessageSupplier' must be non-null");
            return NopTransactionService.ASYNC_QUERY_ENTRY;
        }
        if (timerName == null) {
            logger.error("startAsyncQueryEntry(): argument 'timerName' must be non-null");
            return NopTransactionService.ASYNC_QUERY_ENTRY;
        }
        long startTick = ticker.read();
        TimerImpl syncTimer = startTimer(timerName, startTick);
        AsyncTimer asyncTimer = transaction.startAsyncTimer(timerName, startTick);
        if (transaction.allowAnotherEntry()) {
            AsyncQueryData queryData =
                    transaction.getOrCreateAsyncQueryData(queryType, queryText, true);
            return startAsyncQueryEntry(startTick, queryMessageSupplier, syncTimer, asyncTimer,
                    queryData, 1);
        } else {
            AsyncQueryData queryData =
                    transaction.getOrCreateAsyncQueryData(queryType, queryText, false);
            return new DummyTraceEntryOrQuery(syncTimer, asyncTimer, startTick,
                    queryMessageSupplier, queryData, 1);
        }
    }

    @Override
    public TraceEntry startServiceCallEntry(String serviceCallType, String serviceCallText,
            MessageSupplier messageSupplier, TimerName timerName) {
        if (serviceCallType == null) {
            logger.error("startServiceCallEntry(): argument 'serviceCallType' must be non-null");
            return NopTransactionService.TRACE_ENTRY;
        }
        if (serviceCallText == null) {
            logger.error("startServiceCallEntry(): argument 'serviceCallText' must be non-null");
            return NopTransactionService.TRACE_ENTRY;
        }
        if (messageSupplier == null) {
            logger.error("startServiceCallEntry(): argument 'messageSupplier' must be non-null");
            return NopTransactionService.TRACE_ENTRY;
        }
        if (timerName == null) {
            logger.error("startServiceCallEntry(): argument 'timerName' must be non-null");
            return NopTransactionService.TRACE_ENTRY;
        }
        long startTick = ticker.read();
        TimerImpl timer = startTimer(timerName, startTick);
        if (transaction.allowAnotherEntry()) {
            SyncQueryData queryData =
                    getOrCreateServiceCallData(serviceCallType, serviceCallText, true);
            return traceEntryComponent.pushEntry(startTick, messageSupplier, timer, null, queryData,
                    1);
        } else {
            SyncQueryData queryData =
                    getOrCreateServiceCallData(serviceCallType, serviceCallText, false);
            return new DummyTraceEntryOrQuery(timer, null, startTick, messageSupplier, queryData,
                    1);
        }
    }

    @Override
    public AsyncTraceEntry startAsyncServiceCallEntry(String serviceCallType,
            String serviceCallText, MessageSupplier messageSupplier, TimerName timerName) {
        if (serviceCallType == null) {
            logger.error(
                    "startAsyncServiceCallEntry(): argument 'serviceCallType' must be non-null");
            return NopTransactionService.ASYNC_TRACE_ENTRY;
        }
        if (serviceCallText == null) {
            logger.error(
                    "startAsyncServiceCallEntry(): argument 'serviceCallText' must be non-null");
            return NopTransactionService.ASYNC_TRACE_ENTRY;
        }
        if (messageSupplier == null) {
            logger.error(
                    "startAsyncServiceCallEntry(): argument 'messageSupplier' must be non-null");
            return NopTransactionService.ASYNC_TRACE_ENTRY;
        }
        if (timerName == null) {
            logger.error("startAsyncServiceCallEntry(): argument 'timerName' must be non-null");
            return NopTransactionService.ASYNC_TRACE_ENTRY;
        }
        long startTick = ticker.read();
        TimerImpl syncTimer = startTimer(timerName, startTick);
        AsyncTimer asyncTimer = transaction.startAsyncTimer(timerName, startTick);
        if (transaction.allowAnotherEntry()) {
            AsyncQueryData queryData = transaction.getOrCreateAsyncServiceCallData(serviceCallType,
                    serviceCallText, true);
            return startAsyncServiceCallEntry(startTick, messageSupplier, syncTimer, asyncTimer,
                    queryData);
        } else {
            AsyncQueryData queryData = transaction.getOrCreateAsyncServiceCallData(serviceCallType,
                    serviceCallText, false);
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
    public void setTransactionAsync() {
        if (innerTransactionThreadContext == null) {
            if (logger.isDebugEnabled() && AuxThreadContextImpl.inAuxDebugLogging.get() == null) {
                AuxThreadContextImpl.inAuxDebugLogging.set(Boolean.TRUE);
                try {
                    logger.debug("set async transaction, thread context: {}, parent thread context:"
                            + " {}, thread name: {}", hashCode(), getParentThreadContextDisplay(),
                            Thread.currentThread().getName(), new Exception());
                } finally {
                    AuxThreadContextImpl.inAuxDebugLogging.remove();
                }
            }
            transaction.setAsync();
        } else {
            innerTransactionThreadContext.setTransactionAsync();
        }
    }

    @Override
    public void setTransactionAsyncComplete() {
        if (innerTransactionThreadContext == null) {
            if (logger.isDebugEnabled() && AuxThreadContextImpl.inAuxDebugLogging.get() == null) {
                AuxThreadContextImpl.inAuxDebugLogging.set(Boolean.TRUE);
                try {
                    logger.debug("set async transaction complete, thread context: {},"
                            + " parent thread context: {}, thread name: {}", hashCode(),
                            getParentThreadContextDisplay(), Thread.currentThread().getName(),
                            new Exception());
                } finally {
                    AuxThreadContextImpl.inAuxDebugLogging.remove();
                }
            }
            transactionAsyncComplete = true;
            if (isCompleted()) {
                transaction.end(ticker.read(), true, true);
            } else {
                transaction.setWaitingToEndAsync();
            }
        } else {
            innerTransactionThreadContext.setTransactionAsyncComplete();
        }
    }

    @Override
    public void setTransactionOuter() {
        if (innerTransactionThreadContext == null) {
            transaction.setOuter();
        } else {
            innerTransactionThreadContext.setTransactionOuter();
        }
    }

    @Override
    public void setTransactionType(@Nullable String transactionType, int priority) {
        if (Strings.isNullOrEmpty(transactionType)) {
            return;
        }
        if (innerTransactionThreadContext == null) {
            transaction.setTransactionType(transactionType, priority);
        } else {
            innerTransactionThreadContext.setTransactionType(transactionType, priority);
        }
    }

    @Override
    public void setTransactionName(@Nullable String transactionName, int priority) {
        if (Strings.isNullOrEmpty(transactionName)) {
            return;
        }
        if (innerTransactionThreadContext == null) {
            transaction.setTransactionName(transactionName, priority);
        } else {
            innerTransactionThreadContext.setTransactionName(transactionName, priority);
        }
    }

    @Override
    public void setTransactionUser(@Nullable String user, int priority) {
        if (Strings.isNullOrEmpty(user)) {
            return;
        }
        if (innerTransactionThreadContext == null) {
            transaction.setUser(user, priority);
        } else {
            innerTransactionThreadContext.setTransactionUser(user, priority);
        }
    }

    @Override
    public void addTransactionAttribute(String name, @Nullable String value) {
        if (name == null) {
            logger.error("addTransactionAttribute(): argument 'name' must be non-null");
            return;
        }
        if (innerTransactionThreadContext == null) {
            transaction.addAttribute(name, value);
        } else {
            innerTransactionThreadContext.addTransactionAttribute(name, value);
        }
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
        if (innerTransactionThreadContext == null) {
            int thresholdMillis = Ints.saturatedCast(unit.toMillis(threshold));
            transaction.setSlowThresholdMillis(thresholdMillis, priority);
        } else {
            innerTransactionThreadContext.setTransactionSlowThreshold(threshold, unit, priority);
        }
    }

    @Override
    public void setTransactionError(Throwable t) {
        if (innerTransactionThreadContext == null) {
            transaction.setError(null, t);
        } else {
            innerTransactionThreadContext.setTransactionError(t);
        }
    }

    @Override
    public void setTransactionError(@Nullable String message) {
        if (Strings.isNullOrEmpty(message)) {
            return;
        }
        if (innerTransactionThreadContext == null) {
            transaction.setError(message, null);
        } else {
            innerTransactionThreadContext.setTransactionError(message);
        }
    }

    @Override
    public void setTransactionError(@Nullable String message, @Nullable Throwable t) {
        if (innerTransactionThreadContext == null) {
            transaction.setError(message, t);
        } else {
            innerTransactionThreadContext.setTransactionError(message, t);
        }
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
    public void trackResourceAcquired(Object resource, boolean withLocationStackTrace) {
        transaction.trackResourceAcquired(resource, withLocationStackTrace);
    }

    @Override
    public void trackResourceReleased(Object resource) {
        transaction.trackResourceReleased(resource);
    }

    @Override
    public @Nullable ServletRequestInfo getServletRequestInfo() {
        return servletRequestInfo;
    }

    @Override
    public void setServletRequestInfo(@Nullable ServletRequestInfo servletRequestInfo) {
        this.servletRequestInfo = servletRequestInfo;
    }

    boolean hasTraceEntries() {
        return !traceEntryComponent.isEmpty();
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
                logger.error("found non-root trace entry with null parent trace entry"
                        + "\ntrace entry: {}\ntransaction: {} - {}", entry,
                        transaction.getTransactionType(), transaction.getTransactionName());
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
        if (detached && !traceEntryComponent.isEmpty()) {
            TraceEntryImpl rootEntry = getRootEntry();
            parentChildMap.put(rootEntry,
                    new TraceEntryImpl(this, rootEntry, DETACHED_MESSAGE_SUPPLIER,
                            null, 0, transaction.getEndTick(), null, null));
        }
    }

    private boolean isAuxiliary() {
        return parentTraceEntry != null;
    }

    private void addErrorEntryInternal(@Nullable String message, @Nullable Throwable t) {
        // use higher entry limit when adding errors, but still need some kind of cap
        if (transaction.allowAnotherErrorEntry()) {
            long currTick = ticker.read();
            ErrorMessage errorMessage =
                    ErrorMessage.create(message, t, transaction.getThrowableFrameLimitCounter());
            addErrorEntry(currTick, currTick, null, null, errorMessage);
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

    private @Nullable Object getParentThreadContextDisplay() {
        if (parentTraceEntry == null) {
            return null;
        } else {
            return parentTraceEntry.getThreadContext().hashCode();
        }
    }

    static int getNormalizedStartIndex(StackTraceElement[] locationStackTrace, String methodName,
            int additionalMethodsToSkip) {
        for (int i = 0; i < locationStackTrace.length; i++) {
            if (methodName.equals(locationStackTrace[i].getMethodName())) {
                if (methodName.equals(locationStackTrace[i + 1].getMethodName())
                        && OptionalThreadContextImpl.class.getName()
                                .equals(locationStackTrace[i + 1].getClassName())) {
                    // e.g. OptionalThreadContextImpl.createAuxThreadContext()
                    // -> ThreadContextImpl.createAuxThreadContext()
                    i++;
                }
                return i + 1 + additionalMethodsToSkip;
            }
        }
        return 0;
    }

    // this does not include the root trace entry
    private class DummyTraceEntryOrQuery extends QueryEntryBase implements AsyncQueryEntry, Timer {

        private final TimerImpl syncTimer;
        private final @Nullable AsyncTimer asyncTimer;
        private final long startTick;
        private final Object messageSupplier;

        // not volatile, so depends on memory barrier in Transaction for visibility
        private int selfNestingLevel;
        // only used by transaction thread
        private @Nullable TimerImpl extendedTimer;

        private boolean initialComplete;

        public DummyTraceEntryOrQuery(TimerImpl syncTimer, @Nullable AsyncTimer asyncTimer,
                long startTick, Object messageSupplier, @Nullable QueryData queryData,
                long queryExecutionCount) {
            super(queryData, startTick, queryExecutionCount);
            this.syncTimer = syncTimer;
            this.asyncTimer = asyncTimer;
            this.startTick = startTick;
            this.messageSupplier = messageSupplier;
        }

        @Override
        public void end() {
            endInternal(ticker.read());
        }

        @Override
        public void endWithLocationStackTrace(long threshold, TimeUnit unit) {
            if (threshold < 0) {
                logger.error(
                        "endWithLocationStackTrace(): argument 'threshold' must be non-negative");
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
                ErrorMessage errorMessage = ErrorMessage.create(message, t,
                        transaction.getThrowableFrameLimitCounter());
                // entry won't be nested properly, but at least the error will get captured
                addErrorEntry(startTick, endTick, messageSupplier, getQueryData(), errorMessage);
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
                if (isAsync()) {
                    extendAsync();
                } else {
                    TimerImpl currentTimerLocal = currentTimer;
                    if (currentTimerLocal == null) {
                        // thread context has ended, cannot extend sync timer
                        // (this is ok, see https://github.com/glowroot/glowroot/issues/418)
                        selfNestingLevel--;
                        return NopTimer.INSTANCE;
                    }
                    extendSync(ticker.read(), currentTimerLocal);
                }
            }
            return this;
        }

        private void extendSync(long currTick, TimerImpl currentTimer) {
            extendedTimer = syncTimer.extend(currTick, currentTimer);
            extendQueryData(currTick);
        }

        @RequiresNonNull("asyncTimer")
        private void extendAsync() {
            ThreadContextThreadLocal.Holder holder =
                    BytecodeServiceHolder.get().getCurrentThreadContextHolder();
            ThreadContextPlus currThreadContext = holder.get();
            long currTick = ticker.read();
            if (currThreadContext == ThreadContextImpl.this) {
                // this thread context was found in ThreadContextThreadLocal.Holder, so it is still
                // active, and so current timer must be non-null
                extendSync(currTick, checkNotNull(getCurrentTimer()));
            } else {
                // set to null since its value is checked in stopAsync()
                extendedTimer = null;
                extendQueryData(currTick);
            }
            asyncTimer.extend(currTick);
        }

        // this is called for stopping an extension
        @Override
        public void stop() {
            // the timer interface for this class is only expose through return value of extend()
            if (--selfNestingLevel == 0) {
                if (isAsync()) {
                    stopAsync();
                } else {
                    stopSync(ticker.read());
                }
            }
        }

        private void stopSync(long endTick) {
            // the timer interface for this class is only expose through return value of extend()
            checkNotNull(extendedTimer).end(endTick);
            endQueryData(endTick);
        }

        @RequiresNonNull("asyncTimer")
        private void stopAsync() {
            long endTick = ticker.read();
            if (extendedTimer == null) {
                endQueryData(endTick);
                // it is not helpful to capture stack trace at end of async trace entry since it is
                // ended by a different thread (and by not capturing, it reduces thread safety
                // needs)
            } else {
                stopSync(endTick);
            }
            asyncTimer.end(endTick);
        }

        @Override
        public Object getMessageSupplier() {
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
            // this thread context was passed in from plugin, so it is still active, and so current
            // timer must be non-null
            return syncTimer.extend(checkNotNull(getCurrentTimer()));
        }

        @EnsuresNonNullIf(expression = "asyncTimer", result = true)
        private boolean isAsync() {
            return asyncTimer != null;
        }
    }
}
