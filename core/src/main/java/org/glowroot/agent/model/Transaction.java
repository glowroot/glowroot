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
package org.glowroot.agent.model;

import java.io.IOException;
import java.lang.management.ThreadInfo;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import com.google.common.base.Strings;
import com.google.common.base.Ticker;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.TreeMultimap;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.model.ThreadInfoComponent.ThreadInfoData;
import org.glowroot.agent.util.ThreadAllocatedBytes;
import org.glowroot.collector.spi.GarbageCollectorActivity;
import org.glowroot.collector.spi.ProfileNode;
import org.glowroot.collector.spi.TraceEntry;
import org.glowroot.common.config.AdvancedConfig;
import org.glowroot.common.util.ScheduledRunnable;
import org.glowroot.plugin.api.transaction.MessageSupplier;
import org.glowroot.plugin.api.transaction.TimerName;
import org.glowroot.plugin.api.transaction.internal.ReadableMessage;

import static com.google.common.base.Preconditions.checkNotNull;

// contains all data that has been captured for a given transaction (e.g. a servlet request)
//
// this class needs to be thread safe, only one thread updates it, but multiple threads can read it
// at the same time as it is being updated
public class Transaction {

    private static final Logger logger = LoggerFactory.getLogger(Transaction.class);

    public static final int USE_GENERAL_STORE_THRESHOLD = -1;

    // initial capacity is very important, see ThreadSafeCollectionOfTenBenchmark
    private static final int CUSTOM_ATTRIBUTE_KEYS_INITIAL_CAPACITY = 16;

    // this is just to limit memory (and also to limit display size of trace)
    private static final long CUSTOM_ATTRIBUTE_VALUES_PER_KEY_LIMIT = 10000;

    // a unique identifier
    private final TraceUniqueId id;

    // timing data is tracked in nano seconds which cannot be converted into dates
    // (see javadoc for System.nanoTime()), so the start time is also tracked here
    private final long startTime;

    private volatile String transactionType;
    private volatile @Nullable OverrideSource transactionTypeOverrideSource;

    private volatile String transactionName;
    private volatile @Nullable OverrideSource transactionNameOverrideSource;

    private volatile @Nullable String user;
    private volatile @Nullable OverrideSource userOverrideSource;

    // lazy loaded to reduce memory when custom attributes are not used
    @GuardedBy("customAttributes")
    private volatile @MonotonicNonNull SetMultimap<String, String> customAttributes;

    // trace-level error
    private volatile @Nullable ErrorMessage errorMessage;
    private volatile @Nullable OverrideSource errorMessageOverrideSource;

    private final TimerImpl rootTimer;
    // currentTimer doesn't need to be thread safe as it is only accessed by transaction thread
    private @Nullable TimerImpl currentTimer;

    private final @Nullable ThreadInfoComponent threadInfoComponent;
    private final @Nullable GcActivityComponent gcActivityComponent;

    // root entry for this trace
    private final TraceEntryComponent traceEntryComponent;

    // linked list of QueryData instances for safe concurrent access
    private @MonotonicNonNull QueryData headQueryData;
    // these maps are only accessed by the transaction thread
    private @MonotonicNonNull Map<String, QueryData> firstQueryTypeQueries;
    private @MonotonicNonNull Map<String, Map<String, QueryData>> allQueryTypesMap;
    private final int maxAggregateQueriesPerQueryType;

    // stack trace data constructed from profiling
    private volatile @MonotonicNonNull Profile profile;

    private final long threadId;

    // overrides general store threshold
    // -1 means don't override the general store threshold
    private volatile int slowThresholdMillis = USE_GENERAL_STORE_THRESHOLD;
    private volatile @Nullable OverrideSource slowThresholdMillisOverrideSource;

    // these are stored in the trace so they are only scheduled a single time, and also so they can
    // be canceled at trace completion
    private volatile @MonotonicNonNull ScheduledRunnable userProfileRunnable;
    private volatile @MonotonicNonNull ScheduledRunnable immedateTraceStoreRunnable;

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

    public Transaction(long startTime, String transactionType, String transactionName,
            MessageSupplier messageSupplier, TimerName timerName, long startTick,
            boolean captureThreadInfo, boolean captureGcActivity,
            int maxAggregateQueriesPerQueryType,
            @Nullable ThreadAllocatedBytes threadAllocatedBytes,
            CompletionCallback completionCallback, Ticker ticker) {
        this.startTime = startTime;
        this.transactionType = transactionType;
        this.transactionName = transactionName;
        id = new TraceUniqueId(startTime);
        // suppress warning for passing @UnderInitialization this
        @SuppressWarnings("argument.type.incompatible")
        TimerImpl rootTimer = TimerImpl.createRootTimer(this, (TimerNameImpl) timerName);
        this.rootTimer = rootTimer;
        rootTimer.start(startTick);
        traceEntryComponent =
                new TraceEntryComponent(messageSupplier, rootTimer, startTick, ticker);
        threadId = Thread.currentThread().getId();
        threadInfoComponent =
                captureThreadInfo ? new ThreadInfoComponent(threadAllocatedBytes) : null;
        gcActivityComponent = captureGcActivity ? new GcActivityComponent() : null;
        this.maxAggregateQueriesPerQueryType = maxAggregateQueriesPerQueryType;
        this.completionCallback = completionCallback;
    }

    public long getStartTime() {
        return startTime;
    }

    public String getId() {
        return id.get();
    }

    // a couple of properties make sense to expose as part of trace
    public long getStartTick() {
        return traceEntryComponent.getStartTick();
    }

    public boolean isCompleted() {
        return traceEntryComponent.isCompleted();
    }

    long getEndTick() {
        return traceEntryComponent.getEndTick();
    }

    public long getDurationNanos() {
        return traceEntryComponent.getDurationNanos();
    }

    public String getTransactionType() {
        return transactionType;
    }

    public String getTransactionName() {
        return transactionName;
    }

    public String getHeadline() {
        MessageSupplier messageSupplier = traceEntryComponent.getRootEntry().getMessageSupplier();
        // root trace entry messageSupplier is never be null
        checkNotNull(messageSupplier);
        return ((ReadableMessage) messageSupplier.get()).getText();
    }

    public @Nullable String getUser() {
        return user;
    }

    public ImmutableSetMultimap<String, String> getCustomAttributes() {
        if (customAttributes == null) {
            return ImmutableSetMultimap.of();
        }
        SetMultimap<String, String> orderedCustomAttributes =
                TreeMultimap.create(String.CASE_INSENSITIVE_ORDER, String.CASE_INSENSITIVE_ORDER);
        synchronized (customAttributes) {
            orderedCustomAttributes.putAll(customAttributes);
        }
        return ImmutableSetMultimap.copyOf(orderedCustomAttributes);
    }

    Map<String, ? extends /*@Nullable*/Object> getCustomDetail() {
        MessageSupplier messageSupplier = traceEntryComponent.getRootEntry().getMessageSupplier();
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
        return traceEntryComponent.getRootEntry().getErrorMessage();
    }

    // this is called from a non-transaction thread
    public TimerImpl getRootTimer() {
        readMemoryBarrier();
        return rootTimer;
    }

    public @Nullable TimerImpl getCurrentTimer() {
        return currentTimer;
    }

    // can be called from a non-transaction thread
    @Nullable
    ThreadInfoData getThreadInfo() {
        return threadInfoComponent == null ? null : threadInfoComponent.getThreadInfo();
    }

    // can be called from a non-transaction thread
    List<GarbageCollectorActivity> getGcActivity() {
        return gcActivityComponent == null ? ImmutableList.<GarbageCollectorActivity>of()
                : gcActivityComponent.getGcActivity();
    }

    public TraceEntryImpl getRootEntry() {
        return traceEntryComponent.getRootEntry();
    }

    public int getEntryCount() {
        return traceEntryComponent.getEntryCount();
    }

    public Iterable<QueryData> getQueries() {
        // read memory barrier is for QueryData values
        readMemoryBarrier();
        if (headQueryData == null) {
            return ImmutableList.of();
        }
        return new Iterable<QueryData>() {
            @Override
            public Iterator<QueryData> iterator() {
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
        };
    }

    public Collection<TraceEntry> getEntries() {
        readMemoryBarrier();
        return traceEntryComponent.getEntries();
    }

    long getProfileSampleCount() {
        if (profile == null) {
            return 0;
        } else {
            return profile.getSampleCount();
        }
    }

    @Nullable
    Profile getProfile() {
        return profile;
    }

    public @Nullable ProfileNode getSyntheticRootProfileNode() throws IOException {
        if (profile == null) {
            return null;
        }
        if (isCompleted()) {
            synchronized (profile.getLock()) {
                // even though complete, still need lock to ensure visibility
                return profile.getSyntheticRootNode();
            }
        } else {
            synchronized (profile.getLock()) {
                return profile.getSyntheticRootNode().copy();
            }
        }
    }

    // TODO implement profile limit
    boolean isProfileLimitExceeded() {
        return false;
    }

    public int getSlowThresholdMillisOverride() {
        return slowThresholdMillis;
    }

    public @Nullable ScheduledRunnable getUserProfileRunnable() {
        return userProfileRunnable;
    }

    public @Nullable ScheduledRunnable getImmedateTraceStoreRunnable() {
        return immedateTraceStoreRunnable;
    }

    public boolean isPartiallyStored() {
        return partiallyStored;
    }

    public long getThreadId() {
        return threadId;
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
        }
    }

    public void addCustomAttribute(String name, @Nullable String value) {
        if (customAttributes == null) {
            // no race condition here since only transaction thread calls addAttribute()
            customAttributes = HashMultimap.create(CUSTOM_ATTRIBUTE_KEYS_INITIAL_CAPACITY, 1);
        }
        String val = Strings.nullToEmpty(value);
        synchronized (customAttributes) {
            Collection<String> values = customAttributes.get(name);
            if (values.size() < CUSTOM_ATTRIBUTE_VALUES_PER_KEY_LIMIT) {
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

    public void setUserProfileRunnable(ScheduledRunnable scheduledRunnable) {
        if (userProfileRunnable != null) {
            logger.warn("setUserProfileRunnable(): overwriting non-null userProfileRunnable");
        }
        this.userProfileRunnable = scheduledRunnable;
    }

    public void setImmediateTraceStoreRunnable(ScheduledRunnable scheduledRunnable) {
        if (immedateTraceStoreRunnable != null) {
            logger.warn("setImmediateTraceStoreRunnable(): overwriting non-null"
                    + " immedateTraceStoreRunnable");
        }
        this.immedateTraceStoreRunnable = scheduledRunnable;
    }

    public void setPartiallyStored() {
        partiallyStored = true;
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

    // only called by transaction thread
    public @Nullable QueryData getOrCreateQueryDataIfPossible(String queryType, String queryText) {
        if (headQueryData == null) {
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
        if (queryData == null && currentQueryTypeQueries.size() < maxAggregateQueriesPerQueryType
                * AdvancedConfig.OVERALL_AGGREGATE_QUERIES_HARD_LIMIT_MULTIPLIER) {
            queryData = new QueryData(queryType, queryText, headQueryData);
            currentQueryTypeQueries.put(queryText, queryData);
            headQueryData = queryData;
        }
        return queryData;
    }

    public TraceEntryImpl addEntry(long startTick, long endTick,
            @Nullable MessageSupplier messageSupplier, @Nullable ErrorMessage errorMessage,
            boolean limitBypassed) {
        TraceEntryImpl entry = traceEntryComponent.addEntry(startTick, endTick, messageSupplier,
                errorMessage, limitBypassed);
        memoryBarrier = true;
        return entry;
    }

    public void setEntryLimitExceeded() {
        traceEntryComponent.setEntryLimitExceeded();
        memoryBarrier = true;
    }

    boolean isEntryLimitExceeded() {
        return traceEntryComponent.isEntryLimitExceeded();
    }

    public void captureStackTrace(@Nullable ThreadInfo threadInfo, int limit,
            boolean mayHaveSyntheticTimerMethods) {
        if (threadInfo == null) {
            // thread is no longer alive
            return;
        }
        if (traceEntryComponent.isCompleted()) {
            return;
        }
        if (profile == null) {
            // initialization possible race condition (between StackTraceCollector and
            // UserProfileRunnable) is ok, worst case scenario it misses an almost simultaneously
            // captured stack trace
            //
            // profile is constructed and first stack trace is added prior to setting the
            // transaction profile field, so that it is not possible to read a profile that doesn't
            // have at least one stack trace
            Profile profile = new Profile(mayHaveSyntheticTimerMethods);
            profile.addStackTrace(threadInfo, limit);
            this.profile = profile;
        } else {
            profile.addStackTrace(threadInfo, limit);
        }
    }

    // called by the transaction thread
    public void onCompleteCaptureThreadInfo() {
        if (threadInfoComponent != null) {
            threadInfoComponent.onComplete();
        }
    }

    // called by the transaction thread
    public void onCompleteCaptureGcActivity() {
        if (gcActivityComponent != null) {
            gcActivityComponent.onComplete();
        }
    }

    // called by the transaction thread
    public void onComplete(long captureTime) {
        this.captureTime = captureTime;
    }

    long getCaptureTime() {
        return captureTime;
    }

    // typically pop() methods don't require the objects to pop, but for safety, the entry to pop is
    // passed in just to make sure it is the one on top (and if not, then pop until is is found,
    // preventing any nasty bugs from a missed pop, e.g. a trace never being marked as complete)
    void popEntry(TraceEntryImpl entry, long endTick) {
        traceEntryComponent.popEntry(entry, endTick);
        memoryBarrier = true;
        if (isCompleted()) {
            // the root entry has been popped off
            if (immedateTraceStoreRunnable != null) {
                immedateTraceStoreRunnable.cancel();
            }
            if (userProfileRunnable != null) {
                userProfileRunnable.cancel();
            }
            completionCallback.completed(this);
        }
    }

    void setCurrentTimer(@Nullable TimerImpl currentTimer) {
        this.currentTimer = currentTimer;
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

    private boolean readMemoryBarrier() {
        return memoryBarrier;
    }

    public static interface CompletionCallback {
        void completed(Transaction transaction);
    }

    public enum OverrideSource {

        // higher priority wins
        PLUGIN_API(1), USER_API(2);

        private final int priority;

        private OverrideSource(int priority) {
            this.priority = priority;
        }
    }
}
