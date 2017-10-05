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
package org.glowroot.agent.impl;

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

import org.glowroot.agent.model.AsyncTimerImpl;
import org.glowroot.agent.model.ErrorMessage;
import org.glowroot.agent.model.QueryCollector;
import org.glowroot.agent.model.QueryData;
import org.glowroot.agent.model.QueryDataMap;
import org.glowroot.agent.model.QueryEntryBase;
import org.glowroot.agent.model.ThreadContextPlus;
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
import org.glowroot.agent.plugin.api.internal.NopTransactionService;
import org.glowroot.agent.plugin.api.internal.NopTransactionService.NopTimer;
import org.glowroot.agent.util.ThreadAllocatedBytes;
import org.glowroot.agent.util.Tickers;
import org.glowroot.common.model.ServiceCallCollector;
import org.glowroot.common.util.NotAvailableAware;
import org.glowroot.common.util.UsedByGeneratedBytecode;

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

    // linked lists of QueryData instances for safe concurrent access
    private @MonotonicNonNull QueryData headQueryData;
    private @MonotonicNonNull QueryData headServiceCallData;
    // these maps are only accessed by the thread context's thread
    private @MonotonicNonNull QueryDataMap queriesForFirstType;
    private @MonotonicNonNull Map<String, QueryDataMap> allQueryTypesMap;
    private @MonotonicNonNull QueryDataMap serviceCallsForFirstType;
    private @MonotonicNonNull Map<String, QueryDataMap> allServiceCallTypesMap;

    private final long threadId;

    private final boolean limitExceededAuxThreadContext;

    private final Ticker ticker;

    private final ThreadContextThreadLocal.Holder threadContextHolder;

    private @Nullable ServletRequestInfo servletRequestInfo;

    private volatile boolean mayHaveChildAuxThreadContext;

    // this is not used much, so overhead of Long seems good tradeoff for avoiding extra field
    private volatile @MonotonicNonNull Long detachedTime;

    // only ever non-null for main thread context
    private final @Nullable ThreadContextImpl outerTransactionThreadContext;

    // this is needed in for pointcuts that startTransaction() on an outer transaction thread
    // context, and then proceed to immediately call setTransaction...() on that same outer
    // transaction thread context
    private @Nullable ThreadContextImpl innerTransactionThreadContext;

    ThreadContextImpl(Transaction transaction, @Nullable TraceEntryImpl parentTraceEntry,
            @Nullable TraceEntryImpl parentThreadContextPriorEntry, MessageSupplier messageSupplier,
            TimerName rootTimerName, long startTick, boolean captureThreadStats,
            @Nullable ThreadAllocatedBytes threadAllocatedBytes,
            boolean limitExceededAuxThreadContext, Ticker ticker,
            ThreadContextThreadLocal.Holder threadContextHolder,
            @Nullable ServletRequestInfo servletRequestInfo) {
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
        this.limitExceededAuxThreadContext = limitExceededAuxThreadContext;
        this.ticker = ticker;
        this.threadContextHolder = threadContextHolder;
        this.servletRequestInfo = servletRequestInfo;
        this.outerTransactionThreadContext = threadContextHolder.get();
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

    long getTotalCpuNanos() {
        if (threadStatsComponent == null) {
            return NotAvailableAware.NA;
        }
        return threadStatsComponent.getTotalCpuNanos();
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
    @UsedByGeneratedBytecode
    public int getCurrentNestingGroupId() {
        return currentNestingGroupId;
    }

    @Override
    @UsedByGeneratedBytecode
    public void setCurrentNestingGroupId(int nestingGroupId) {
        this.currentNestingGroupId = nestingGroupId;
    }

    @Override
    @UsedByGeneratedBytecode
    public int getCurrentSuppressionKeyId() {
        return currentSuppressionKeyId;
    }

    @Override
    @UsedByGeneratedBytecode
    public void setCurrentSuppressionKeyId(int suppressionKeyId) {
        this.currentSuppressionKeyId = suppressionKeyId;
    }

    boolean isCompleteAndEmptyExceptForTimersAndThreadStats() {
        return isCompleted() && !mayHaveChildAuxThreadContext && traceEntryComponent.isEmpty()
                && headQueryData == null && headServiceCallData == null;
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

    private boolean isCompleted(long captureTick) {
        if (!traceEntryComponent.isCompleted()) {
            return false;
        }
        return traceEntryComponent.getEndTick() >= captureTick;
    }

    // only called by transaction thread
    private QueryData getOrCreateQueryDataIfPossible(String queryType, String queryText,
            boolean bypassLimit) {
        if (headQueryData == null) {
            // the call to allowAnotherAggregateQuery() is needed to increment the counter
            if (!transaction.allowAnotherAggregateQuery(bypassLimit)) {
                // this only occurs if maxAggregateQueriesPerType is set to 0
                return new QueryData(queryType, queryText, null, null);
            }
            QueryData queryData = new QueryData(queryType, queryText, null, null);
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
        if (queryData == null) {
            if (transaction.allowAnotherAggregateQuery(bypassLimit)) {
                queryData = createQueryData(queriesForCurrentType, queryType, queryText);
            } else {
                QueryData limitExceededBucket = queriesForCurrentType.get(LIMIT_EXCEEDED_BUCKET);
                if (limitExceededBucket == null) {
                    limitExceededBucket = createQueryData(queriesForCurrentType, queryType,
                            LIMIT_EXCEEDED_BUCKET);
                }
                queryData = new QueryData(queryType, queryText, null, limitExceededBucket);
            }
        }
        return queryData;
    }

    private QueryData createQueryData(QueryDataMap queriesForCurrentType, String queryType,
            String queryText) {
        QueryData queryData = new QueryData(queryType, queryText, headQueryData, null);
        queriesForCurrentType.put(queryText, queryData);
        headQueryData = queryData;
        return queryData;
    }

    // only called by transaction thread
    private @Nullable QueryData getOrCreateServiceCallDataIfPossible(String serviceCallType,
            String serviceCallText) {
        if (headServiceCallData == null) {
            if (!transaction.allowAnotherAggregateServiceCall()) {
                // this only occurs if maxAggregateServiceCallsPerType is set to 0
                return null;
            }
            QueryData serviceCallData = new QueryData(serviceCallType, serviceCallText, null, null);
            serviceCallsForFirstType = new QueryDataMap(serviceCallType);
            serviceCallsForFirstType.put(serviceCallText, serviceCallData);
            headServiceCallData = serviceCallData;
            return headServiceCallData;
        }
        QueryDataMap serviceCallsForCurrentType = checkNotNull(serviceCallsForFirstType);
        if (!serviceCallsForCurrentType.getType().equals(serviceCallType)) {
            serviceCallsForCurrentType = getOrCreateServiceCallsForType(serviceCallType);
        }
        QueryData serviceCallData = serviceCallsForCurrentType.get(serviceCallText);
        if (serviceCallData == null) {
            if (transaction.allowAnotherAggregateServiceCall()) {
                serviceCallData =
                        createServiceCallData(serviceCallType, serviceCallText,
                                serviceCallsForCurrentType);
            } else {
                QueryData limitExceededBucket =
                        serviceCallsForCurrentType.get(LIMIT_EXCEEDED_BUCKET);
                if (limitExceededBucket == null) {
                    limitExceededBucket = createQueryData(serviceCallsForCurrentType,
                            serviceCallType, LIMIT_EXCEEDED_BUCKET);
                }
                serviceCallData =
                        new QueryData(serviceCallType, serviceCallText, null, limitExceededBucket);
            }
        }
        return serviceCallData;
    }

    private QueryData createServiceCallData(String serviceCallType, String serviceCallText,
            QueryDataMap serviceCallsForCurrentType) {
        QueryData serviceCallData =
                new QueryData(serviceCallType, serviceCallText, headServiceCallData, null);
        serviceCallsForCurrentType.put(serviceCallText, serviceCallData);
        headServiceCallData = serviceCallData;
        return serviceCallData;
    }

    private TraceEntryImpl addErrorEntry(long startTick, long endTick,
            @Nullable Object messageSupplier, @Nullable QueryData queryData,
            ErrorMessage errorMessage) {
        TraceEntryImpl entry = traceEntryComponent.addErrorEntry(startTick, endTick,
                messageSupplier, queryData, errorMessage);
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

    private TraceEntryImpl startAsyncQueryEntry(long startTick,
            QueryMessageSupplier queryMessageSupplier, TimerImpl syncTimer,
            AsyncTimerImpl asyncTimer, @Nullable QueryData queryData, long queryExecutionCount) {
        TraceEntryImpl entry =
                traceEntryComponent.pushEntry(startTick, queryMessageSupplier, syncTimer,
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

    void captureStackTrace(ThreadInfo threadInfo, int limit) {
        transaction.captureStackTrace(isAuxiliary(), threadInfo, limit);
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
            if (limitExceededAuxThreadContext) {
                // this is a limit exceeded auxiliary thread context
                transaction.mergeLimitExceededAuxThreadContext(this);
            }
            if (!isAuxiliary() || transactionAsyncComplete) {
                transaction.end(endTick, transactionAsyncComplete);
            }
            if (threadStatsComponent != null) {
                threadStatsComponent.onComplete();
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
        if (transaction.isOuter()) {
            TraceEntryImpl traceEntry = transaction.startInnerTransaction(transactionType,
                    transactionName, messageSupplier, timerName, threadContextHolder);
            innerTransactionThreadContext = checkNotNull(threadContextHolder.get());
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
            QueryData queryData = getOrCreateQueryDataIfPossible(queryType, queryText, true);
            return traceEntryComponent.pushEntry(startTick, queryMessageSupplier, timer, null,
                    queryData, 1);
        } else {
            QueryData queryData = getOrCreateQueryDataIfPossible(queryType, queryText, false);
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
            QueryData queryData = getOrCreateQueryDataIfPossible(queryType, queryText, true);
            return traceEntryComponent.pushEntry(startTick, queryMessageSupplier, timer, null,
                    queryData, queryExecutionCount);
        } else {
            QueryData queryData = getOrCreateQueryDataIfPossible(queryType, queryText, false);
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
        AsyncTimerImpl asyncTimer = startAsyncTimer(timerName, startTick);
        if (transaction.allowAnotherEntry()) {
            QueryData queryData = getOrCreateQueryDataIfPossible(queryType, queryText, true);
            return startAsyncQueryEntry(startTick, queryMessageSupplier, syncTimer, asyncTimer,
                    queryData, 1);
        } else {
            QueryData queryData = getOrCreateQueryDataIfPossible(queryType, queryText, false);
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
        QueryData queryData =
                getOrCreateServiceCallDataIfPossible(serviceCallType, serviceCallText);
        if (transaction.allowAnotherEntry()) {
            return traceEntryComponent.pushEntry(startTick, messageSupplier, timer, null, queryData,
                    1);
        } else {
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
        AsyncTimerImpl asyncTimer = startAsyncTimer(timerName, startTick);
        QueryData queryData =
                getOrCreateServiceCallDataIfPossible(serviceCallType, serviceCallText);
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
    public void setTransactionAsync() {
        if (innerTransactionThreadContext == null) {
            transaction.setAsync();
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
        } else {
            innerTransactionThreadContext.setTransactionAsync();
        }
    }

    @Override
    public void setTransactionAsyncComplete() {
        if (innerTransactionThreadContext == null) {
            transactionAsyncComplete = true;
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
    public @Nullable ServletRequestInfo getServletRequestInfo() {
        return servletRequestInfo;
    }

    @Override
    public void setServletRequestInfo(@Nullable ServletRequestInfo servletRequestInfo) {
        this.servletRequestInfo = servletRequestInfo;
    }

    @Override
    @Deprecated
    public @Nullable MessageSupplier getServletMessageSupplier() {
        ServletRequestInfo servletRequestInfo = getServletRequestInfo();
        if (servletRequestInfo instanceof MessageSupplier) {
            return (MessageSupplier) servletRequestInfo;
        } else {
            return null;
        }
    }

    @Override
    @Deprecated
    public void setServletMessageSupplier(@Nullable MessageSupplier messageSupplier) {
        if (messageSupplier instanceof ServletRequestInfo) {
            setServletRequestInfo((ServletRequestInfo) messageSupplier);
        }
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
        if (detachedTime != null && !traceEntryComponent.isEmpty()) {
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
            org.glowroot.agent.impl.TraceEntryImpl entry =
                    addErrorEntry(currTick, currTick, null, null, errorMessage);
            if (t == null) {
                StackTraceElement[] locationStackTrace = Thread.currentThread().getStackTrace();
                // strip up through this method, plus 2 additional methods:
                // ThreadContextImpl.addErrorEntry() and the plugin advice method
                int index = getNormalizedStartIndex(locationStackTrace, "addErrorEntryInternal", 2);
                entry.setLocationStackTrace(ImmutableList.copyOf(locationStackTrace).subList(index,
                        locationStackTrace.length));
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
                return i + 1 + additionalMethodsToSkip;
            }
        }
        return 0;
    }

    // this does not include the root trace entry
    private class DummyTraceEntryOrQuery extends QueryEntryBase implements AsyncQueryEntry, Timer {

        private final TimerImpl syncTimer;
        private final @Nullable AsyncTimerImpl asyncTimer;
        private final long startTick;
        private final Object messageSupplier;

        // not volatile, so depends on memory barrier in Transaction for visibility
        private int selfNestingLevel;
        // only used by transaction thread
        private @MonotonicNonNull TimerImpl extendedTimer;

        private boolean initialComplete;

        public DummyTraceEntryOrQuery(TimerImpl syncTimer, @Nullable AsyncTimerImpl asyncTimer,
                long startTick, Object messageSupplier, @Nullable QueryData queryData,
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
                org.glowroot.agent.impl.TraceEntryImpl entry = addErrorEntry(startTick, endTick,
                        messageSupplier, getQueryData(), errorMessage);
                if (t == null) {
                    StackTraceElement[] locationStackTrace = Thread.currentThread().getStackTrace();
                    // strip up through this method, plus 2 additional methods:
                    // DummyTraceEntryOrQuery.endWithError() and the plugin advice method
                    int index =
                            getNormalizedStartIndex(locationStackTrace, "endWithErrorInternal", 2);
                    entry.setLocationStackTrace(ImmutableList.copyOf(locationStackTrace)
                            .subList(index, locationStackTrace.length));
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
            return syncTimer.extend();
        }
    }
}
