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
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import checkers.igj.quals.Immutable;
import checkers.igj.quals.ReadOnly;
import checkers.lock.quals.GuardedBy;
import checkers.nullness.quals.MonotonicNonNull;
import checkers.nullness.quals.Nullable;
import com.google.common.base.Objects;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import dataflow.quals.Pure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.ErrorMessage;
import org.glowroot.api.MessageSupplier;
import org.glowroot.api.internal.ReadableErrorMessage;
import org.glowroot.api.internal.ReadableMessage;
import org.glowroot.common.ScheduledRunnable;
import org.glowroot.jvm.ThreadAllocatedBytes;
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
@PartiallyThreadSafe("pushSpan(), addSpan(), popSpan(), addMetric(),"
        + "  resetThreadLocalMetrics() can only be called from constructing thread")
public class Trace {

    private static final Logger logger = LoggerFactory.getLogger(Trace.class);

    // initial capacity is very important, see ThreadSafeCollectionOfTenBenchmark
    private static final int ATTRIBUTES_LIST_INITIAL_CAPACITY = 16;
    private static final int METRICS_LIST_INITIAL_CAPACITY = 32;

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
    private volatile List<TraceAttribute> attributes;

    // see performance comparison of synchronized ArrayList vs ConcurrentLinkedQueue in
    // ThreadSafeCollectionOfTenBenchmark
    @GuardedBy("metrics")
    private final List<Metric> metrics;

    // the MetricNames are tracked since they contain thread locals that need to be cleared at the
    // end of the trace
    //
    // this doesn't need to be thread safe as it is only accessed by the trace thread
    private final List<MetricNameImpl> metricNames = Lists.newArrayList();

    private final JvmInfo jvmInfo;

    // root span for this trace
    private final RootSpan rootSpan;

    // stack trace data constructed from coarse-grained profiling
    @MonotonicNonNull
    private volatile MergedStackTree coarseMergedStackTree;
    // stack trace data constructed from fine-grained profiling
    @MonotonicNonNull
    private volatile MergedStackTree fineMergedStackTree;

    private final long threadId;

    // overrides general store threshold
    // -1 means don't override the general store threshold
    private volatile int storeThresholdMillisOverride = -1;

    // these are stored in the trace so they are only scheduled a single time, and also so they can
    // be canceled at trace completion
    @Nullable
    private volatile ScheduledRunnable coarseProfilerScheduledRunnable;
    @Nullable
    private volatile ScheduledRunnable fineProfilerScheduledRunnable;
    @Nullable
    private volatile ScheduledRunnable stuckScheduledRunnable;

    public Trace(long startTime, boolean background, String transactionName,
            MessageSupplier messageSupplier, MetricNameImpl metricName,
            @Nullable ThreadAllocatedBytes threadAllocatedBytes, Ticker ticker) {
        this.startTime = startTime;
        this.background = background;
        this.transactionName = transactionName;
        id = new TraceUniqueId(startTime);
        long startTick = ticker.read();
        Metric metric = metricName.create();
        metric.start(startTick);
        rootSpan = new RootSpan(messageSupplier, metric, startTick, ticker);
        List<Metric> theMetrics = Lists.newArrayListWithCapacity(METRICS_LIST_INITIAL_CAPACITY);
        theMetrics.add(metric);
        // safe publish of metrics to avoid synchronization
        this.metrics = theMetrics;
        metricNames.add(metricName);
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

    public long getEndTick() {
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
        ReadableErrorMessage message = rootSpan.getRootSpan().getErrorMessage();
        if (message != null) {
            return message.getText();
        }
        return error;
    }

    @Nullable
    public String getUser() {
        return user;
    }

    public ImmutableList<TraceAttribute> getAttributes() {
        if (attributes == null) {
            return ImmutableList.of();
        }
        List<TraceAttribute> theAttributes;
        synchronized (this.attributes) {
            theAttributes = ImmutableList.copyOf(this.attributes);
        }
        ImmutableList.Builder<TraceAttribute> orderedAttributes = ImmutableList.builder();
        // filter out duplicate attributes by name (first one wins)
        Set<String> attributeNames = Sets.newHashSet();
        for (TraceAttribute attribute : theAttributes) {
            if (attributeNames.add(attribute.getName())) {
                orderedAttributes.add(attribute);
            }
        }
        return orderedAttributes.build();
    }

    // this is called from a non-trace thread
    public ImmutableList<Metric> getMetrics() {
        // metrics is a non-thread safe list, but it is guarded by itself, so ok to make a copy
        // inside of synchronized block
        synchronized (metrics) {
            return ImmutableList.copyOf(metrics);
        }
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

    @ReadOnly
    public Iterable<Span> getSpans() {
        return rootSpan.getSpans();
    }

    public boolean isFine() {
        return fineMergedStackTree != null;
    }

    @Nullable
    public MergedStackTree getCoarseMergedStackTree() {
        return coarseMergedStackTree;
    }

    @Nullable
    public MergedStackTree getFineMergedStackTree() {
        return fineMergedStackTree;
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

    // returns previous value
    public boolean setStuck() {
        return stuck.getAndSet(true);
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

    public void setAttribute(String name, @Nullable String value) {
        if (attributes == null) {
            // no race condition here since only trace thread calls setAttribute()
            //
            // see performance comparison of synchronized ArrayList vs ConcurrentLinkedQueue in
            // ThreadSafeCollectionOfTenBenchmark
            attributes = Lists.newArrayListWithCapacity(ATTRIBUTES_LIST_INITIAL_CAPACITY);
        }
        synchronized (attributes) {
            attributes.add(new TraceAttribute(name, value));
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

    public Span pushSpan(MetricNameImpl metricName, long startTick,
            MessageSupplier messageSupplier) {
        Metric metric = metricName.get();
        if (metric == null) {
            metric = addMetric(metricName);
        }
        metric.start(startTick);
        return rootSpan.pushSpan(startTick, messageSupplier, metric);
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
        Metric metric = span.getMetric();
        if (metric != null) {
            metric.end(endTick);
        }
    }

    public Metric addMetric(MetricNameImpl metricName) {
        Metric metric = metricName.create();
        synchronized (metrics) {
            metrics.add(metric);
        }
        metricNames.add(metricName);
        return metric;
    }

    public void clearThreadLocalMetrics() {
        // reset metric thread locals to clear their state for next time
        for (MetricNameImpl metricName : metricNames) {
            metricName.clear();
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
            if (fineMergedStackTree == null) {
                // initialization possible race condition is ok, worst case scenario it misses
                // an almost simultaneously captured stack trace
                fineMergedStackTree = new MergedStackTree();
            }
            fineMergedStackTree.addStackTrace(threadInfo);
        } else {
            if (coarseMergedStackTree == null) {
                // initialization possible race condition is ok, worst case scenario it misses
                // an almost simultaneously captured stack trace
                coarseMergedStackTree = new MergedStackTree();
            }
            coarseMergedStackTree.addStackTrace(threadInfo);
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
                .add("metrics", metrics)
                .add("jvmInfo", jvmInfo)
                .add("rootSpan", rootSpan)
                .add("coarseMergedStackTree", coarseMergedStackTree)
                .add("fineMergedStackTree", fineMergedStackTree)
                .add("coarseProfilingScheduledRunnable", coarseProfilerScheduledRunnable)
                .add("fineProfilingScheduledRunnable", fineProfilerScheduledRunnable)
                .add("stuckScheduledRunnable", stuckScheduledRunnable)
                .toString();
    }

    @Immutable
    public static class TraceAttribute {
        private final String name;
        @Nullable
        private final String value;
        private TraceAttribute(String name, @Nullable String value) {
            this.name = name;
            this.value = value;
        }
        public String getName() {
            return name;
        }
        @Nullable
        public String getValue() {
            return value;
        }
    }
}
