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
package org.glowroot.transaction.model;

import java.lang.management.ThreadInfo;
import java.util.List;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import com.google.common.base.Strings;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.TreeMultimap;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.ErrorMessage;
import org.glowroot.api.MessageSupplier;
import org.glowroot.api.internal.ReadableErrorMessage;
import org.glowroot.api.internal.ReadableMessage;
import org.glowroot.common.ScheduledRunnable;
import org.glowroot.common.Ticker;
import org.glowroot.jvm.ThreadAllocatedBytes;
import org.glowroot.transaction.model.GcInfoComponent.GcInfo;
import org.glowroot.transaction.model.ThreadInfoComponent.ThreadInfoData;

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

    // a unique identifier
    private final TraceUniqueId id;

    // timing data is tracked in nano seconds which cannot be converted into dates
    // (see javadoc for System.nanoTime()), so the start time is also tracked here
    private final long startTime;

    private volatile String transactionType;
    private volatile boolean explicitSetTransactionType;

    private volatile String transactionName;
    private volatile boolean explicitSetTransactionName;

    // trace-level error, only used if root entry doesn't have an ErrorMessage
    private volatile @Nullable String error;

    private volatile @Nullable String user;

    // lazy loaded to reduce memory when custom attributes are not used
    @GuardedBy("customAttributes")
    private volatile @MonotonicNonNull SetMultimap<String, String> customAttributes;

    private final TransactionMetricImpl rootMetric;

    private final @Nullable ThreadInfoComponent threadInfoComponent;
    private final @Nullable GcInfoComponent gcInfoComponent;

    // root entry for this trace
    private final TraceEntryComponent traceEntryComponent;

    // stack trace data constructed from profiling
    private volatile @MonotonicNonNull Profile profile;

    private final long threadId;

    // overrides general store threshold
    // -1 means don't override the general store threshold
    private volatile int storeThresholdMillisOverride = USE_GENERAL_STORE_THRESHOLD;

    // these are stored in the trace so they are only scheduled a single time, and also so they can
    // be canceled at trace completion
    private volatile @Nullable ScheduledRunnable userProfileRunnable;
    private volatile @Nullable ScheduledRunnable immedateTraceStoreRunnable;

    private long captureTime;

    // memory barrier is used to ensure memory visibility of entries and metrics at key points,
    // namely after each entry
    //
    // benchmarking shows this is significantly faster than ensuring memory visibility of each
    // metric update, the down side is that the latest updates to metrics for transactions
    // that are captured in-flight (e.g. partial traces and active traces displayed in the UI) may
    // not be visible
    private volatile boolean memoryBarrier;

    public Transaction(long startTime, String transactionType, String transactionName,
            MessageSupplier messageSupplier, TransactionMetricImpl rootMetric, long startTick,
            boolean captureThreadInfo, boolean captureGcInfo,
            @Nullable ThreadAllocatedBytes threadAllocatedBytes, Ticker ticker) {
        this.startTime = startTime;
        this.transactionType = transactionType;
        this.transactionName = transactionName;
        this.rootMetric = rootMetric;
        id = new TraceUniqueId(startTime);
        traceEntryComponent =
                new TraceEntryComponent(messageSupplier, rootMetric, startTick, ticker);
        threadId = Thread.currentThread().getId();
        threadInfoComponent =
                captureThreadInfo ? new ThreadInfoComponent(threadAllocatedBytes) : null;
        gcInfoComponent = captureGcInfo ? new GcInfoComponent() : null;
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

    public long getEndTick() {
        return traceEntryComponent.getEndTick();
    }

    // duration of trace in nanoseconds
    public long getDuration() {
        return traceEntryComponent.getDuration();
    }

    public String getTransactionType() {
        return transactionType;
    }

    public String getTransactionName() {
        return transactionName;
    }

    public String getHeadline() {
        MessageSupplier messageSupplier =
                traceEntryComponent.getRootTraceEntry().getMessageSupplier();
        // messageSupplier should never be null since entry.getMessageSupplier() is only null when
        // the entry was created using addErrorEntry()
        checkNotNull(messageSupplier);
        return ((ReadableMessage) messageSupplier.get()).getText();
    }

    public @Nullable String getError() {
        // don't prefer the root entry error message since it is likely a more generic error
        // message, e.g. servlet response sendError(500)
        if (error != null) {
            return error;
        }
        ReadableErrorMessage message = traceEntryComponent.getRootTraceEntry().getErrorMessage();
        if (message == null) {
            return null;
        }
        return message.getText();
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

    private boolean readMemoryBarrier() {
        return memoryBarrier;
    }

    // this is called from a non-transaction thread
    public TransactionMetricImpl getRootMetric() {
        readMemoryBarrier();
        return rootMetric;
    }

    public @Nullable TransactionMetricImpl getCurrentTransactionMetric() {
        return rootMetric.getCurrentTransactionMetricHolder().get();
    }

    // can be called from a non-transaction thread
    public @Nullable ThreadInfoData getThreadInfo() {
        return threadInfoComponent == null ? null : threadInfoComponent.getThreadInfo();
    }

    // can be called from a non-transaction thread
    public @Nullable List<GcInfo> getGcInfos() {
        return gcInfoComponent == null ? null : gcInfoComponent.getGcInfos();
    }

    public TraceEntry getTraceEntryComponent() {
        return traceEntryComponent.getRootTraceEntry();
    }

    public int getEntryCount() {
        return traceEntryComponent.getSize();
    }

    public ImmutableList<TraceEntry> getEntriesCopy() {
        readMemoryBarrier();
        return traceEntryComponent.getEntriesCopy();
    }

    public int getProfileSampleCount() {
        if (profile == null) {
            return 0;
        } else {
            return profile.getSampleCount();
        }
    }

    public @Nullable Profile getProfile() {
        return profile;
    }

    public int getStoreThresholdMillisOverride() {
        return storeThresholdMillisOverride;
    }

    public @Nullable ScheduledRunnable getUserProfileRunnable() {
        return userProfileRunnable;
    }

    public @Nullable ScheduledRunnable getImmedateTraceStoreRunnable() {
        return immedateTraceStoreRunnable;
    }

    public long getThreadId() {
        return threadId;
    }

    public void setTransactionType(@Nullable String transactionType) {
        // use the first explicit, non-null/non-empty call to setTransactionType()
        if (!explicitSetTransactionType && transactionType != null && !transactionType.isEmpty()) {
            this.transactionType = transactionType;
            explicitSetTransactionType = true;
        }
    }

    public void setTransactionName(@Nullable String transactionName) {
        // use the first explicit, non-null/non-empty call to setTransactionName()
        if (!explicitSetTransactionName && transactionName != null && !transactionName.isEmpty()) {
            this.transactionName = transactionName;
            explicitSetTransactionName = true;
        }
    }

    public void setUser(String user) {
        // use the first non-null/non-empty user
        if (this.user == null) {
            this.user = user;
        }
    }

    public void putCustomAttribute(String name, @Nullable String value) {
        if (customAttributes == null) {
            // no race condition here since only transaction thread calls addAttribute()
            customAttributes = HashMultimap.create(CUSTOM_ATTRIBUTE_KEYS_INITIAL_CAPACITY, 1);
        }
        String val = Strings.nullToEmpty(value);
        synchronized (customAttributes) {
            customAttributes.put(name, val);
        }
    }

    public void setError(@Nullable String error) {
        // use the first non-null/non-empty error
        if (this.error == null && error != null && !error.isEmpty()) {
            this.error = error;
        }
    }

    public void setStoreThresholdMillisOverride(int storeThresholdMillisOverride) {
        if (this.storeThresholdMillisOverride == -1) {
            // first call to this method for this trace, this is normal case
            this.storeThresholdMillisOverride = storeThresholdMillisOverride;
        } else {
            // use the minimum threshold passed to this method
            this.storeThresholdMillisOverride =
                    Math.min(this.storeThresholdMillisOverride, storeThresholdMillisOverride);
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

    public TraceEntry pushEntry(long startTick, MessageSupplier messageSupplier,
            TransactionMetricExt transactionMetric) {
        return traceEntryComponent.pushEntry(startTick, messageSupplier, transactionMetric);
    }

    public TraceEntry addEntry(long startTick, long endTick,
            @Nullable MessageSupplier messageSupplier, @Nullable ErrorMessage errorMessage,
            boolean limitBypassed) {
        TraceEntry entry = traceEntryComponent.addEntry(startTick, endTick, messageSupplier,
                errorMessage, limitBypassed);
        memoryBarrier = true;
        return entry;
    }

    public void addEntryLimitExceededMarkerIfNeeded() {
        traceEntryComponent.addEntryLimitExceededMarkerIfNeeded();
        memoryBarrier = true;
    }

    // typically pop() methods don't require the objects to pop, but for safety, the entry to pop is
    // passed in just to make sure it is the one on top (and if not, then pop until is is found,
    // preventing any nasty bugs from a missed pop, e.g. a trace never being marked as complete)
    public void popEntry(TraceEntry entry, long endTick, @Nullable ErrorMessage errorMessage) {
        traceEntryComponent.popEntry(entry, endTick, errorMessage);
        TransactionMetricExt transactionMetric = entry.getTransactionMetric();
        if (transactionMetric != null) {
            transactionMetric.end(endTick);
        }
        memoryBarrier = true;
    }

    public void captureStackTrace(@Nullable ThreadInfo threadInfo, int limit) {
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
            Profile profile = new Profile();
            profile.addStackTrace(threadInfo, limit);
            this.profile = profile;
        } else {
            profile.addStackTrace(threadInfo, limit);
        }
    }

    // called by the transaction thread
    public void onCompleteAndShouldStore(long captureTime) {
        this.captureTime = captureTime;
        if (threadInfoComponent != null) {
            threadInfoComponent.onTraceComplete();
        }
        if (gcInfoComponent != null) {
            gcInfoComponent.onTraceComplete();
        }
    }

    public long getCaptureTime() {
        return captureTime;
    }
}
