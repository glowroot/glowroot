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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import javax.annotation.Nullable;

import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.impl.AsyncContextImpl;
import org.glowroot.agent.impl.TransactionRegistry;
import org.glowroot.agent.impl.TransactionServiceImpl;
import org.glowroot.agent.plugin.api.internal.NopAsyncService.NopAsyncContext;
import org.glowroot.agent.plugin.api.transaction.MessageSupplier;
import org.glowroot.agent.plugin.api.transaction.ThreadContext;
import org.glowroot.agent.plugin.api.transaction.TimerName;
import org.glowroot.agent.util.ThreadAllocatedBytes;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static com.google.common.base.Preconditions.checkNotNull;

public class ThreadContextImpl {

    private static final Logger logger = LoggerFactory.getLogger(ThreadContextImpl.class);

    private final Transaction transaction;
    // this is null for main thread, and non-null for auxiliary threads
    private final @Nullable TraceEntryImpl parentTraceEntry;

    private final TimerImpl rootTimer;
    // currentTimer doesn't need to be thread safe as it is only accessed by transaction thread
    private @Nullable TimerImpl currentTimer;

    private final @Nullable ThreadStatsComponent threadStatsComponent;

    // root entry for this trace
    private final TraceEntryComponent traceEntryComponent;

    // linked list of QueryData instances for safe concurrent access
    private @MonotonicNonNull QueryData headQueryData;
    // these maps are only accessed by the transaction thread
    private @MonotonicNonNull Map<String, QueryData> firstQueryTypeQueries;
    private @MonotonicNonNull Map<String, Map<String, QueryData>> allQueryTypesMap;

    private final long threadId;

    private final boolean auxiliary;

    ThreadContextImpl(Transaction transaction, @Nullable TraceEntryImpl parentTraceEntry,
            MessageSupplier messageSupplier, TimerName rootTimerName, long startTick,
            boolean captureThreadStats, @Nullable ThreadAllocatedBytes threadAllocatedBytes,
            boolean auxiliary, Ticker ticker) {
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
        return traceEntryComponent.toProtobuf(captureTick, asyncRootTraceEntries);
    }

    public @Nullable ThreadStats getThreadStats() {
        if (threadStatsComponent == null) {
            return null;
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

    public Iterator<QueryData> getQueries() {
        if (headQueryData == null) {
            return ImmutableList.<QueryData>of().iterator();
        }
        return new Iterator<QueryData>() {
            private @Nullable QueryData next = headQueryData;
            @Override
            public boolean hasNext() {
                return next != null;
            }
            @Override
            public QueryData next() {
                QueryData curr = next;
                if (curr == null) {
                    throw new NoSuchElementException();
                }
                next = curr.getNextQueryData();
                return curr;
            }
            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }

    // only called by transaction thread
    public @Nullable QueryData getOrCreateQueryDataIfPossible(String queryType, String queryText) {
        if (headQueryData == null) {
            if (!transaction.allowAnotherAggregateQuery()) {
                return null;
            }
            QueryData queryData = new QueryData(queryType, queryText, null);
            // TODO build a micro-optimized query data map, e.g. NestedTimerMap
            firstQueryTypeQueries = new HashMap<String, QueryData>(4);
            firstQueryTypeQueries.put(queryText, queryData);
            headQueryData = queryData;
            return headQueryData;
        }
        Map<String, QueryData> currentQueryTypeQueries;
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
        transaction.writeMemoryBarrier();
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
        transaction.writeMemoryBarrier();
        return entry;
    }

    public void captureStackTrace(ThreadInfo threadInfo, int limit, boolean timerWrapperMethods) {
        transaction.captureStackTrace(auxiliary, threadInfo, limit, timerWrapperMethods);
    }

    public ThreadContext createAsyncContext(TransactionRegistry transactionRegistry,
            TransactionServiceImpl transactionService) {
        TraceEntryImpl activeEntry = traceEntryComponent.getActiveEntry();
        if (activeEntry == null) {
            logger.warn("cannot create async context because active entry is null");
            return NopAsyncContext.INSTANCE;
        }
        return new AsyncContextImpl(transaction, activeEntry, transactionRegistry,
                transactionService);
    }

    // typically pop() methods don't require the objects to pop, but for safety, the entry to pop is
    // passed in just to make sure it is the one on top (and if not, then pop until is is found,
    // preventing any nasty bugs from a missed pop, e.g. a trace never being marked as complete)
    void popEntry(TraceEntryImpl entry, long endTick) {
        traceEntryComponent.popEntry(entry, endTick);
        transaction.writeMemoryBarrier();
        if (traceEntryComponent.isCompleted()) {
            if (auxiliary) {
                transaction.endAuxThreadContext();
            } else {
                transaction.end(endTick);
            }
            if (threadStatsComponent != null) {
                threadStatsComponent.onComplete();
            }
        }
    }

    private Map<String, QueryData> getOrCreateQueriesForQueryType(String queryType) {
        if (allQueryTypesMap == null) {
            allQueryTypesMap = new HashMap<String, Map<String, QueryData>>(2);
            Map<String, QueryData> currentQueryTypeQueries = new HashMap<String, QueryData>(4);
            allQueryTypesMap.put(queryType, currentQueryTypeQueries);
            return currentQueryTypeQueries;
        }
        Map<String, QueryData> currentQueryTypeQueries = allQueryTypesMap.get(queryType);
        if (currentQueryTypeQueries == null) {
            currentQueryTypeQueries = new HashMap<String, QueryData>(4);
            allQueryTypesMap.put(queryType, currentQueryTypeQueries);
        }
        return currentQueryTypeQueries;
    }

    @SuppressWarnings("return.type.incompatible")
    private static <T> /*@Initialized*/ T castInitialized(/*@UnderInitialization*/ T obj) {
        return obj;
    }
}
