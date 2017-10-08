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

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.base.Ticker;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.google.common.collect.TreeMultimap;
import com.google.common.io.BaseEncoding;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.collector.Collector.EntryVisitor;
import org.glowroot.agent.config.AdvancedConfig;
import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.model.AsyncTimerImpl;
import org.glowroot.agent.model.CommonTimerImpl;
import org.glowroot.agent.model.ErrorMessage;
import org.glowroot.agent.model.ImmutableTimerImplSnapshot;
import org.glowroot.agent.model.MutableAggregateTimer;
import org.glowroot.agent.model.MutableTraceTimer;
import org.glowroot.agent.model.QueryCollector;
import org.glowroot.agent.model.ThreadProfile;
import org.glowroot.agent.model.ThreadStats;
import org.glowroot.agent.model.TimerNameImpl;
import org.glowroot.agent.plugin.api.Message;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.ThreadContext.ServletRequestInfo;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.internal.ReadableMessage;
import org.glowroot.agent.util.IterableWithSelfRemovableEntries.SelfRemovableEntry;
import org.glowroot.agent.util.ThreadAllocatedBytes;
import org.glowroot.common.model.ServiceCallCollector;
import org.glowroot.common.util.Cancellable;
import org.glowroot.common.util.NotAvailableAware;
import org.glowroot.common.util.Traverser;

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
            Integer.getInteger("glowroot.transaction.aux.thread.context.limit", 10000);

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

    // lazy loaded to reduce memory when custom attributes are not used
    @GuardedBy("attributes")
    private volatile @MonotonicNonNull SetMultimap<String, String> attributes;

    // trace-level error
    private volatile @Nullable ErrorMessage errorMessage;

    private final int maxTraceEntriesPerTransaction;
    private final int maxAggregateQueriesPerType;
    private final int maxAggregateServiceCallsPerType;

    private final TransactionRegistry transactionRegistry;
    private final TransactionServiceImpl transactionService;
    private final ConfigService configService;

    // stack trace data constructed from profiling
    private volatile @MonotonicNonNull ThreadProfile mainThreadProfile;
    private volatile @MonotonicNonNull ThreadProfile auxThreadProfile;

    // overrides general store threshold
    // -1 means don't override the general store threshold
    private volatile int slowThresholdMillis = USE_GENERAL_STORE_THRESHOLD;
    private volatile int slowThresholdMillisPriority = Integer.MIN_VALUE;

    // these are stored in the trace so they are only scheduled a single time, and also so they can
    // be canceled at trace completion
    private volatile @MonotonicNonNull Cancellable userProfileRunnable;
    private volatile @MonotonicNonNull Cancellable immedateTraceStoreRunnable;

    private volatile boolean partiallyStored;

    private long captureTime;

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
    private volatile int aggregateQueryLimitCounter;
    private volatile int aggregateServiceCallLimitCounter;

    private volatile @Nullable AtomicInteger throwableFrameLimitCounter;

    private final ThreadContextImpl mainThreadContext;

    @GuardedBy("mainThreadContext")
    private @MonotonicNonNull List<ThreadContextImpl> auxThreadContexts;
    @GuardedBy("mainThreadContext")
    private @MonotonicNonNull Set<ThreadContextImpl> unmergedLimitExceededAuxThreadContexts;

    // async root timers are the root timers which do not have corresponding thread context
    // (those corresponding to async trace entries)
    private final Object asyncTimerLock = new Object();
    @GuardedBy("asyncTimerLock")
    private @MonotonicNonNull List<AsyncTimerImpl> asyncTimers;
    @GuardedBy("asyncTimerLock")
    private @MonotonicNonNull Map<String, AggregateAsyncTimer> aggregateAsyncTimers;

    private volatile boolean completed;
    private volatile long endTick;

    private final Ticker ticker;

    private final UserProfileScheduler userProfileScheduler;

    private @Nullable SelfRemovableEntry transactionEntry;

    @GuardedBy("mainThreadContext")
    private @MonotonicNonNull RootTimerCollectorImpl alreadyMergedAuxThreadTimers;
    @GuardedBy("mainThreadContext")
    private @MonotonicNonNull ThreadStatsCollectorImpl alreadyMergedAuxThreadStats;
    @GuardedBy("mainThreadContext")
    private boolean stopMergingAuxThreadContexts;

    Transaction(long startTime, long startTick, String transactionType, String transactionName,
            MessageSupplier messageSupplier, TimerName timerName, boolean captureThreadStats,
            int maxTraceEntriesPerTransaction, int maxAggregateQueriesPerType,
            int maxAggregateServiceCallsPerType,
            @Nullable ThreadAllocatedBytes threadAllocatedBytes,
            CompletionCallback completionCallback, Ticker ticker,
            TransactionRegistry transactionRegistry, TransactionServiceImpl transactionService,
            ConfigService configService, UserProfileScheduler userProfileScheduler,
            ThreadContextThreadLocal.Holder threadContextHolder) {
        this.startTime = startTime;
        this.startTick = startTick;
        this.transactionType = transactionType;
        this.transactionName = transactionName;
        this.maxTraceEntriesPerTransaction = maxTraceEntriesPerTransaction;
        this.maxAggregateQueriesPerType = maxAggregateQueriesPerType;
        this.maxAggregateServiceCallsPerType = maxAggregateServiceCallsPerType;
        this.completionCallback = completionCallback;
        this.ticker = ticker;
        this.userProfileScheduler = userProfileScheduler;
        this.transactionRegistry = transactionRegistry;
        this.transactionService = transactionService;
        this.configService = configService;
        mainThreadContext = new ThreadContextImpl(castInitialized(this), null, null,
                messageSupplier, timerName, startTick, captureThreadStats, threadAllocatedBytes,
                false, ticker, threadContextHolder, null);
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

    public ImmutableSetMultimap<String, String> getAttributes() {
        if (attributes == null) {
            return ImmutableSetMultimap.of();
        }
        SetMultimap<String, String> orderedAttributes = TreeMultimap.create();
        synchronized (attributes) {
            orderedAttributes.putAll(attributes);
        }
        return ImmutableSetMultimap.copyOf(orderedAttributes);
    }

    Map<String, ?> getDetail() {
        Object messageSupplier = mainThreadContext.getRootEntry().getMessageSupplier();
        // root trace entry messageSupplier is never null
        checkNotNull(messageSupplier);
        // root trace entry messageSupplier is never QueryMessageSupplier
        return ((ReadableMessage) ((MessageSupplier) messageSupplier).get()).getDetail();
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

    void mergeAuxThreadTimersInto(RootTimerCollector rootTimers) {
        synchronized (mainThreadContext) {
            if (auxThreadContexts == null) {
                return;
            }
            for (ThreadContextImpl auxThreadContext : auxThreadContexts) {
                rootTimers.mergeRootTimer(auxThreadContext.getRootTimer());
            }
            if (alreadyMergedAuxThreadTimers != null) {
                for (CommonTimerImpl rootTimer : alreadyMergedAuxThreadTimers.getRootTimers()) {
                    rootTimers.mergeRootTimer(rootTimer);
                }
            }
            if (unmergedLimitExceededAuxThreadContexts != null) {
                for (ThreadContextImpl auxThreadContext : unmergedLimitExceededAuxThreadContexts) {
                    rootTimers.mergeRootTimer(auxThreadContext.getRootTimer());
                }
            }
        }
    }

    void mergeAsyncTimersInto(RootTimerCollector rootTimers) {
        memoryBarrierRead();
        synchronized (asyncTimerLock) {
            if (asyncTimers == null) {
                return;
            }
            for (AsyncTimerImpl asyncTimer : asyncTimers) {
                rootTimers.mergeRootTimer(asyncTimer);
            }
            if (aggregateAsyncTimers == null) {
                return;
            }
            for (Entry<String, AggregateAsyncTimer> entry : aggregateAsyncTimers.entrySet()) {
                AggregateAsyncTimer value = entry.getValue();
                rootTimers.mergeRootTimer(
                        new SimpleTimerImpl(entry.getKey(), value.totalNanos, value.count));
            }
        }
    }

    // can be called from a non-transaction thread
    ThreadStats getMainThreadStats() {
        return mainThreadContext.getThreadStats();
    }

    public long getTotalCpuNanos() {
        long totalCpuNanos = mainThreadContext.getTotalCpuNanos();
        synchronized (mainThreadContext) {
            if (auxThreadContexts == null) {
                return totalCpuNanos;
            }
            for (ThreadContextImpl auxThreadContext : auxThreadContexts) {
                totalCpuNanos =
                        NotAvailableAware.add(totalCpuNanos, auxThreadContext.getTotalCpuNanos());
            }
            return totalCpuNanos;
        }
    }

    void mergeAuxThreadStatsInto(ThreadStatsCollector threadStats) {
        synchronized (mainThreadContext) {
            if (auxThreadContexts == null) {
                return;
            }
            for (ThreadContextImpl auxThreadContext : auxThreadContexts) {
                threadStats.mergeThreadStats(auxThreadContext.getThreadStats());
            }
            if (alreadyMergedAuxThreadStats != null) {
                threadStats.mergeThreadStats(alreadyMergedAuxThreadStats.getMergedThreadStats());
            }
            if (unmergedLimitExceededAuxThreadContexts != null) {
                for (ThreadContextImpl auxThreadContext : unmergedLimitExceededAuxThreadContexts) {
                    threadStats.mergeThreadStats(auxThreadContext.getThreadStats());
                }
            }
        }
    }

    void mergeQueriesInto(QueryCollector queries) {
        memoryBarrierRead();
        mainThreadContext.mergeQueriesInto(queries);
        synchronized (mainThreadContext) {
            if (auxThreadContexts != null) {
                for (ThreadContextImpl auxThreadContext : auxThreadContexts) {
                    auxThreadContext.mergeQueriesInto(queries);
                }
            }
        }
    }

    void mergeServiceCallsInto(ServiceCallCollector serviceCalls) {
        memoryBarrierRead();
        mainThreadContext.mergeServiceCallsInto(serviceCalls);
        synchronized (mainThreadContext) {
            if (auxThreadContexts != null) {
                for (ThreadContextImpl auxThreadContext : auxThreadContexts) {
                    auxThreadContext.mergeServiceCallsInto(serviceCalls);
                }
            }
        }
    }

    // this method has side effect of incrementing counter
    boolean allowAnotherEntry() {
        return entryLimitCounter++ < maxTraceEntriesPerTransaction;
    }

    // this method has side effect of incrementing counter
    boolean allowAnotherErrorEntry() {
        // use higher entry limit when adding errors, but still need some kind of cap
        return entryLimitCounter++ < maxTraceEntriesPerTransaction
                || extraErrorEntryLimitCounter++ < maxTraceEntriesPerTransaction;
    }

    // this method has side effect of incrementing counter
    boolean allowAnotherAggregateQuery(boolean bypassLimit) {
        if (aggregateQueryLimitCounter++ < maxAggregateQueriesPerType
                * AdvancedConfig.OVERALL_AGGREGATE_QUERIES_HARD_LIMIT_MULTIPLIER) {
            return true;
        }
        return bypassLimit;
    }

    // this method has side effect of incrementing counter
    boolean allowAnotherAggregateServiceCall() {
        return aggregateServiceCallLimitCounter++ < maxAggregateServiceCallsPerType
                * AdvancedConfig.OVERALL_AGGREGATE_SERVICE_CALLS_HARD_LIMIT_MULTIPLIER;
    }

    public void accept(long captureTick, EntryVisitor entryVisitor) throws Exception {
        memoryBarrierRead();
        ListMultimap<TraceEntryImpl, ThreadContextImpl> priorEntryChildThreadContextMap =
                buildPriorEntryChildThreadContextMap();
        ListMultimap<TraceEntryImpl, TraceEntryImpl> parentChildMap = ArrayListMultimap.create();
        mainThreadContext.populateParentChildMap(parentChildMap, captureTick,
                priorEntryChildThreadContextMap);
        synchronized (mainThreadContext) {
            if (auxThreadContexts != null) {
                for (ThreadContextImpl auxThreadContext : auxThreadContexts) {
                    auxThreadContext.populateParentChildMap(parentChildMap, captureTick,
                            priorEntryChildThreadContextMap);
                }
            }
        }
        new ParentChildMapTrimmer(mainThreadContext.getRootEntry(), parentChildMap, captureTick)
                .traverse();
        addProtobufChildEntries(mainThreadContext.getRootEntry(), parentChildMap, startTick,
                captureTick, 0, entryVisitor, async);
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

    public @Nullable org.glowroot.wire.api.model.ProfileOuterClass.Profile getMainThreadProfileProtobuf() {
        if (mainThreadProfile == null) {
            return null;
        }
        return mainThreadProfile.toProto();
    }

    boolean isMainThreadProfileSampleLimitExceeded() {
        // TODO implement profile limit
        return false;
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

    public @Nullable org.glowroot.wire.api.model.ProfileOuterClass.Profile getAuxThreadProfileProtobuf() {
        if (auxThreadProfile == null) {
            return null;
        }
        return auxThreadProfile.toProto();
    }

    boolean isAuxThreadProfileSampleLimitExceeded() {
        // TODO implement profile limit
        return false;
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
            for (ThreadContextImpl auxThreadContext : auxThreadContexts) {
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
            if (userProfileRunnable == null) {
                userProfileScheduler.maybeScheduleUserProfiling(this, user);
            }
        }
    }

    void addAttribute(String name, @Nullable String value) {
        if (attributes == null) {
            // no race condition here since only transaction thread calls addAttribute()
            attributes = HashMultimap.create(ATTRIBUTE_KEYS_INITIAL_CAPACITY, 1);
        }
        String val = Strings.nullToEmpty(value);
        synchronized (attributes) {
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

    void setUserProfileRunnable(Cancellable userProfileRunnable) {
        if (this.userProfileRunnable != null) {
            logger.warn("setUserProfileRunnable(): overwriting non-null userProfileRunnable");
        }
        this.userProfileRunnable = userProfileRunnable;
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
            if (allowAnotherAuxThreadContextWithHierarchy() && parentTraceEntry != null
                    && parentThreadContextPriorEntry != null) {
                auxThreadContext = new ThreadContextImpl(this, parentTraceEntry,
                        parentThreadContextPriorEntry, AuxThreadRootMessageSupplier.INSTANCE,
                        auxTimerName, startTick, mainThreadContext.getCaptureThreadStats(),
                        threadAllocatedBytes, false, ticker, threadContextHolder,
                        servletRequestInfo);
                auxThreadContexts.add(auxThreadContext);
            } else {
                auxThreadContext = new ThreadContextImpl(this, mainThreadContext.getRootEntry(),
                        mainThreadContext.getTailEntry(), AuxThreadRootMessageSupplier.INSTANCE,
                        auxTimerName, startTick, mainThreadContext.getCaptureThreadStats(),
                        threadAllocatedBytes, true, ticker, threadContextHolder,
                        servletRequestInfo);
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
            if (alreadyMergedAuxThreadTimers == null) {
                alreadyMergedAuxThreadTimers = new RootTimerCollectorImpl();
            }
            if (alreadyMergedAuxThreadStats == null) {
                alreadyMergedAuxThreadStats = new ThreadStatsCollectorImpl();
            }
            alreadyMergedAuxThreadTimers.mergeRootTimer(auxThreadContext.getRootTimer());
            alreadyMergedAuxThreadStats.mergeThreadStats(auxThreadContext.getThreadStats());
        }
    }

    AsyncTimerImpl startAsyncTimer(TimerName asyncTimerName, long startTick) {
        AsyncTimerImpl asyncTimer = new AsyncTimerImpl((TimerNameImpl) asyncTimerName, startTick);
        synchronized (asyncTimerLock) {
            if (asyncTimers == null) {
                asyncTimers = Lists.newArrayList();
            }
            if (asyncTimers.size() >= 1000) {
                // this is just to conserve memory
                if (aggregateAsyncTimers == null) {
                    aggregateAsyncTimers = Maps.newHashMap();
                }
                List<AsyncTimerImpl> activeAsyncTimers = Lists.newArrayList();
                for (AsyncTimerImpl loopAsyncTimer : asyncTimers) {
                    if (loopAsyncTimer.active()) {
                        activeAsyncTimers.add(loopAsyncTimer);
                        continue;
                    }
                    AggregateAsyncTimer aggregateAsyncTimer =
                            aggregateAsyncTimers.get(loopAsyncTimer.getName());
                    if (aggregateAsyncTimer == null) {
                        aggregateAsyncTimer = new AggregateAsyncTimer();
                        aggregateAsyncTimers.put(loopAsyncTimer.getName(), aggregateAsyncTimer);
                    }
                    aggregateAsyncTimer.totalNanos += loopAsyncTimer.getTotalNanos();
                    aggregateAsyncTimer.count += loopAsyncTimer.getCount();
                }
                asyncTimers = activeAsyncTimers;
            }
            asyncTimers.add(asyncTimer);
        }
        return asyncTimer;
    }

    TraceEntryImpl startInnerTransaction(String transactionType, String transactionName,
            MessageSupplier messageSupplier, TimerName timerName,
            ThreadContextThreadLocal.Holder threadContextHolder) {
        return transactionService.startTransaction(transactionType, transactionName,
                messageSupplier, timerName, threadContextHolder);
    }

    boolean isEntryLimitExceeded() {
        return entryLimitCounter > maxTraceEntriesPerTransaction;
    }

    void captureStackTrace(boolean auxiliary, ThreadInfo threadInfo, int limit) {
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
            profile = new ThreadProfile();
            profile.addStackTrace(threadInfo, limit);
            if (auxiliary) {
                auxThreadProfile = profile;
            } else {
                mainThreadProfile = profile;
            }
            return;
        }
        profile.addStackTrace(threadInfo, limit);
    }

    void end(long endTick, boolean completeAsyncTransaction) {
        if (async && !completeAsyncTransaction) {
            return;
        }
        // set endTick first before completed, to avoid race condition in getDurationNanos()
        this.endTick = endTick;
        synchronized (mainThreadContext) {
            // set completed and detach incomplete aux thread contexts inside synchronized block
            // to avoid race condition with adding new aux thread contexts, see synchronized block
            // in startAuxThreadContext()
            completed = true;
            detachIncompleteAuxThreadContexts();
        }
        if (immedateTraceStoreRunnable != null) {
            immedateTraceStoreRunnable.cancel();
        }
        if (userProfileRunnable != null) {
            userProfileRunnable.cancel();
        }
        completionCallback.completed(this);
    }

    // called by the transaction thread
    void onCompleteWillStoreTrace(long captureTime) {
        this.captureTime = captureTime;
    }

    long getCaptureTime() {
        return captureTime;
    }

    TransactionRegistry getTransactionRegistry() {
        return transactionRegistry;
    }

    TransactionServiceImpl getTransactionService() {
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

    // must be called under synchronized (mainThreadContext)
    @RequiresNonNull("auxThreadContexts")
    private boolean allowAnotherAuxThreadContextWithHierarchy() {
        if (auxThreadContexts.size() < TRANSACTION_AUX_THREAD_CONTEXT_LIMIT) {
            return true;
        }
        if (stopMergingAuxThreadContexts) {
            return false;
        }
        List<ThreadContextImpl> mergeableAuxThreadContexts = Lists.newArrayList();
        List<ThreadContextImpl> nonMergeableAuxThreadContexts = Lists.newArrayList();
        for (Iterator<ThreadContextImpl> i = auxThreadContexts.iterator(); i.hasNext();) {
            ThreadContextImpl loopAuxThreadContext = i.next();
            if (loopAuxThreadContext.isCompleteAndEmptyExceptForTimersAndThreadStats()) {
                mergeableAuxThreadContexts.add(loopAuxThreadContext);
            } else {
                nonMergeableAuxThreadContexts.add(loopAuxThreadContext);
            }
        }
        if (mergeableAuxThreadContexts.size() < 0.1 * auxThreadContexts.size()) {
            // unable to merge more than 10%
            stopMergingAuxThreadContexts = true;
            return false;
        }
        if (alreadyMergedAuxThreadTimers == null) {
            alreadyMergedAuxThreadTimers = new RootTimerCollectorImpl();
        }
        if (alreadyMergedAuxThreadStats == null) {
            alreadyMergedAuxThreadStats = new ThreadStatsCollectorImpl();
        }
        for (ThreadContextImpl mergeableAuxThreadContext : mergeableAuxThreadContexts) {
            alreadyMergedAuxThreadTimers.mergeRootTimer(mergeableAuxThreadContext.getRootTimer());
            alreadyMergedAuxThreadStats
                    .mergeThreadStats(mergeableAuxThreadContext.getThreadStats());
        }
        auxThreadContexts = nonMergeableAuxThreadContexts;
        return true;
    }

    private static void addProtobufChildEntries(TraceEntryImpl entry,
            ListMultimap<TraceEntryImpl, TraceEntryImpl> parentChildMap, long transactionStartTick,
            long captureTick, int depth, EntryVisitor entryVisitor, boolean removeSingleAuxEntry)
            throws Exception {
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
                        captureTick, depth, entryVisitor, removeSingleAuxEntry);
            } else {
                childEntry.accept(depth, transactionStartTick, captureTick, entryVisitor);
                addProtobufChildEntries(childEntry, parentChildMap, transactionStartTick,
                        captureTick, depth + 1, entryVisitor, false);
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
            for (ThreadContextImpl auxThreadContext : auxThreadContexts) {
                // checkNotNull is safe b/c aux thread contexts have non-null parent thread context
                // prior entries when they are not limit exceeded aux thread contexts
                parentChildMap.put(
                        checkNotNull(auxThreadContext.getParentThreadContextPriorEntry()),
                        auxThreadContext);
            }
            return parentChildMap;
        }
    }

    // must be called under synchronized (mainThreadContext)
    private void detachIncompleteAuxThreadContexts() {
        if (auxThreadContexts == null) {
            return;
        }
        for (ThreadContextImpl auxThreadContext : auxThreadContexts) {
            if (auxThreadContext.isCompleted()) {
                continue;
            }
            auxThreadContext.detach();
            if (!logger.isDebugEnabled()) {
                continue;
            }
            ThreadInfo threadInfo = ManagementFactory.getThreadMXBean()
                    .getThreadInfo(auxThreadContext.getThreadId(), Integer.MAX_VALUE);
            if (logger.isDebugEnabled() && !isCompleted()
                    && threadInfo != null) {
                // still not complete and got a valid stack trace from auxiliary thread
                StringBuilder sb = new StringBuilder();
                for (StackTraceElement stackTraceElement : threadInfo.getStackTrace()) {
                    sb.append("    ");
                    sb.append(stackTraceElement.toString());
                    sb.append('\n');
                }
                logger.debug(
                        "auxiliary thread extended beyond the transaction which started it\n{}",
                        sb);
            }
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
        void mergeRootTimer(CommonTimerImpl rootTimer);
    }

    interface ThreadStatsCollector {
        void mergeThreadStats(ThreadStats threadStats);
    }

    private static class AggregateAsyncTimer {

        private long totalNanos;
        private long count;
    }

    private static class SimpleTimerImpl implements CommonTimerImpl {

        private final String name;
        private final long totalNanos;
        private final long count;

        private SimpleTimerImpl(String name, long totalNanos, long count) {
            this.name = name;
            this.totalNanos = totalNanos;
            this.count = count;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isExtended() {
            return false;
        }

        @Override
        public long getTotalNanos() {
            return totalNanos;
        }

        @Override
        public long getCount() {
            return count;
        }

        @Override
        public void mergeChildTimersInto(List<MutableTraceTimer> childTimers) {
            // async timers have no child timers
        }

        @Override
        public void mergeChildTimersInto2(List<MutableAggregateTimer> childTimers) {
            // async timers have no child timers
        }

        @Override
        public TimerImplSnapshot getSnapshot() {
            return ImmutableTimerImplSnapshot.builder()
                    .totalNanos(totalNanos)
                    .count(count)
                    .active(false)
                    .build();
        }
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
}
