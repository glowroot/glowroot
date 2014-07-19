/*
 * Copyright 2011-2014 the original author or authors.
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
package org.glowroot.trace.model;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.base.Objects;
import com.google.common.base.Strings;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.TreeMultimap;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.ErrorMessage;
import org.glowroot.api.MessageSupplier;
import org.glowroot.api.internal.ReadableErrorMessage;
import org.glowroot.api.internal.ReadableMessage;
import org.glowroot.common.ScheduledRunnable;
import org.glowroot.common.Ticker;
import org.glowroot.markers.GuardedBy;
import org.glowroot.markers.PartiallyThreadSafe;

/**
 * Contains all data that has been captured for a given trace (e.g. a servlet request).
 * 
 * This class needs to be thread safe, only one thread updates it, but multiple threads can read it
 * at the same time as it is being updated.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@PartiallyThreadSafe("pushSpan(), popSpan(), add*() can only be called from trace thread")
public class Trace {

    private static final Logger logger = LoggerFactory.getLogger(Trace.class);

    public static final int USE_GENERAL_STORE_THRESHOLD = -1;

    // initial capacity is very important, see ThreadSafeCollectionOfTenBenchmark
    private static final int CUSTOM_ATTRIBUTE_KEYS_INITIAL_CAPACITY = 16;

    // a unique identifier
    private final TraceUniqueId id;

    // timing data is tracked in nano seconds which cannot be converted into dates
    // (see javadoc for System.nanoTime()), so the start time is also tracked here
    private final long startTime;

    private final AtomicBoolean stuck = new AtomicBoolean();

    private volatile String transactionType;
    private volatile boolean explicitSetTransactionType;

    private volatile String transactionName;
    private volatile boolean explicitSetTransactionName;

    // trace-level error, only used if root span doesn't have an ErrorMessage
    @Nullable
    private volatile String error;

    @Nullable
    private volatile String user;

    // lazy loaded to reduce memory when custom attributes are not used
    @GuardedBy("customAttributes")
    @MonotonicNonNull
    private volatile SetMultimap<String, String> customAttributes;

    private final TraceMetric rootTraceMetric;

    @Nullable
    private final TraceThreadInfo threadInfo;
    @Nullable
    private final TraceGcInfos gcInfos;

    // root span for this trace
    private final RootSpan rootSpan;

    // stack trace data constructed from profiling
    @MonotonicNonNull
    private volatile Profile profile;
    // stack trace data constructed from outlier profiling
    @MonotonicNonNull
    private volatile Profile outlierProfile;

    private final long threadId;

    // overrides general store threshold
    // -1 means don't override the general store threshold
    private volatile int storeThresholdMillisOverride = USE_GENERAL_STORE_THRESHOLD;

    // these are stored in the trace so they are only scheduled a single time, and also so they can
    // be canceled at trace completion
    @Nullable
    private volatile ScheduledRunnable profilerScheduledRunnable;
    @Nullable
    private volatile ScheduledRunnable outlierProfilerScheduledRunnable;
    @Nullable
    private volatile ScheduledRunnable stuckScheduledRunnable;

    // memory barrier is used to ensure memory visibility of spans and trace metrics at key points,
    // namely after each span
    //
    // benchmarking shows this is significantly faster than ensuring memory visibility of each
    // trace metric update, the down side is that the latest updates to trace metrics for snapshots
    // that are captured in-flight (e.g. stuck traces and active traces displayed in the UI) may not
    // be visible
    private volatile boolean memoryBarrier;

    public Trace(long startTime, String transactionType, String transactionName,
            MessageSupplier messageSupplier, TraceMetric rootTraceMetric, long startTick,
            @Nullable TraceThreadInfo threadInfo, @Nullable TraceGcInfos gcInfo, Ticker ticker) {
        this.startTime = startTime;
        this.transactionType = transactionType;
        this.transactionName = transactionName;
        this.rootTraceMetric = rootTraceMetric;
        id = new TraceUniqueId(startTime);
        rootSpan = new RootSpan(messageSupplier, rootTraceMetric, startTick, ticker);
        threadId = Thread.currentThread().getId();
        this.threadInfo = threadInfo;
        this.gcInfos = gcInfo;
    }

    public long getStartTime() {
        return startTime;
    }

    public String getId() {
        return id.get();
    }

    // a couple of properties make sense to expose as part of trace
    public long getStartTick() {
        return rootSpan.getStartTick();
    }

    @Nullable
    public Long getEndTick() {
        return rootSpan.getEndTick();
    }

    // duration of trace in nanoseconds
    public long getDuration() {
        return rootSpan.getDuration();
    }

    public boolean isStuck() {
        return stuck.get();
    }

    public boolean isCompleted() {
        return rootSpan.isCompleted();
    }

    public String getTransactionType() {
        return transactionType;
    }

    public String getTransactionName() {
        return transactionName;
    }

    public String getHeadline() {
        MessageSupplier messageSupplier = rootSpan.getRootSpan().getMessageSupplier();
        if (messageSupplier == null) {
            // this should be impossible since span.getMessageSupplier() is only null when the
            // span was created using addErrorSpan()
            throw new AssertionError("Somehow got hold of an error Span??");
        }
        return ((ReadableMessage) messageSupplier.get()).getText();
    }

    @Nullable
    public String getError() {
        // don't prefer the root span error message since it is likely a more generic error message,
        // e.g. servlet response sendError(500)
        if (error != null) {
            return error;
        }
        ReadableErrorMessage message = rootSpan.getRootSpan().getErrorMessage();
        if (message == null) {
            return null;
        }
        return message.getText();
    }

    @Nullable
    public String getUser() {
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

    public boolean readMemoryBarrier() {
        return memoryBarrier;
    }

    // this is called from a non-trace thread
    public TraceMetric getRootTraceMetric() {
        readMemoryBarrier();
        return rootTraceMetric;
    }

    // can be called from a non-trace thread
    @Nullable
    public String getThreadInfoJson() {
        if (threadInfo == null) {
            return null;
        }
        return threadInfo.writeValueAsString();
    }

    // can be called from a non-trace thread
    @Nullable
    public String getGcInfosJson() {
        if (gcInfos == null) {
            return null;
        }
        return gcInfos.writeValueAsString();
    }

    public Span getRootSpan() {
        return rootSpan.getRootSpan();
    }

    public int getSpanCount() {
        return rootSpan.getSize();
    }

    public ImmutableList<Span> getSpansCopy() {
        readMemoryBarrier();
        return rootSpan.getSpansCopy();
    }

    public boolean isProfiled() {
        return profile != null;
    }

    @Nullable
    public Profile getProfile() {
        return profile;
    }

    @Nullable
    public Profile getOutlierProfile() {
        return outlierProfile;
    }

    public int getStoreThresholdMillisOverride() {
        return storeThresholdMillisOverride;
    }

    @Nullable
    public ScheduledRunnable getProfilerScheduledRunnable() {
        return profilerScheduledRunnable;
    }

    @Nullable
    public ScheduledRunnable getOutlierProfilerScheduledRunnable() {
        return outlierProfilerScheduledRunnable;
    }

    @Nullable
    public ScheduledRunnable getStuckScheduledRunnable() {
        return stuckScheduledRunnable;
    }

    public void setStuck() {
        stuck.getAndSet(true);
    }

    public void setTransactionType(String transactionType) {
        // use the first explicit, non-null call to setTransactionType()
        if (!explicitSetTransactionType && transactionType != null) {
            this.transactionType = transactionType;
            explicitSetTransactionType = true;
        }
    }

    public void setTransactionName(String transactionName) {
        // use the first explicit, non-null call to setTransactionName()
        if (!explicitSetTransactionName && transactionName != null) {
            this.transactionName = transactionName;
            explicitSetTransactionName = true;
        }
    }

    public void setUser(@Nullable String user) {
        // use the first non-null user
        if (this.user == null && user != null) {
            this.user = user;
        }
    }

    public void putCustomAttribute(String name, @Nullable String value) {
        if (customAttributes == null) {
            // no race condition here since only trace thread calls addAttribute()
            customAttributes = HashMultimap.create(CUSTOM_ATTRIBUTE_KEYS_INITIAL_CAPACITY, 1);
        }
        String val = Strings.nullToEmpty(value);
        synchronized (customAttributes) {
            customAttributes.put(name, val);
        }
    }

    public void setError(@Nullable String error) {
        // use the first non-null error
        if (this.error == null && error != null) {
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

    public void setProfilerScheduledRunnable(ScheduledRunnable scheduledRunnable) {
        if (profilerScheduledRunnable != null) {
            logger.warn("setProfilerScheduledRunnable(): overwriting non-null"
                    + " profilerScheduledRunnable");
        }
        this.profilerScheduledRunnable = scheduledRunnable;
    }

    public void setOutlierProfilerScheduledRunnable(ScheduledRunnable scheduledRunnable) {
        if (outlierProfilerScheduledRunnable != null) {
            logger.warn("setOutlierProfilerScheduledRunnable(): overwriting non-null"
                    + " outlierProfilerScheduledRunnable");
        }
        this.outlierProfilerScheduledRunnable = scheduledRunnable;
    }

    public void setStuckScheduledRunnable(ScheduledRunnable scheduledRunnable) {
        if (stuckScheduledRunnable != null) {
            logger.warn("setStuckScheduledRunnable(): overwriting non-null stuckScheduledRunnable");
        }
        this.stuckScheduledRunnable = scheduledRunnable;
    }

    public Span pushSpan(long startTick, MessageSupplier messageSupplier,
            TraceMetricTimerExt traceMetricTimer) {
        return rootSpan.pushSpan(startTick, messageSupplier, traceMetricTimer);
    }

    public Span addSpan(long startTick, long endTick, @Nullable MessageSupplier messageSupplier,
            @Nullable ErrorMessage errorMessage, boolean limitBypassed) {
        Span span = rootSpan.addSpan(startTick, endTick, messageSupplier, errorMessage,
                limitBypassed);
        memoryBarrier = true;
        return span;
    }

    public void addSpanLimitExceededMarkerIfNeeded() {
        rootSpan.addSpanLimitExceededMarkerIfNeeded();
        memoryBarrier = true;
    }

    // typically pop() methods don't require the objects to pop, but for safety, the span to pop is
    // passed in just to make sure it is the one on top (and if not, then pop until is is found,
    // preventing any nasty bugs from a missed pop, e.g. a trace never being marked as complete)
    public void popSpan(Span span, long endTick, @Nullable ErrorMessage errorMessage) {
        rootSpan.popSpan(span, endTick, errorMessage);
        TraceMetricTimerExt traceMetricTimer = span.getTraceMetricTimer();
        if (traceMetricTimer != null) {
            traceMetricTimer.end(endTick);
        }
        memoryBarrier = true;
    }

    public void captureStackTrace(boolean outlier) {
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        ThreadInfo threadInfo = threadBean.getThreadInfo(threadId, Integer.MAX_VALUE);
        if (threadInfo == null) {
            // thread is no longer alive
            return;
        }
        // check if trace is completed to avoid small window between trace completion and
        // canceling the scheduled command that invokes this method
        if (rootSpan.isCompleted()) {
            return;
        }
        if (outlier) {
            if (outlierProfile == null) {
                // initialization possible race condition is ok, worst case scenario it misses
                // an almost simultaneously captured stack trace
                outlierProfile = new Profile();
            }
            outlierProfile.addStackTrace(threadInfo);
        } else {
            if (profile == null) {
                // initialization possible race condition is ok, worst case scenario it misses
                // an almost simultaneously captured stack trace
                profile = new Profile();
            }
            // TODO make sure that when reading profile it is not in-between instantiation
            // and having its first stack trace here, maybe pass threadInfo to constructor????
            profile.addStackTrace(threadInfo);
        }
    }

    // called by the trace thread
    public void onCompleteAndShouldStore() {
        if (threadInfo != null) {
            threadInfo.onTraceComplete();
        }
        if (gcInfos != null) {
            gcInfos.onTraceComplete();
        }
    }

    @Override
    @Pure
    public String toString() {
        return Objects.toStringHelper(this)
                .add("id", id)
                .add("startTime", startTime)
                .add("stuck", stuck)
                .add("transactionType", transactionType)
                .add("explicitSetTransactionType", explicitSetTransactionType)
                .add("transactionName", transactionName)
                .add("explicitSetTransactionName", explicitSetTransactionName)
                .add("error", error)
                .add("user", user)
                .add("customAttributes", customAttributes)
                .add("rootTraceMetric", rootTraceMetric)
                .add("threadInfo", threadInfo)
                .add("gcInfos", gcInfos)
                .add("rootSpan", rootSpan)
                .add("profile", profile)
                .add("outlierProfile", outlierProfile)
                .add("profilerScheduledRunnable", profilerScheduledRunnable)
                .add("outlierProfilerScheduledRunnable", outlierProfilerScheduledRunnable)
                .add("stuckScheduledRunnable", stuckScheduledRunnable)
                .toString();
    }
}
