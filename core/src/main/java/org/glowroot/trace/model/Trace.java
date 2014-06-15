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
import org.glowroot.jvm.ThreadAllocatedBytes;
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
    private static final int ATTRIBUTE_KEYS_INITIAL_CAPACITY = 16;

    // a unique identifier
    private final TraceUniqueId id;

    // timing data is tracked in nano seconds which cannot be converted into dates
    // (see javadoc for System.nanoTime()), so the start time is also tracked here
    private final long startTime;

    private final AtomicBoolean stuck = new AtomicBoolean();

    private final boolean background;

    private volatile String transactionName;
    private volatile boolean explicitSetTransactionName;

    // trace-level error, only used if root span doesn't have an ErrorMessage
    @Nullable
    private volatile String error;

    @Nullable
    private volatile String user;

    // lazy loaded to reduce memory when attributes are not used
    @GuardedBy("attributes")
    @MonotonicNonNull
    private volatile SetMultimap<String, String> attributes;

    private final TraceMetric rootMetric;

    private final JvmInfo jvmInfo;

    // root span for this trace
    private final RootSpan rootSpan;

    // stack trace data constructed from coarse-grained profiling
    @MonotonicNonNull
    private volatile Profile coarseProfile;
    // stack trace data constructed from fine-grained profiling
    @MonotonicNonNull
    private volatile Profile fineProfile;

    private final long threadId;

    // overrides general store threshold
    // -1 means don't override the general store threshold
    private volatile int storeThresholdMillisOverride = USE_GENERAL_STORE_THRESHOLD;

    // these are stored in the trace so they are only scheduled a single time, and also so they can
    // be canceled at trace completion
    @Nullable
    private volatile ScheduledRunnable coarseProfilerScheduledRunnable;
    @Nullable
    private volatile ScheduledRunnable fineProfilerScheduledRunnable;
    @Nullable
    private volatile ScheduledRunnable stuckScheduledRunnable;

    public Trace(long startTime, boolean background, String transactionName,
            MessageSupplier messageSupplier, TraceMetric rootTraceMetric, long startTick,
            @Nullable ThreadAllocatedBytes threadAllocatedBytes, Ticker ticker) {
        this.startTime = startTime;
        this.background = background;
        this.transactionName = transactionName;
        this.rootMetric = rootTraceMetric;
        id = new TraceUniqueId(startTime);
        rootSpan = new RootSpan(messageSupplier, rootTraceMetric, startTick, ticker);
        threadId = Thread.currentThread().getId();
        jvmInfo = new JvmInfo(threadAllocatedBytes);
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

    public boolean isBackground() {
        return background;
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

    public String getTransactionName() {
        return transactionName;
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

    // this is called from a non-trace thread
    public TraceMetric getRootMetric() {
        return rootMetric;
    }

    // can be called from a non-trace thread
    public String getJvmInfoJson() {
        return jvmInfo.writeValueAsString();
    }

    public Span getRootSpan() {
        return rootSpan.getRootSpan();
    }

    public int getSpanCount() {
        return rootSpan.getSize();
    }

    public Iterable<Span> getSpans() {
        return rootSpan.getSpans();
    }

    public boolean isFine() {
        return fineProfile != null;
    }

    @Nullable
    public Profile getCoarseProfile() {
        return coarseProfile;
    }

    @Nullable
    public Profile getFineProfile() {
        return fineProfile;
    }

    public int getStoreThresholdMillisOverride() {
        return storeThresholdMillisOverride;
    }

    @Nullable
    public ScheduledRunnable getCoarseProfilerScheduledRunnable() {
        return coarseProfilerScheduledRunnable;
    }

    @Nullable
    public ScheduledRunnable getFineProfilerScheduledRunnable() {
        return fineProfilerScheduledRunnable;
    }

    @Nullable
    public ScheduledRunnable getStuckScheduledRunnable() {
        return stuckScheduledRunnable;
    }

    public void setStuck() {
        stuck.getAndSet(true);
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

    public void addAttribute(String name, @Nullable String value) {
        if (attributes == null) {
            // no race condition here since only trace thread calls addAttribute()
            attributes = HashMultimap.create(ATTRIBUTE_KEYS_INITIAL_CAPACITY, 1);
        }
        String val = Strings.nullToEmpty(value);
        synchronized (attributes) {
            attributes.put(name, val);
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

    public void setCoarseProfilerScheduledRunnable(ScheduledRunnable scheduledRunnable) {
        if (coarseProfilerScheduledRunnable != null) {
            logger.warn("setCoarseProfilerScheduledRunnable(): overwriting non-null"
                    + " coarseProfilingScheduledRunnable");
        }
        this.coarseProfilerScheduledRunnable = scheduledRunnable;
    }

    public void setFineProfilerScheduledRunnable(ScheduledRunnable scheduledRunnable) {
        if (fineProfilerScheduledRunnable != null) {
            logger.warn("setFineProfilerScheduledRunnable(): overwriting non-null"
                    + " fineProfilingScheduledRunnable");
        }
        this.fineProfilerScheduledRunnable = scheduledRunnable;
    }

    public void setStuckScheduledRunnable(ScheduledRunnable scheduledRunnable) {
        if (stuckScheduledRunnable != null) {
            logger.warn("setStuckScheduledRunnable(): overwriting non-null stuckScheduledRunnable");
        }
        this.stuckScheduledRunnable = scheduledRunnable;
    }

    public Span pushSpan(long startTick, MessageSupplier messageSupplier,
            TraceMetricTimerExt metricTimer) {
        return rootSpan.pushSpan(startTick, messageSupplier, metricTimer);
    }

    public Span addSpan(long startTick, long endTick, @Nullable MessageSupplier messageSupplier,
            @Nullable ErrorMessage errorMessage, boolean limitBypassed) {
        return rootSpan.addSpan(startTick, endTick, messageSupplier, errorMessage, limitBypassed);
    }

    public void addSpanLimitExceededMarkerIfNeeded() {
        rootSpan.addSpanLimitExceededMarkerIfNeeded();
    }

    // typically pop() methods don't require the objects to pop, but for safety, the span to pop is
    // passed in just to make sure it is the one on top (and if not, then pop until is is found,
    // preventing any nasty bugs from a missed pop, e.g. a trace never being marked as complete)
    public void popSpan(Span span, long endTick, @Nullable ErrorMessage errorMessage) {
        rootSpan.popSpan(span, endTick, errorMessage);
        TraceMetricTimerExt metricTimer = span.getMetricTimer();
        if (metricTimer != null) {
            metricTimer.end(endTick);
        }
    }

    public void captureStackTrace(boolean fine) {
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
        if (fine) {
            if (fineProfile == null) {
                // initialization possible race condition is ok, worst case scenario it misses
                // an almost simultaneously captured stack trace
                fineProfile = new Profile();
            }
            // TODO make sure that when reading profile it is not in-between instantiation
            // and having its first stack trace here, maybe pass threadInfo to constructor????
            fineProfile.addStackTrace(threadInfo);
        } else {
            if (coarseProfile == null) {
                // initialization possible race condition is ok, worst case scenario it misses
                // an almost simultaneously captured stack trace
                coarseProfile = new Profile();
            }
            coarseProfile.addStackTrace(threadInfo);
        }
    }

    // called by the trace thread
    public void onCompleteAndShouldStore() {
        jvmInfo.onTraceComplete();
    }

    @Override
    @Pure
    public String toString() {
        return Objects.toStringHelper(this)
                .add("id", id)
                .add("startDate", startTime)
                .add("stuck", stuck)
                .add("background", background)
                .add("error", error)
                .add("user", user)
                .add("attributes", attributes)
                .add("rootMetric", rootMetric)
                .add("jvmInfo", jvmInfo)
                .add("rootSpan", rootSpan)
                .add("coarseProfile", coarseProfile)
                .add("fineProfile", fineProfile)
                .add("coarseProfilingScheduledRunnable", coarseProfilerScheduledRunnable)
                .add("fineProfilingScheduledRunnable", fineProfilerScheduledRunnable)
                .add("stuckScheduledRunnable", stuckScheduledRunnable)
                .toString();
    }
}
