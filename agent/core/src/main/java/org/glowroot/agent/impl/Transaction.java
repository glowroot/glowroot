/*
 * Copyright 2011-2019 the original author or authors.
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

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.Collection;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.concurrent.GuardedBy;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.base.Ticker;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.google.common.collect.TreeMultimap;
import com.google.common.io.BaseEncoding;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.bytecode.api.ThreadContextThreadLocal;
import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.model.AggregatedTimer;
import org.glowroot.agent.model.AsyncQueryData;
import org.glowroot.agent.model.AsyncTimer;
import org.glowroot.agent.model.ErrorMessage;
import org.glowroot.agent.model.MergedThreadTimer;
import org.glowroot.agent.model.QueryCollector;
import org.glowroot.agent.model.ServiceCallCollector;
import org.glowroot.agent.model.SharedQueryTextCollection;
import org.glowroot.agent.model.ThreadProfile;
import org.glowroot.agent.model.ThreadStats;
import org.glowroot.agent.model.TransactionTimer;
import org.glowroot.agent.plugin.api.Message;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.ThreadContext.ServletRequestInfo;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.internal.ReadableMessage;
import org.glowroot.agent.util.IterableWithSelfRemovableEntries.SelfRemovableEntry;
import org.glowroot.agent.util.ThreadAllocatedBytes;
import org.glowroot.common.config.AdvancedConfig;
import org.glowroot.common.util.Cancellable;
import org.glowroot.common.util.NotAvailableAware;
import org.glowroot.common.util.Traverser;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.glowroot.agent.util.Checkers.castInitialized;

// contains all data that has been captured for a given transaction (e.g. a servlet request)
//
// this class needs to be thread safe, only one thread updates it, but multiple threads can read it
// at the same time as it is being updated
public class Transaction {

    private static final Logger logger = LoggerFactory.getLogger(Transaction.class);

    static final int USE_GENERAL_STORE_THRESHOLD = -1;

    static final String AUXILIARY_THREAD_MESSAGE = "auxiliary thread";

    // initial capacity is very important, see ThreadSafeCollectionOfTenBenchmark
    private static final int ATTRIBUTE_KEYS_INITIAL_CAPACITY = 16;

    // this is just to limit memory (and also to limit display size of trace)
    private static final long ATTRIBUTE_VALUES_PER_KEY_LIMIT = 1000;

    // this is only to limit memory
    private static final int TRANSACTION_AUX_THREAD_CONTEXT_LIMIT =
            Integer.getInteger("glowroot.transaction.aux.thread.context.limit", 1000);

    private static final StackTraceElement[] EMPTY_STACK_TRACE = new StackTraceElement[0];

    private static final Random random = new Random();

    private volatile @Nullable String traceId;

    private final long startTime;
    private final long startTick;

    private volatile boolean async;
    private volatile boolean outer;

    private volatile String transactionType;
    private volatile int transactionTypePriority = Integer.MIN_VALUE;

    private volatile String transactionName;
    private volatile int transactionNamePriority = Integer.MIN_VALUE;

    private volatile @Nullable String user;
    private volatile int userPriority = Integer.MIN_VALUE;

    private final Object attributesLock = new Object();
    // lazy loaded to reduce memory when custom attributes are not used
    @GuardedBy("attributesLock")
    private @MonotonicNonNull SetMultimap<String, String> attributes;

    // trace-level error
    private volatile @Nullable ErrorMessage errorMessage;

    private final int maxTraceEntries;
    private final int maxQueryAggregates;
    private final int maxServiceCallAggregates;
    private final int maxProfileSamples;

    private final TransactionRegistry transactionRegistry;
    private final TransactionService transactionService;
    private final ConfigService configService;

    // stack trace data constructed from profiling
    private volatile @MonotonicNonNull ThreadProfile mainThreadProfile;
    private volatile @MonotonicNonNull ThreadProfile auxThreadProfile;

    // overrides general store threshold
    // -1 means don't override the general store threshold
    private volatile int slowThresholdMillis = USE_GENERAL_STORE_THRESHOLD;
    private volatile int slowThresholdMillisPriority = Integer.MIN_VALUE;

    // this is stored in the trace so it is only scheduled a single time, and also so it can be
    // canceled at trace completion
    private volatile @MonotonicNonNull Cancellable immedateTraceStoreRunnable;

    private volatile boolean partiallyStored;

    private volatile long captureTime;

    // memory barrier is used to ensure memory visibility of entries and timers at key points,
    // namely after each entry
    //
    // benchmarking shows this is significantly faster than ensuring memory visibility of each
    // timer update, the down side is that the latest updates to timers for transactions
    // that are captured in-flight (e.g. partial traces and active traces displayed in the UI) may
    // not be visible
    private volatile boolean memoryBarrier;

    private final CompletionCallback completionCallback;

    // ideally would use AtomicInteger here, but using plain volatile int as optimization since
    // it's ok if race condition in limit check
    private volatile int entryLimitCounter;
    private volatile int extraErrorEntryLimitCounter;

    private volatile @Nullable AtomicInteger throwableFrameLimitCounter;

    private final ThreadContextImpl mainThreadContext;

    @GuardedBy("mainThreadContext")
    private @MonotonicNonNull List<ThreadContextImpl> auxThreadContexts;
    @GuardedBy("mainThreadContext")
    private @MonotonicNonNull List<ThreadContextImpl> unmergeableAuxThreadContexts;
    @GuardedBy("mainThreadContext")
    private @MonotonicNonNull Set<ThreadContextImpl> unmergedLimitExceededAuxThreadContexts;

    private final Object asyncComponentsInitLock = new Object();
    private volatile @MonotonicNonNull AsyncComponents asyncComponents;

    private final Object sharedQueryTextCollectionLock = new Object();
    @GuardedBy("sharedQueryTextCollectionLock")
    private @MonotonicNonNull SharedQueryTextCollectionImpl sharedQueryTextCollection;

    private Map<Object, StackTraceElement[]> unreleasedResources = Maps.newConcurrentMap();

    private volatile boolean waitingToEndAsync;
    private volatile boolean completed;
    private volatile long endTick;

    private final Ticker ticker;

    private @Nullable SelfRemovableEntry transactionEntry;

    @GuardedBy("mainThreadContext")
    private @MonotonicNonNull RootTimerCollectorImpl alreadyMergedAuxThreadTimers;
    @GuardedBy("mainThreadContext")
    private @MonotonicNonNull ThreadStatsCollectorImpl alreadyMergedAuxThreadStats;
    @GuardedBy("mainThreadContext")
    private @MonotonicNonNull QueryCollector alreadyMergedAuxQueries;
    @GuardedBy("mainThreadContext")
    private @MonotonicNonNull ServiceCallCollector alreadyMergedAuxServiceCalls;
    @GuardedBy("mainThreadContext")
    private boolean stopMergingAuxThreadContexts;

    Transaction(long startTime, long startTick, String transactionType, String transactionName,
            MessageSupplier messageSupplier, TimerName timerName, boolean captureThreadStats,
            int maxTraceEntries, int maxQueryAggregates, int maxServiceCallAggregates,
            int maxProfileSamples, @Nullable ThreadAllocatedBytes threadAllocatedBytes,
            CompletionCallback completionCallback, Ticker ticker,
            TransactionRegistry transactionRegistry, TransactionService transactionService,
            ConfigService configService, ThreadContextThreadLocal.Holder threadContextHolder,
            int rootNestingGroupId, int rootSuppressionKeyId) {
        this.startTime = startTime;
        this.startTick = startTick;
        this.transactionType = transactionType;
        this.transactionName = transactionName;
        this.maxTraceEntries = maxTraceEntries;
        this.maxQueryAggregates = maxQueryAggregates;
        this.maxServiceCallAggregates = maxServiceCallAggregates;
        this.maxProfileSamples = maxProfileSamples;
        this.completionCallback = completionCallback;
        this.ticker = ticker;
        this.transactionRegistry = transactionRegistry;
        this.transactionService = transactionService;
        this.configService = configService;
        mainThreadContext = new ThreadContextImpl(castInitialized(this), null, null,
                messageSupplier, timerName, startTick, captureThreadStats, maxQueryAggregates,
                maxServiceCallAggregates, threadAllocatedBytes, false, ticker, threadContextHolder,
                null, rootNestingGroupId, rootSuppressionKeyId);
    }

    long getStartTime() {
        return startTime;
    }

    public String getTraceId() {
        if (traceId == null) {
            // double-checked locking works here because traceId is volatile
            //
            // synchronized on "this" as a micro-optimization just so don't need to create an empty
            // object to lock on
            synchronized (this) {
                if (traceId == null) {
                    traceId = buildTraceId(startTime);
                }
            }
        }
        return traceId;
    }

    public long getStartTick() {
        return startTick;
    }

    public boolean isCompleted() {
        return completed;
    }

    public boolean isFullyCompleted() {
        return completed && captureTime != 0;
    }

    long getEndTick() {
        return endTick;
    }

    public long getDurationNanos() {
        return completed ? endTick - startTick : ticker.read() - startTick;
    }

    public String getTransactionType() {
        return transactionType;
    }

    public String getTransactionName() {
        return transactionName;
    }

    public String getHeadline() {
        Object messageSupplier = mainThreadContext.getRootEntry().getMessageSupplier();
        // root trace entry messageSupplier is never null
        checkNotNull(messageSupplier);
        // root trace entry messageSupplier is never QueryMessageSupplier
        return ((ReadableMessage) ((MessageSupplier) messageSupplier).get()).getText();
    }

    public String getUser() {
        return Strings.nullToEmpty(user);
    }

    public SetMultimap<String, String> getAttributes() {
        synchronized (attributesLock) {
            if (attributes == null) {
                return ImmutableSetMultimap.of();
            }
            SetMultimap<String, String> orderedAttributes = TreeMultimap.create();
            orderedAttributes.putAll(attributes);
            return orderedAttributes;
        }
    }

    Map<String, ?> getDetail() {
        Object messageSupplier = mainThreadContext.getRootEntry().getMessageSupplier();
        // root trace entry messageSupplier is never null
        checkNotNull(messageSupplier);
        // root trace entry messageSupplier is never QueryMessageSupplier
        return ((ReadableMessage) ((MessageSupplier) messageSupplier).get()).getDetail();
    }

    @Nullable
    List<StackTraceElement> getLocationStackTrace() {
        return mainThreadContext.getRootEntry().getLocationStackTrace();
    }

    public @Nullable ErrorMessage getErrorMessage() {
        // don't prefer the root entry error message since it is likely a more generic error
        // message, e.g. servlet response sendError(500)
        if (errorMessage != null) {
            return errorMessage;
        }
        return mainThreadContext.getRootEntry().getErrorMessage();
    }

    boolean isAsync() {
        return async;
    }

    boolean isOuter() {
        return outer;
    }

    TimerImpl getMainThreadRootTimer() {
        memoryBarrierRead();
        return mainThreadContext.getRootTimer();
    }

    boolean hasAuxThreadContexts() {
        synchronized (mainThreadContext) {
            return auxThreadContexts != null;
        }
    }

    void mergeAuxThreadTimersInto(AggregatedTimer rootAuxThreadTimer) {
        synchronized (mainThreadContext) {
            if (auxThreadContexts == null) {
                return;
            }
            if (alreadyMergedAuxThreadTimers != null) {
                for (MergedThreadTimer rootTimer : alreadyMergedAuxThreadTimers.getRootTimers()) {
                    rootAuxThreadTimer.addDataFrom(rootTimer);
                }
            }
            for (ThreadContextImpl auxThreadContext : getUnmergedAuxThreadContext()) {
                rootAuxThreadTimer.addDataFrom(auxThreadContext.getRootTimer());
            }
        }
    }

    boolean hasAsyncTimers() {
        return asyncComponents != null;
    }

    void mergeAsyncTimersInto(RootTimerCollector asyncTimers) {
        if (asyncComponents != null) {
            asyncComponents.mergeAsyncTimersInto(asyncTimers);
        }
    }

    // can be called from a non-transaction thread
    ThreadStats getMainThreadStats() {
        return mainThreadContext.getThreadStats();
    }

    public long getCpuNanos() {
        long cpuNanos = mainThreadContext.getCpuNanos();
        synchronized (mainThreadContext) {
            if (auxThreadContexts == null) {
                return cpuNanos;
            }
            if (alreadyMergedAuxThreadStats != null) {
                cpuNanos =
                        NotAvailableAware.add(cpuNanos, alreadyMergedAuxThreadStats.getCpuNanos());
            }
            for (ThreadContextImpl auxThreadContext : getUnmergedAuxThreadContext()) {
                cpuNanos =
                        NotAvailableAware.add(cpuNanos, auxThreadContext.getCpuNanos());
            }
        }
        return cpuNanos;
    }

    void mergeAuxThreadStatsInto(ThreadStatsCollector collector) {
        synchronized (mainThreadContext) {
            if (auxThreadContexts == null) {
                return;
            }
            if (alreadyMergedAuxThreadStats != null) {
                collector.mergeThreadStats(alreadyMergedAuxThreadStats.getMergedThreadStats());
            }
            for (ThreadContextImpl auxThreadContext : getUnmergedAuxThreadContext()) {
                collector.mergeThreadStats(auxThreadContext.getThreadStats());
            }
        }
    }

    void mergeQueriesInto(QueryCollector collector) {
        memoryBarrierRead();
        mainThreadContext.mergeQueriesInto(collector);
        synchronized (mainThreadContext) {
            if (auxThreadContexts != null) {
                if (alreadyMergedAuxQueries != null) {
                    alreadyMergedAuxQueries.mergeQueriesInto(collector);
                }
                for (ThreadContextImpl auxThreadContext : getUnmergedAuxThreadContext()) {
                    auxThreadContext.mergeQueriesInto(collector);
                }
            }
        }
        if (asyncComponents != null) {
            asyncComponents.mergeQueriesInto(collector);
        }
    }

    public List<Aggregate.Query> getQueries() {
        synchronized (sharedQueryTextCollectionLock) {
            if (sharedQueryTextCollection == null) {
                sharedQueryTextCollection = new SharedQueryTextCollectionImpl();
            }
            return getQueriesInternal(sharedQueryTextCollection);
        }
    }

    int getQueryCount() {
        return getQueriesInternal(new NopSharedQueryTextCollection()).size();
    }

    private List<Aggregate.Query> getQueriesInternal(
            SharedQueryTextCollection sharedQueryTextCollection) {
        QueryCollector collector = new QueryCollector(maxQueryAggregates,
                AdvancedConfig.OVERALL_AGGREGATE_QUERIES_HARD_LIMIT_MULTIPLIER);
        mergeQueriesInto(collector);
        return collector.toAggregateProto(sharedQueryTextCollection, true);
    }

    public List<String> getSharedQueryTexts() {
        synchronized (sharedQueryTextCollectionLock) {
            if (sharedQueryTextCollection == null) {
                sharedQueryTextCollection = new SharedQueryTextCollectionImpl();
            }
            return ImmutableList.copyOf(sharedQueryTextCollection.sharedQueryTexts);
        }
    }

    void mergeServiceCallsInto(ServiceCallCollector collector) {
        memoryBarrierRead();
        mainThreadContext.mergeServiceCallsInto(collector);
        synchronized (mainThreadContext) {
            if (auxThreadContexts != null) {
                if (alreadyMergedAuxServiceCalls != null) {
                    alreadyMergedAuxServiceCalls.mergeServiceCallsInto(collector);
                }
                for (ThreadContextImpl auxThreadContext : getUnmergedAuxThreadContext()) {
                    auxThreadContext.mergeServiceCallsInto(collector);
                }
            }
        }
        if (asyncComponents != null) {
            asyncComponents.mergeServiceCallsInto(collector);
        }
    }

    // this method has side effect of incrementing counter
    boolean allowAnotherEntry() {
        return entryLimitCounter++ < maxTraceEntries;
    }

    // this method has side effect of incrementing counter
    boolean allowAnotherErrorEntry() {
        // use higher entry limit when adding errors, but still need some kind of cap
        return entryLimitCounter++ < maxTraceEntries
                || extraErrorEntryLimitCounter++ < maxTraceEntries;
    }

    public void visitEntries(long captureTick, TraceEntryVisitor entryVisitor) {
        synchronized (sharedQueryTextCollectionLock) {
            if (sharedQueryTextCollection == null) {
                sharedQueryTextCollection = new SharedQueryTextCollectionImpl();
            }
            visitEntriesInternal(captureTick, entryVisitor, sharedQueryTextCollection);
        }
    }

    int getEntryCount(long captureTick) {
        CountingEntryVisitor entryVisitor = new CountingEntryVisitor();
        visitEntriesInternal(captureTick, entryVisitor, new NopSharedQueryTextCollection());
        return entryVisitor.count;
    }

    private void visitEntriesInternal(long captureTick, TraceEntryVisitor entryVisitor,
            SharedQueryTextCollection sharedQueryTextCollection) {
        memoryBarrierRead();
        ListMultimap<TraceEntryImpl, ThreadContextImpl> priorEntryChildThreadContextMap =
                buildPriorEntryChildThreadContextMap();
        ListMultimap<TraceEntryImpl, TraceEntryImpl> parentChildMap = ArrayListMultimap.create();
        mainThreadContext.populateParentChildMap(parentChildMap, captureTick,
                priorEntryChildThreadContextMap);
        synchronized (mainThreadContext) {
            if (auxThreadContexts != null) {
                for (ThreadContextImpl auxThreadContext : getUnmergedAuxThreadContext()) {
                    auxThreadContext.populateParentChildMap(parentChildMap, captureTick,
                            priorEntryChildThreadContextMap);
                }
            }
        }
        new ParentChildMapTrimmer(mainThreadContext.getRootEntry(), parentChildMap, captureTick)
                .traverse();
        addProtobufChildEntries(mainThreadContext.getRootEntry(), parentChildMap, startTick,
                captureTick, 0, entryVisitor, sharedQueryTextCollection, async);
    }

    long getMainThreadProfileSampleCount() {
        if (mainThreadProfile == null) {
            return 0;
        } else {
            return mainThreadProfile.getSampleCount();
        }
    }

    @Nullable
    ThreadProfile getMainThreadProfile() {
        return mainThreadProfile;
    }

    public @Nullable Profile getMainThreadProfileProtobuf() {
        if (mainThreadProfile == null) {
            return null;
        }
        return mainThreadProfile.toProto();
    }

    boolean isMainThreadProfileSampleLimitExceeded(long profileSampleCount) {
        return profileSampleCount >= maxProfileSamples && mainThreadProfile != null
                && mainThreadProfile.isSampleLimitExceeded();
    }

    long getAuxThreadProfileSampleCount() {
        if (auxThreadProfile == null) {
            return 0;
        } else {
            return auxThreadProfile.getSampleCount();
        }
    }

    @Nullable
    ThreadProfile getAuxThreadProfile() {
        return auxThreadProfile;
    }

    public @Nullable Profile getAuxThreadProfileProtobuf() {
        if (auxThreadProfile == null) {
            return null;
        }
        return auxThreadProfile.toProto();
    }

    boolean isAuxThreadProfileSampleLimitExceeded(long profileSampleCount) {
        return profileSampleCount >= maxProfileSamples && auxThreadProfile != null
                && auxThreadProfile.isSampleLimitExceeded();
    }

    int getSlowThresholdMillisOverride() {
        return slowThresholdMillis;
    }

    public @Nullable Cancellable getImmedateTraceStoreRunnable() {
        return immedateTraceStoreRunnable;
    }

    public boolean isPartiallyStored() {
        return partiallyStored;
    }

    public ThreadContextImpl getMainThreadContext() {
        return mainThreadContext;
    }

    public List<ThreadContextImpl> getActiveAuxThreadContexts() {
        synchronized (mainThreadContext) {
            if (auxThreadContexts == null) {
                return ImmutableList.of();
            }
            List<ThreadContextImpl> activeAuxThreadContexts = Lists.newArrayList();
            for (ThreadContextImpl auxThreadContext : getUnmergedAuxThreadContext()) {
                if (auxThreadContext.isActive()) {
                    activeAuxThreadContexts.add(auxThreadContext);
                }
            }
            return activeAuxThreadContexts;
        }
    }

    void setAsync() {
        async = true;
    }

    void setOuter() {
        outer = true;
    }

    void setTransactionType(String transactionType, int priority) {
        if (priority > transactionTypePriority && !transactionType.isEmpty()) {
            this.transactionType = transactionType;
            transactionTypePriority = priority;
        }
    }

    void setTransactionName(String transactionName, int priority) {
        if (priority > transactionNamePriority && !transactionName.isEmpty()) {
            this.transactionName = transactionName;
            transactionNamePriority = priority;
        }
    }

    void setUser(String user, int priority) {
        if (priority > userPriority && !user.isEmpty()) {
            this.user = user;
            userPriority = priority;
        }
    }

    void addAttribute(String name, @Nullable String value) {
        synchronized (attributesLock) {
            if (attributes == null) {
                // no race condition here since only transaction thread calls addAttribute()
                attributes = HashMultimap.create(ATTRIBUTE_KEYS_INITIAL_CAPACITY, 1);
            }
            String val = Strings.nullToEmpty(value);
            Collection<String> values = attributes.get(name);
            if (values.size() < ATTRIBUTE_VALUES_PER_KEY_LIMIT) {
                values.add(val);
            }
        }
    }

    void setError(@Nullable String message, @Nullable Throwable t) {
        if (this.errorMessage == null) {
            this.errorMessage = ErrorMessage.create(message, t, getThrowableFrameLimitCounter());
        }
    }

    void setSlowThresholdMillis(int slowThresholdMillis, int priority) {
        if (priority > slowThresholdMillisPriority) {
            this.slowThresholdMillis = slowThresholdMillis;
            slowThresholdMillisPriority = priority;
        } else if (priority == slowThresholdMillisPriority) {
            // use the minimum threshold from the same override source
            this.slowThresholdMillis = Math.min(this.slowThresholdMillis, slowThresholdMillis);
        }
    }

    public void setImmediateTraceStoreRunnable(Cancellable immedateTraceStoreRunnable) {
        if (this.immedateTraceStoreRunnable != null) {
            logger.warn("setImmediateTraceStoreRunnable(): overwriting non-null"
                    + " immedateTraceStoreRunnable");
        }
        this.immedateTraceStoreRunnable = immedateTraceStoreRunnable;
    }

    void setPartiallyStored() {
        partiallyStored = true;
    }

    void setTransactionEntry(SelfRemovableEntry transactionEntry) {
        this.transactionEntry = transactionEntry;
    }

    void removeFromActiveTransactions() {
        checkNotNull(transactionEntry).remove();
    }

    @Nullable
    ThreadContextImpl startAuxThreadContext(@Nullable TraceEntryImpl parentTraceEntry,
            @Nullable TraceEntryImpl parentThreadContextPriorEntry, TimerName auxTimerName,
            long startTick, ThreadContextThreadLocal.Holder threadContextHolder,
            @Nullable ServletRequestInfo servletRequestInfo,
            @Nullable ThreadAllocatedBytes threadAllocatedBytes) {
        ThreadContextImpl auxThreadContext;
        synchronized (mainThreadContext) {
            // check completed and add aux thread context inside synchronized block to avoid race
            // condition with setting completed and detaching incomplete aux thread contexts, see
            // synchronized block in end()
            if (completed) {
                return null;
            }
            if (auxThreadContexts == null) {
                auxThreadContexts = Lists.newArrayList();
            }
            // conditions below for parentTraceEntry and parentThreadContextPriorEntry are redundant
            // since they will not be null until after allowAnotherAuxThreadContextWithHierarchy()
            // starts returning false
            if (allowAnotherAuxThreadContextWithTraceEntries() && parentTraceEntry != null
                    && parentThreadContextPriorEntry != null) {
                auxThreadContext = new ThreadContextImpl(this, parentTraceEntry,
                        parentThreadContextPriorEntry, AuxThreadRootMessageSupplier.INSTANCE,
                        auxTimerName, startTick, mainThreadContext.getCaptureThreadStats(),
                        maxQueryAggregates, maxServiceCallAggregates, threadAllocatedBytes, false,
                        ticker, threadContextHolder, servletRequestInfo, 0, 0);
                auxThreadContexts.add(auxThreadContext);
            } else {
                auxThreadContext = new ThreadContextImpl(this, mainThreadContext.getRootEntry(),
                        mainThreadContext.getTailEntry(), AuxThreadRootMessageSupplier.INSTANCE,
                        auxTimerName, startTick, mainThreadContext.getCaptureThreadStats(),
                        maxQueryAggregates, maxServiceCallAggregates, threadAllocatedBytes, true,
                        ticker, threadContextHolder, servletRequestInfo, 0, 0);
                if (unmergedLimitExceededAuxThreadContexts == null) {
                    unmergedLimitExceededAuxThreadContexts = Sets.newHashSet();
                }
                unmergedLimitExceededAuxThreadContexts.add(auxThreadContext);
            }
        }
        // see counterpart to this synchronization (and explanation) in ThreadContextImpl.detach()
        synchronized (threadContextHolder) {
            threadContextHolder.set(auxThreadContext);
        }
        return auxThreadContext;
    }

    void mergeLimitExceededAuxThreadContext(ThreadContextImpl auxThreadContext) {
        synchronized (mainThreadContext) {
            checkNotNull(unmergedLimitExceededAuxThreadContexts).remove(auxThreadContext);
            if (auxThreadContext.hasTraceEntries()) {
                checkNotNull(auxThreadContexts).add(auxThreadContext);
                return;
            }
            initAlreadyMergedAuxComponentsIfNeeded();
            mergeAux(auxThreadContext);
        }
    }

    AsyncTimer startAsyncTimer(TimerName asyncTimerName, long startTick) {
        return getOrInitAsyncComponents().startAsyncTimer(asyncTimerName, startTick);
    }

    AsyncQueryData getOrCreateAsyncQueryData(String queryType, String queryText,
            boolean bypassLimit) {
        return getOrInitAsyncComponents().getOrCreateAsyncQueryData(queryType, queryText,
                bypassLimit);
    }

    AsyncQueryData getOrCreateAsyncServiceCallData(String serviceCallType, String serviceCallText,
            boolean bypassLimit) {
        return getOrInitAsyncComponents().getOrCreateAsyncServiceCallData(serviceCallType,
                serviceCallText, bypassLimit);
    }

    TraceEntryImpl startInnerTransaction(String transactionType, String transactionName,
            MessageSupplier messageSupplier, TimerName timerName,
            ThreadContextThreadLocal.Holder threadContextHolder, int rootNestingGroupId,
            int rootSuppressionKeyId) {
        return transactionService.startTransaction(transactionType, transactionName,
                messageSupplier, timerName, threadContextHolder, rootNestingGroupId,
                rootSuppressionKeyId);
    }

    boolean isEntryLimitExceeded(int entryCount) {
        return entryCount >= maxTraceEntries && entryLimitCounter > maxTraceEntries;
    }

    boolean isQueryLimitExceeded(int queryCount) {
        // "LIMIT EXCEEDED BUCKET" will always push query count over the max
        return queryCount > maxQueryAggregates;
    }

    void captureStackTrace(boolean auxiliary, ThreadInfo threadInfo) {
        if (completed) {
            return;
        }
        ThreadProfile profile;
        if (auxiliary) {
            profile = auxThreadProfile;
        } else {
            profile = mainThreadProfile;
        }
        if (profile == null) {
            // initialization possible race condition (between StackTraceCollector and
            // UserProfileRunnable) is ok, worst case scenario it misses an almost simultaneously
            // captured stack trace
            //
            // profile is constructed and first stack trace is added prior to setting the
            // transaction profile field, so that it is not possible to read a profile that doesn't
            // have at least one stack trace
            profile = new ThreadProfile(maxProfileSamples);
            profile.addStackTrace(threadInfo);
            if (auxiliary) {
                auxThreadProfile = profile;
            } else {
                mainThreadProfile = profile;
            }
            return;
        }
        profile.addStackTrace(threadInfo);
    }

    void trackResourceAcquired(Object resource, boolean withLocationStackTrace) {
        if (withLocationStackTrace) {
            unreleasedResources.put(resource, Thread.currentThread().getStackTrace());
        } else {
            unreleasedResources.put(resource, EMPTY_STACK_TRACE);
        }
    }

    void trackResourceReleased(Object resource) {
        // not checking if resource was really in map, because it's _possible_ that it's intentional
        // for one transaction to release a resource that was acquired by another transaction
        unreleasedResources.remove(resource);
    }

    void setWaitingToEndAsync() {
        waitingToEndAsync = true;
    }

    void end(long endTick, boolean completeAsyncTransaction,
            boolean isSetTransactionAsyncComplete) {
        if (async && (!completeAsyncTransaction
                || waitingToEndAsync && isSetTransactionAsyncComplete)) {
            return;
        }
        synchronized (mainThreadContext) {
            if (completed) {
                // protect against plugin calling setTransactionAsyncComplete() multiple times,
                // potentially from different threads (e.g. netty plugin ending transaction by
                // sending LastHttpContent at the same time client disconnects)
                return;
            }
            // set endTick first before completed, to avoid race condition in getDurationNanos()
            this.endTick = endTick;
            // set completed and detach incomplete aux thread contexts inside synchronized block
            // to avoid race condition with adding new aux thread contexts, see synchronized block
            // in startAuxThreadContext()
            completed = true;
            detachIncompleteAuxThreadContexts();
        }
        if (!unreleasedResources.isEmpty()) {
            boolean first = true;
            for (Map.Entry<Object, StackTraceElement[]> entry : unreleasedResources.entrySet()) {
                ErrorMessage errorMessage = ErrorMessage.create("Resource leaked", null,
                        getThrowableFrameLimitCounter());
                if (first) {
                    this.errorMessage = errorMessage;
                }
                StackTraceElement[] locationStackTrace = entry.getValue();
                TraceEntryImpl traceEntry = mainThreadContext.addErrorEntry(endTick, endTick,
                        MessageSupplier.create("Resource leaked (acquired during the transaction"
                                + " and not released): " + entry.getKey().getClass().getName()),
                        null, errorMessage);
                if (locationStackTrace.length != 0) {
                    // strip up through this method, plus 1 additional method (the plugin advice
                    // method)
                    int index = ThreadContextImpl.getNormalizedStartIndex(locationStackTrace,
                            "trackResourceAcquired", 2);
                    traceEntry.setLocationStackTrace(ImmutableList.copyOf(locationStackTrace)
                            .subList(index, locationStackTrace.length));
                }
                first = false;
            }
        }
        if (immedateTraceStoreRunnable != null) {
            immedateTraceStoreRunnable.cancel();
        }
        completionCallback.completed(this);
    }

    void setCaptureTime(long captureTime) {
        this.captureTime = captureTime;
    }

    public long getCaptureTime() {
        return captureTime;
    }

    TransactionRegistry getTransactionRegistry() {
        return transactionRegistry;
    }

    TransactionService getTransactionService() {
        return transactionService;
    }

    ConfigService getConfigService() {
        return configService;
    }

    AtomicInteger getThrowableFrameLimitCounter() {
        if (throwableFrameLimitCounter == null) {
            // double-checked locking works here because throwableFrameLimitCounter is volatile
            //
            // synchronized on "this" as a micro-optimization just so don't need to create an empty
            // object to lock on
            synchronized (this) {
                if (throwableFrameLimitCounter == null) {
                    throwableFrameLimitCounter = new AtomicInteger();
                }
            }
        }
        return throwableFrameLimitCounter;
    }

    boolean memoryBarrierRead() {
        return memoryBarrier;
    }

    void memoryBarrierWrite() {
        memoryBarrier = true;
    }

    void memoryBarrierReadWrite() {
        memoryBarrierRead();
        memoryBarrierWrite();
    }

    @GuardedBy("mainThreadContext")
    @RequiresNonNull("auxThreadContexts")
    private boolean allowAnotherAuxThreadContextWithTraceEntries() {
        int unmergedCount = auxThreadContexts.size();
        if (unmergeableAuxThreadContexts != null) {
            unmergedCount += unmergeableAuxThreadContexts.size();
        }
        if (unmergedCount < TRANSACTION_AUX_THREAD_CONTEXT_LIMIT) {
            return true;
        }
        if (stopMergingAuxThreadContexts) {
            return false;
        }
        List<ThreadContextImpl> mergeableAuxThreadContexts = Lists.newArrayList();
        List<ThreadContextImpl> unmergeableAuxThreadContexts = Lists.newArrayList();
        List<ThreadContextImpl> mergeableButIncompleteAuxThreadContexts = Lists.newArrayList();
        for (ThreadContextImpl auxThreadContext : auxThreadContexts) {
            if (!auxThreadContext.isMergeable()) {
                // once it's not mergeable it will never be mergeable
                unmergeableAuxThreadContexts.add(auxThreadContext);
            } else if (auxThreadContext.isCompleted()) {
                mergeableAuxThreadContexts.add(auxThreadContext);
            } else {
                mergeableButIncompleteAuxThreadContexts.add(auxThreadContext);
            }
        }
        if (mergeableAuxThreadContexts.size() < 0.01 * TRANSACTION_AUX_THREAD_CONTEXT_LIMIT) {
            // not worth continuing to merge
            stopMergingAuxThreadContexts = true;
            return false;
        }
        initAlreadyMergedAuxComponentsIfNeeded();
        for (ThreadContextImpl mergeableAuxThreadContext : mergeableAuxThreadContexts) {
            mergeAux(mergeableAuxThreadContext);
        }
        if (this.unmergeableAuxThreadContexts == null) {
            this.unmergeableAuxThreadContexts = Lists.newArrayList(unmergeableAuxThreadContexts);
        } else {
            this.unmergeableAuxThreadContexts.addAll(unmergeableAuxThreadContexts);
        }
        auxThreadContexts = Lists.newArrayList(mergeableButIncompleteAuxThreadContexts);
        return true;
    }

    @GuardedBy("mainThreadContext")
    @EnsuresNonNull({"alreadyMergedAuxThreadTimers", "alreadyMergedAuxThreadStats",
            "alreadyMergedAuxQueries", "alreadyMergedAuxServiceCalls"})
    private void initAlreadyMergedAuxComponentsIfNeeded() {
        if (alreadyMergedAuxThreadTimers == null) {
            alreadyMergedAuxThreadTimers = new RootTimerCollectorImpl();
        }
        if (alreadyMergedAuxThreadStats == null) {
            alreadyMergedAuxThreadStats = new ThreadStatsCollectorImpl();
        }
        if (alreadyMergedAuxQueries == null) {
            alreadyMergedAuxQueries = new QueryCollector(maxQueryAggregates,
                    AdvancedConfig.OVERALL_AGGREGATE_QUERIES_HARD_LIMIT_MULTIPLIER);
        }
        if (alreadyMergedAuxServiceCalls == null) {
            alreadyMergedAuxServiceCalls = new ServiceCallCollector(maxServiceCallAggregates,
                    AdvancedConfig.OVERALL_AGGREGATE_QUERIES_HARD_LIMIT_MULTIPLIER);
        }
    }

    @GuardedBy("mainThreadContext")
    @RequiresNonNull({"alreadyMergedAuxThreadTimers", "alreadyMergedAuxThreadStats",
            "alreadyMergedAuxQueries", "alreadyMergedAuxServiceCalls"})
    private void mergeAux(ThreadContextImpl mergeableAuxThreadContext) {
        alreadyMergedAuxThreadTimers.mergeRootTimer(mergeableAuxThreadContext.getRootTimer());
        alreadyMergedAuxThreadStats.mergeThreadStats(mergeableAuxThreadContext.getThreadStats());
        mergeableAuxThreadContext.mergeQueriesInto(alreadyMergedAuxQueries);
        mergeableAuxThreadContext.mergeServiceCallsInto(alreadyMergedAuxServiceCalls);
    }

    private AsyncComponents getOrInitAsyncComponents() {
        if (asyncComponents == null) {
            synchronized (asyncComponentsInitLock) {
                if (asyncComponents == null) {
                    asyncComponents = new AsyncComponents(maxQueryAggregates,
                            maxServiceCallAggregates, ticker);
                }
            }
        }
        return asyncComponents;
    }

    private static void addProtobufChildEntries(TraceEntryImpl entry,
            ListMultimap<TraceEntryImpl, TraceEntryImpl> parentChildMap, long transactionStartTick,
            long captureTick, int depth, TraceEntryVisitor entryVisitor,
            SharedQueryTextCollection sharedQueryTextCollection, boolean removeSingleAuxEntry) {
        if (!parentChildMap.containsKey(entry)) {
            // check containsKey to avoid creating garbage empty list via ListMultimap
            return;
        }
        Collection<TraceEntryImpl> childEntries = parentChildMap.get(entry);
        for (TraceEntryImpl childEntry : childEntries) {
            boolean singleAuxEntry = childEntries.size() == 1 && childEntry.isAuxThreadRoot()
                    && !childEntry.hasLocationStackTrace();
            if (singleAuxEntry && removeSingleAuxEntry) {
                addProtobufChildEntries(childEntry, parentChildMap, transactionStartTick,
                        captureTick, depth, entryVisitor, sharedQueryTextCollection,
                        removeSingleAuxEntry);
            } else {
                childEntry.accept(depth, transactionStartTick, captureTick, entryVisitor,
                        sharedQueryTextCollection);
                addProtobufChildEntries(childEntry, parentChildMap, transactionStartTick,
                        captureTick, depth + 1, entryVisitor, sharedQueryTextCollection, false);
            }
        }
    }

    private ListMultimap<TraceEntryImpl, ThreadContextImpl> buildPriorEntryChildThreadContextMap() {
        synchronized (mainThreadContext) {
            if (auxThreadContexts == null) {
                return ImmutableListMultimap.of();
            }
            ListMultimap<TraceEntryImpl, ThreadContextImpl> parentChildMap =
                    ArrayListMultimap.create();
            for (ThreadContextImpl auxThreadContext : getUnmergedAuxThreadContext()) {
                // checkNotNull is safe b/c aux thread contexts have non-null parent thread context
                // prior entries when they are not limit exceeded aux thread contexts
                parentChildMap.put(
                        checkNotNull(auxThreadContext.getParentThreadContextPriorEntry()),
                        auxThreadContext);
            }
            return parentChildMap;
        }
    }

    @GuardedBy("mainThreadContext")
    private void detachIncompleteAuxThreadContexts() {
        if (auxThreadContexts == null) {
            return;
        }
        for (ThreadContextImpl auxThreadContext : getUnmergedAuxThreadContext()) {
            if (auxThreadContext.isCompleted()) {
                continue;
            }
            auxThreadContext.detach();
            if (!logger.isDebugEnabled()) {
                continue;
            }
            ThreadInfo threadInfo = ManagementFactory.getThreadMXBean()
                    .getThreadInfo(auxThreadContext.getThreadId(), Integer.MAX_VALUE);
            if (logger.isDebugEnabled() && !isCompleted() && threadInfo != null) {
                // still not complete and got a valid stack trace from auxiliary thread
                StringBuilder sb = new StringBuilder();
                for (StackTraceElement stackTraceElement : threadInfo.getStackTrace()) {
                    sb.append("    ");
                    sb.append(stackTraceElement.toString());
                    sb.append('\n');
                }
                logger.debug("auxiliary thread extended beyond the transaction which started it\n"
                        + "{}", sb);
            }
        }
    }

    @GuardedBy("mainThreadContext")
    @RequiresNonNull("auxThreadContexts")
    private Iterable<ThreadContextImpl> getUnmergedAuxThreadContext() {
        if (unmergeableAuxThreadContexts == null) {
            if (unmergedLimitExceededAuxThreadContexts == null) {
                return auxThreadContexts;
            } else {
                return Iterables.concat(auxThreadContexts, unmergedLimitExceededAuxThreadContexts);
            }
        } else if (unmergedLimitExceededAuxThreadContexts == null) {
            return Iterables.concat(unmergeableAuxThreadContexts, auxThreadContexts);
        } else {
            return Iterables.concat(unmergeableAuxThreadContexts, auxThreadContexts,
                    unmergedLimitExceededAuxThreadContexts);
        }
    }

    @VisibleForTesting
    static String buildTraceId(long startTime) {
        byte[] bytes = new byte[10];
        random.nextBytes(bytes);
        // lower 6 bytes of current time will wrap only every 8925 years
        return lowerSixBytesHex(startTime) + BaseEncoding.base16().lowerCase().encode(bytes);
    }

    @VisibleForTesting
    static String lowerSixBytesHex(long startTime) {
        long mask = 1L << 48;
        return Long.toHexString(mask | (startTime & (mask - 1))).substring(1);
    }

    interface CompletionCallback {
        void completed(Transaction transaction);
    }

    interface RootTimerCollector {
        void mergeRootTimer(TransactionTimer rootTimer);
    }

    interface ThreadStatsCollector {
        void mergeThreadStats(ThreadStats threadStats);
    }

    public interface TraceEntryVisitor {
        void visitEntry(Trace.Entry entry);
    }

    private static class AuxThreadRootMessageSupplier extends MessageSupplier {

        private static final AuxThreadRootMessageSupplier INSTANCE =
                new AuxThreadRootMessageSupplier();

        private final Message message = Message.create(AUXILIARY_THREAD_MESSAGE);

        @Override
        public Message get() {
            return message;
        }
    }

    private static class ParentChildMapTrimmer extends Traverser<TraceEntryImpl, RuntimeException> {

        private final ListMultimap<TraceEntryImpl, TraceEntryImpl> parentChildMap;
        private final long captureTick;

        private ParentChildMapTrimmer(TraceEntryImpl rootNode,
                ListMultimap<TraceEntryImpl, TraceEntryImpl> parentChildMap,
                long captureTick) {
            super(rootNode);
            this.parentChildMap = parentChildMap;
            this.captureTick = captureTick;
        }

        @Override
        public List<TraceEntryImpl> visit(TraceEntryImpl node, int depth) {
            if (!parentChildMap.containsKey(node)) {
                // check containsKey to avoid creating garbage empty list via ListMultimap
                return ImmutableList.of();
            }
            return parentChildMap.get(node);
        }

        @Override
        public void revisitAfterChildren(TraceEntryImpl node) {
            if (!parentChildMap.containsKey(node)) {
                // check containsKey to avoid creating garbage empty list via ListMultimap
                return;
            }
            List<TraceEntryImpl> childEntries = parentChildMap.get(node);
            ListIterator<TraceEntryImpl> i = childEntries.listIterator();
            while (i.hasNext()) {
                TraceEntryImpl childEntry = i.next();
                if (childEntry.getStartTick() > captureTick) {
                    i.remove();
                }
            }
            i = childEntries.listIterator();
            while (i.hasNext()) {
                TraceEntryImpl childEntry = i.next();
                if (!childEntry.isAuxThreadRoot() || childEntry.hasLocationStackTrace()) {
                    continue;
                }
                if (!parentChildMap.containsKey(childEntry)) {
                    // check containsKey to avoid creating garbage empty list via ListMultimap
                    //
                    // remove empty aux thread root
                    i.remove();
                    continue;
                }
                List<TraceEntryImpl> subChildEntries = parentChildMap.get(childEntry);
                if (subChildEntries.isEmpty()) {
                    // remove empty aux thread root
                    i.remove();
                    continue;
                }
                if (subChildEntries.size() == 1) {
                    TraceEntryImpl singleSubChildEntry = subChildEntries.get(0);
                    if (singleSubChildEntry.isAuxThreadRoot()) {
                        // compact consecutive single aux root entries
                        i.set(singleSubChildEntry);
                    }
                }
            }
        }
    }

    private static class CountingEntryVisitor implements TraceEntryVisitor {

        private int count;

        @Override
        public void visitEntry(Trace.Entry entry) {
            if (countEntry(entry)) {
                count++;
            }
        }

        private static boolean countEntry(Trace.Entry entry) {
            // don't count "auxiliary thread" entries since those are not counted in
            // maxTraceEntriesPerTransaction limit (and it's confusing when entry count exceeds the
            // limit)
            return !entry.getMessage().equals(Transaction.AUXILIARY_THREAD_MESSAGE);
        }
    }

    private static class SharedQueryTextCollectionImpl implements SharedQueryTextCollection {

        private final Map<String, Integer> sharedQueryTextIndexes = Maps.newHashMap();
        private List<String> sharedQueryTexts = Lists.newArrayList();

        @Override
        public int getSharedQueryTextIndex(String queryText) {
            Integer sharedQueryTextIndex = sharedQueryTextIndexes.get(queryText);
            if (sharedQueryTextIndex == null) {
                sharedQueryTextIndex = sharedQueryTextIndexes.size();
                sharedQueryTextIndexes.put(queryText, sharedQueryTextIndex);
                sharedQueryTexts.add(queryText);
            }
            return sharedQueryTextIndex;
        }
    }

    private static class NopSharedQueryTextCollection implements SharedQueryTextCollection {

        @Override
        public int getSharedQueryTextIndex(String queryText) {
            return 0;
        }
    }
}
