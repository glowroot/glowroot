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

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import com.google.common.base.Function;
import com.google.common.base.Strings;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.base.Ticker;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.TreeMultimap;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.config.AdvancedConfig;
import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.impl.TransactionRegistry;
import org.glowroot.agent.impl.TransactionServiceImpl;
import org.glowroot.agent.impl.UserProfileScheduler;
import org.glowroot.agent.plugin.api.Message;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.internal.ReadableMessage;
import org.glowroot.agent.plugin.api.util.FastThreadLocal.Holder;
import org.glowroot.agent.util.ThreadAllocatedBytes;
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

    private static final AtomicBoolean loggedBackgroundTransactionSuggestion = new AtomicBoolean();

    private final Supplier<UUID> uuid = Suppliers.memoize(new Supplier<UUID>() {
        @Override
        public UUID get() {
            return UUID.randomUUID();
        }
    });

    private final long startTime;
    private final long startTick;

    private volatile String transactionType;
    private volatile @Nullable OverrideSource transactionTypeOverrideSource;

    private volatile String transactionName;
    private volatile @Nullable OverrideSource transactionNameOverrideSource;

    private volatile @Nullable String user;
    private volatile @Nullable OverrideSource userOverrideSource;

    // lazy loaded to reduce memory when custom attributes are not used
    @GuardedBy("attributes")
    private volatile @MonotonicNonNull SetMultimap<String, String> attributes;

    // trace-level error
    private volatile @Nullable ErrorMessage errorMessage;
    private volatile @Nullable OverrideSource errorMessageOverrideSource;

    private final boolean captureThreadStats;
    private final GcActivityComponent gcActivityComponent;

    private final int maxTraceEntriesPerTransaction;
    private final int maxAggregateQueriesPerQueryType;

    // stack trace data constructed from profiling
    private volatile @MonotonicNonNull Profile mainThreadProfile;
    private volatile @MonotonicNonNull Profile auxThreadProfile;

    // overrides general store threshold
    // -1 means don't override the general store threshold
    private volatile int slowThresholdMillis = USE_GENERAL_STORE_THRESHOLD;
    private volatile @Nullable OverrideSource slowThresholdMillisOverrideSource;

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

    private final AtomicInteger entryLimitCounter = new AtomicInteger();
    private final AtomicInteger extraErrorEntryLimitCounter = new AtomicInteger();
    private final AtomicInteger aggregateQueryLimitCounter = new AtomicInteger();

    private final ThreadContextImpl mainThreadContext;
    // FIXME impose simple max on number of auxiliary thread contexts (AdvancedConfig)
    private final List<ThreadContextImpl> auxThreadContexts = Lists.newCopyOnWriteArrayList();
    // async root timers are the root timers which do not have corresponding thread context
    // (those corresponding to async trace entries)
    // FIXME impose simple max on number of async root timers (AdvancedConfig)
    private final List<AsyncTimerImpl> asyncRootTimers = Lists.newCopyOnWriteArrayList();

    private volatile boolean completed;
    private volatile long endTick;

    private final Ticker ticker;

    private final TransactionRegistry transactionRegistry;
    private final TransactionServiceImpl transactionService;
    private final ConfigService configService;
    private final UserProfileScheduler userProfileScheduler;

    public Transaction(long startTime, long startTick, String transactionType,
            String transactionName, MessageSupplier messageSupplier, TimerName timerName,
            boolean captureThreadStats, int maxTraceEntriesPerTransaction,
            int maxAggregateQueriesPerQueryType,
            @Nullable ThreadAllocatedBytes threadAllocatedBytes,
            CompletionCallback completionCallback, Ticker ticker,
            TransactionRegistry transactionRegistry, TransactionServiceImpl transactionService,
            ConfigService configService, UserProfileScheduler userProfileScheduler,
            Holder</*@Nullable*/ ThreadContextImpl> threadContextHolder) {
        this.startTime = startTime;
        this.startTick = startTick;
        this.transactionType = transactionType;
        this.transactionName = transactionName;
        this.captureThreadStats = captureThreadStats;
        gcActivityComponent = new GcActivityComponent();
        this.maxTraceEntriesPerTransaction = maxTraceEntriesPerTransaction;
        this.maxAggregateQueriesPerQueryType = maxAggregateQueriesPerQueryType;
        this.completionCallback = completionCallback;
        this.ticker = ticker;
        this.transactionRegistry = transactionRegistry;
        this.transactionService = transactionService;
        this.configService = configService;
        this.userProfileScheduler = userProfileScheduler;
        mainThreadContext = new ThreadContextImpl(castInitialized(this), null, messageSupplier,
                timerName, startTick, captureThreadStats, threadAllocatedBytes, false,
                transactionRegistry, transactionService, configService, ticker,
                threadContextHolder);
    }

    public TraceEntryImpl startAuxThreadContext(TraceEntryImpl parentTraceEntry,
            TimerName auxTimerName, long startTick,
            Holder</*@Nullable*/ ThreadContextImpl> threadContextHolder,
            @Nullable ThreadAllocatedBytes threadAllocatedBytes) {
        ThreadContextImpl auxThreadContext = new ThreadContextImpl(this, parentTraceEntry,
                AuxThreadRootMessageSupplier.INSTANCE, auxTimerName, startTick,
                captureThreadStats, threadAllocatedBytes, true, transactionRegistry,
                transactionService, configService, ticker, threadContextHolder);
        auxThreadContexts.add(auxThreadContext);
        threadContextHolder.set(auxThreadContext);
        return auxThreadContext.getRootEntry();
    }

    long getStartTime() {
        return startTime;
    }

    public String getTraceId() {
        return uuid.get().toString();
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

    public boolean isAsynchronous() {
        // TODO
        return false;
    }

    public TimerImpl getMainThreadRootTimer() {
        readMemoryBarrier();
        return mainThreadContext.getRootTimer();
    }

    public Iterable<TimerImpl> getAuxThreadRootTimers() {
        readMemoryBarrier();
        if (auxThreadContexts.isEmpty()) {
            // optimization for common case
            return ImmutableList.of();
        }
        return Iterables.transform(auxThreadContexts, GetRootTimerFunction.INSTANCE);
    }

    public List<AsyncTimerImpl> getAsyncRootTimers() {
        readMemoryBarrier();
        return asyncRootTimers;
    }

    // can be called from a non-transaction thread
    public ThreadStats getMainThreadStats() {
        return mainThreadContext.getThreadStats();
    }

    // can be called from a non-transaction thread
    public Iterable<ThreadStats> getAuxThreadStats() {
        if (auxThreadContexts.isEmpty()) {
            return ImmutableList.of();
        }
        if (!captureThreadStats) {
            return ImmutableList.of(ThreadStats.NA);
        }
        return Iterables.transform(auxThreadContexts, GetThreadStatsFunction.INSTANCE);
    }

    // can be called from a non-transaction thread
    List<Trace.GarbageCollectionActivity> getGcActivity() {
        return gcActivityComponent.getGcActivity();
    }

    public Iterator<QueryData> getQueries() {
        readMemoryBarrier();
        if (auxThreadContexts.size() == 1) {
            // optimization for common case
            return mainThreadContext.getQueries();
        }
        List<Iterator<QueryData>> queries =
                Lists.newArrayListWithCapacity(auxThreadContexts.size() + 1);
        queries.add(mainThreadContext.getQueries());
        for (ThreadContextImpl threadContext : auxThreadContexts) {
            queries.add(threadContext.getQueries());
        }
        return Iterators.concat(queries.iterator());
    }

    public boolean allowAnotherEntry() {
        return entryLimitCounter.getAndIncrement() < maxTraceEntriesPerTransaction;
    }

    public boolean allowAnotherErrorEntry() {
        // use higher entry limit when adding errors, but still need some kind of cap
        return entryLimitCounter.getAndIncrement() < maxTraceEntriesPerTransaction
                || extraErrorEntryLimitCounter.getAndIncrement() < 2
                        * maxTraceEntriesPerTransaction;
    }

    public boolean allowAnotherAggregateQuery() {
        return aggregateQueryLimitCounter.getAndIncrement() < maxAggregateQueriesPerQueryType
                * AdvancedConfig.OVERALL_AGGREGATE_QUERIES_HARD_LIMIT_MULTIPLIER;
    }

    public List<Trace.Entry> getEntriesProtobuf(long captureTick) {
        readMemoryBarrier();
        Multimap<TraceEntryImpl, TraceEntryImpl> auxRootTraceEntries = ArrayListMultimap.create();
        for (ThreadContextImpl auxThreadContext : auxThreadContexts) {
            // checkNotNull is safe b/c auxiliary thread contexts have non-null parent trace entry
            TraceEntryImpl parentTraceEntry =
                    checkNotNull(auxThreadContext.getParentTraceEntry());
            TraceEntryImpl rootEntry = auxThreadContext.getRootEntry();
            if (rootEntry.getNextTraceEntry() != null) {
                // root entry is just "auxiliary thread" root placeholder, don't include if there
                // are no sub entries
                auxRootTraceEntries.put(parentTraceEntry, rootEntry);
            }
        }
        return mainThreadContext.getEntriesProtobuf(captureTick, auxRootTraceEntries);
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
        return auxThreadContexts;
    }

    public void setTransactionType(String transactionType, OverrideSource overrideSource) {
        if (transactionTypeOverrideSource == null
                || transactionTypeOverrideSource.priority < overrideSource.priority) {
            this.transactionType = transactionType;
            transactionTypeOverrideSource = overrideSource;
        }
    }

    public void setTransactionName(String transactionName, OverrideSource overrideSource) {
        if (transactionNameOverrideSource == null
                || transactionNameOverrideSource.priority < overrideSource.priority) {
            this.transactionName = transactionName;
            transactionNameOverrideSource = overrideSource;
        }
    }

    public void setUser(String user, OverrideSource overrideSource) {
        if (userOverrideSource == null || userOverrideSource.priority < overrideSource.priority) {
            this.user = user;
            userOverrideSource = overrideSource;
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

    public void setError(ErrorMessage errorMessage, OverrideSource overrideSource) {
        if (errorMessageOverrideSource == null
                || errorMessageOverrideSource.priority < overrideSource.priority) {
            this.errorMessage = errorMessage;
            errorMessageOverrideSource = overrideSource;
        }
    }

    public void setSlowThresholdMillis(int slowThresholdMillis, OverrideSource overrideSource) {
        if (slowThresholdMillisOverrideSource == null
                || slowThresholdMillisOverrideSource.priority < overrideSource.priority) {
            this.slowThresholdMillis = slowThresholdMillis;
            slowThresholdMillisOverrideSource = overrideSource;
        } else if (slowThresholdMillisOverrideSource.priority == overrideSource.priority) {
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

    public AsyncTimerImpl startAsyncTimer(TimerName asyncTimerName, long startTick) {
        AsyncTimerImpl asyncTimer = new AsyncTimerImpl((TimerNameImpl) asyncTimerName, startTick);
        asyncRootTimers.add(asyncTimer);
        return asyncTimer;
    }

    boolean isEntryLimitExceeded() {
        return entryLimitCounter.get() > maxTraceEntriesPerTransaction;
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

    void end(long endTick) {
        completed = true;
        this.endTick = endTick;
        for (ThreadContextImpl auxThreadContext : auxThreadContexts) {
            // FIXME how to suppress false positives??
            if (!loggedBackgroundTransactionSuggestion.get()) {
                ThreadInfo threadInfo = ManagementFactory.getThreadMXBean()
                        .getThreadInfo(auxThreadContext.getThreadId(), Integer.MAX_VALUE);
                if (threadInfo != null && !auxThreadContext.isCompleted()) {
                    // still not complete, got a valid stack trace from auxiliary thread

                    // race condition getting/setting the static atomic boolean is ok, at worst it
                    // logs the message 2x
                    loggedBackgroundTransactionSuggestion.set(true);
                    StringBuilder sb = new StringBuilder();
                    for (StackTraceElement stackTraceElement : threadInfo.getStackTrace()) {
                        sb.append("    ");
                        sb.append(stackTraceElement.toString());
                        sb.append('\n');
                    }
                    logger.warn("auxiliary thread extended beyond the transaction which started it,"
                            + " maybe this should be captured as a background transaction instead"
                            + " -- this message will be logged only once\n{}", sb);
                }
            }
            // FIXME detach transaction from auxiliary thread context for memory consideration
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
    public void onCompleteWillStoreTrace(long captureTime) {
        this.captureTime = captureTime;
        gcActivityComponent.onComplete();
    }

    long getCaptureTime() {
        return captureTime;
    }

    private boolean readMemoryBarrier() {
        return memoryBarrier;
    }

    void writeMemoryBarrier() {
        memoryBarrier = true;
    }

    public static interface CompletionCallback {
        void completed(Transaction transaction);
    }

    public enum OverrideSource {

        // higher priority wins
        // STARTUP is max to ensure startup threshold (which is 0) cannot be accidentally overridden
        PLUGIN_API(1), USER_API(2), USER_RECORDING(3), STARTUP(1000);

        private final int priority;

        private OverrideSource(int priority) {
            this.priority = priority;
        }
    }

    private static class AuxThreadRootMessageSupplier extends MessageSupplier {

        private static final AuxThreadRootMessageSupplier INSTANCE =
                new AuxThreadRootMessageSupplier();

        private final Message message = Message.from("auxiliary thread");

        @Override
        public Message get() {
            return message;
        }
    }

    private static class GetRootTimerFunction implements Function<ThreadContextImpl, TimerImpl> {

        private static final GetRootTimerFunction INSTANCE = new GetRootTimerFunction();

        @Override
        public TimerImpl apply(@Nullable ThreadContextImpl input) {
            checkNotNull(input);
            return input.getRootTimer();
        }
    }

    private static class GetThreadStatsFunction
            implements Function<ThreadContextImpl, ThreadStats> {

        private static final GetThreadStatsFunction INSTANCE = new GetThreadStatsFunction();

        @Override
        public ThreadStats apply(@Nullable ThreadContextImpl input) {
            checkNotNull(input);
            return input.getThreadStats();
        }
    }
}
