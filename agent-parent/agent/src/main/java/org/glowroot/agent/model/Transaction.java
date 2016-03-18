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
package org.glowroot.agent.model;

import java.lang.management.ThreadInfo;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import com.google.common.base.Strings;
import com.google.common.base.Ticker;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.TreeMultimap;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.config.AdvancedConfig;
import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.impl.TransactionCollection.TransactionEntry;
import org.glowroot.agent.impl.TransactionRegistry;
import org.glowroot.agent.impl.TransactionServiceImpl;
import org.glowroot.agent.impl.UserProfileScheduler;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.internal.ReadableMessage;
import org.glowroot.agent.plugin.api.util.FastThreadLocal.Holder;
import org.glowroot.agent.util.ThreadAllocatedBytes;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.common.model.ServiceCallCollector;
import org.glowroot.common.util.Cancellable;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.glowroot.agent.fat.storage.util.Checkers.castInitialized;

// contains all data that has been captured for a given transaction (e.g. a servlet request)
//
// this class needs to be thread safe, only one thread updates it, but multiple threads can read it
// at the same time as it is being updated
public class Transaction {

    private static final Logger logger = LoggerFactory.getLogger(Transaction.class);

    public static final int USE_GENERAL_STORE_THRESHOLD = -1;

    // initial capacity is very important, see ThreadSafeCollectionOfTenBenchmark
    private static final int ATTRIBUTE_KEYS_INITIAL_CAPACITY = 16;

    // this is just to limit memory (and also to limit display size of trace)
    private static final long ATTRIBUTE_VALUES_PER_KEY_LIMIT = 10000;

    private volatile @Nullable UUID uuid;

    private final long startTime;
    private final long startTick;

    private volatile boolean async;

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

    // stack trace data constructed from profiling
    private volatile @MonotonicNonNull Profile mainThreadProfile;
    private volatile @MonotonicNonNull Profile auxThreadProfile;

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
    // async root timers are the root timers which do not have corresponding thread context
    // (those corresponding to async trace entries)
    // FIXME impose simple max on number of async root timers (AdvancedConfig)
    private volatile @MonotonicNonNull List<AsyncTimerImpl> asyncRootTimers = null;

    private volatile boolean completed;
    private volatile long endTick;

    private final Ticker ticker;

    private final UserProfileScheduler userProfileScheduler;

    private @Nullable TransactionEntry transactionEntry;

    public Transaction(long startTime, long startTick, String transactionType,
            String transactionName, MessageSupplier messageSupplier, TimerName timerName,
            boolean captureThreadStats, int maxTraceEntriesPerTransaction,
            int maxAggregateQueriesPerType, int maxAggregateServiceCallsPerType,
            @Nullable ThreadAllocatedBytes threadAllocatedBytes,
            CompletionCallback completionCallback, Ticker ticker,
            TransactionRegistry transactionRegistry, TransactionServiceImpl transactionService,
            ConfigService configService, UserProfileScheduler userProfileScheduler,
            Holder</*@Nullable*/ ThreadContextImpl> threadContextHolder) {
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
        mainThreadContext = new ThreadContextImpl(castInitialized(this), null, null,
                messageSupplier, timerName, startTick, captureThreadStats, threadAllocatedBytes,
                false, transactionRegistry, transactionService, configService, ticker,
                threadContextHolder, null);
    }

    long getStartTime() {
        return startTime;
    }

    public String getTraceId() {
        if (uuid == null) {
            // double-checked locking works here because uuid is volatile
            //
            // synchronized on "this" as a micro-optimization just so don't need to create an empty
            // object to lock on
            synchronized (this) {
                if (uuid == null) {
                    uuid = UUID.randomUUID();
                }
            }
        }
        return uuid.toString();
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
        MessageSupplier messageSupplier = mainThreadContext.getRootEntry().getMessageSupplier();
        // root trace entry messageSupplier is never be null
        checkNotNull(messageSupplier);
        return ((ReadableMessage) messageSupplier.get()).getText();
    }

    public String getUser() {
        return Strings.nullToEmpty(user);
    }

    public ImmutableSetMultimap<String, String> getAttributes() {
        if (attributes == null) {
            return ImmutableSetMultimap.of();
        }
        SetMultimap<String, String> orderedAttributes =
                TreeMultimap.create(String.CASE_INSENSITIVE_ORDER, String.CASE_INSENSITIVE_ORDER);
        synchronized (attributes) {
            orderedAttributes.putAll(attributes);
        }
        return ImmutableSetMultimap.copyOf(orderedAttributes);
    }

    Map<String, ? extends /*@Nullable*/ Object> getDetail() {
        MessageSupplier messageSupplier = mainThreadContext.getRootEntry().getMessageSupplier();
        // root trace entry messageSupplier is never be null
        checkNotNull(messageSupplier);
        return ((ReadableMessage) messageSupplier.get()).getDetail();
    }

    public @Nullable ErrorMessage getErrorMessage() {
        // don't prefer the root entry error message since it is likely a more generic error
        // message, e.g. servlet response sendError(500)
        if (errorMessage != null) {
            return errorMessage;
        }
        return mainThreadContext.getRootEntry().getErrorMessage();
    }

    public boolean isAsync() {
        return async;
    }

    public TimerImpl getMainThreadRootTimer() {
        memoryBarrierRead();
        return mainThreadContext.getRootTimer();
    }

    public List<AsyncTimerImpl> getAsyncRootTimers() {
        memoryBarrierRead();
        if (asyncRootTimers == null) {
            return ImmutableList.of();
        }
        return asyncRootTimers;
    }

    // can be called from a non-transaction thread
    public ThreadStats getMainThreadStats() {
        return mainThreadContext.getThreadStats();
    }

    public void mergeQueriesInto(QueryCollector queries) {
        memoryBarrierRead();
        mainThreadContext.mergeQueriesInto(queries);
    }

    public void mergeServiceCallsInto(ServiceCallCollector serviceCalls) {
        memoryBarrierRead();
        mainThreadContext.mergeServiceCallsInto(serviceCalls);
    }

    public boolean allowAnotherEntry() {
        return entryLimitCounter++ < maxTraceEntriesPerTransaction;
    }

    public boolean allowAnotherErrorEntry() {
        // use higher entry limit when adding errors, but still need some kind of cap
        return entryLimitCounter++ < maxTraceEntriesPerTransaction
                || extraErrorEntryLimitCounter++ < 2
                        * maxTraceEntriesPerTransaction;
    }

    public boolean allowAnotherAggregateQuery() {
        return aggregateQueryLimitCounter++ < maxAggregateQueriesPerType
                * AdvancedConfig.OVERALL_AGGREGATE_QUERIES_HARD_LIMIT_MULTIPLIER;
    }

    public boolean allowAnotherAggregateServiceCall() {
        return aggregateServiceCallLimitCounter++ < maxAggregateServiceCallsPerType
                * AdvancedConfig.OVERALL_AGGREGATE_SERVICE_CALLS_HARD_LIMIT_MULTIPLIER;
    }

    public List<Trace.Entry> getEntriesProtobuf(long captureTick) {
        memoryBarrierRead();
        return mainThreadContext.getEntriesProtobuf(captureTick);
    }

    long getMainThreadProfileSampleCount() {
        if (mainThreadProfile == null) {
            return 0;
        } else {
            return mainThreadProfile.getSampleCount();
        }
    }

    public @Nullable Profile getMainThreadProfile() {
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

    public @Nullable Profile getAuxThreadProfile() {
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

    public int getSlowThresholdMillisOverride() {
        return slowThresholdMillis;
    }

    public @Nullable Cancellable getUserProfileRunnable() {
        return userProfileRunnable;
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

    public List<ThreadContextImpl> getAuxThreadContexts() {
        return mainThreadContext.getAuxThreadContexts();
    }

    public void setAsync() {
        this.async = true;
    }

    public void setTransactionType(String transactionType, int priority) {
        if (priority > transactionTypePriority && !transactionType.isEmpty()) {
            this.transactionType = transactionType;
            transactionTypePriority = priority;
        }
    }

    public void setTransactionName(String transactionName, int priority) {
        if (priority > transactionNamePriority && !transactionName.isEmpty()) {
            this.transactionName = transactionName;
            transactionNamePriority = priority;
        }
    }

    public void setUser(String user, int priority) {
        if (priority > userPriority && !user.isEmpty()) {
            this.user = user;
            userPriority = priority;
            if (userProfileRunnable == null) {
                userProfileScheduler.maybeScheduleUserProfiling(this, user);
            }
        }
    }

    public void addAttribute(String name, @Nullable String value) {
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

    public void setError(@Nullable String message, @Nullable Throwable t) {
        if (this.errorMessage == null) {
            this.errorMessage = ErrorMessage.from(message, t, getThrowableFrameLimitCounter());
        }
    }

    public void setSlowThresholdMillis(int slowThresholdMillis, int priority) {
        if (priority > slowThresholdMillisPriority) {
            this.slowThresholdMillis = slowThresholdMillis;
            slowThresholdMillisPriority = priority;
        } else if (priority == slowThresholdMillisPriority) {
            // use the minimum threshold from the same override source
            this.slowThresholdMillis = Math.min(this.slowThresholdMillis, slowThresholdMillis);
        }
    }

    public void setUserProfileRunnable(Cancellable userProfileRunnable) {
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

    public void setPartiallyStored() {
        partiallyStored = true;
    }

    public void setTransactionEntry(TransactionEntry transactionEntry) {
        this.transactionEntry = transactionEntry;
    }

    public void removeFromActiveTransactions() {
        checkNotNull(transactionEntry).remove();
    }

    public AsyncTimerImpl startAsyncTimer(TimerName asyncTimerName, long startTick) {
        AsyncTimerImpl asyncTimer = new AsyncTimerImpl((TimerNameImpl) asyncTimerName, startTick);
        if (asyncRootTimers == null) {
            // double-checked locking works here because auxThreadContexts is volatile
            //
            // synchronized on "this" as a micro-optimization just so don't need to create an empty
            // object to lock on
            synchronized (this) {
                if (asyncRootTimers == null) {
                    asyncRootTimers = Lists.newCopyOnWriteArrayList();
                }
            }
        }
        asyncRootTimers.add(asyncTimer);
        return asyncTimer;
    }

    boolean isEntryLimitExceeded() {
        return entryLimitCounter++ > maxTraceEntriesPerTransaction;
    }

    public void captureStackTrace(boolean auxiliary, ThreadInfo threadInfo, int limit) {
        if (completed) {
            return;
        }
        Profile profile;
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
            profile = new Profile();
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
        completed = true;
        this.endTick = endTick;
        mainThreadContext.endCheckAuxThreadContexts();
        if (immedateTraceStoreRunnable != null) {
            immedateTraceStoreRunnable.cancel();
        }
        if (userProfileRunnable != null) {
            userProfileRunnable.cancel();
        }
        completionCallback.completed(this);
    }

    // called by the transaction thread
    public void onCompleteWillStoreTrace(long captureTime) {
        this.captureTime = captureTime;
    }

    long getCaptureTime() {
        return captureTime;
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

    public static interface CompletionCallback {
        void completed(Transaction transaction);
    }
}
