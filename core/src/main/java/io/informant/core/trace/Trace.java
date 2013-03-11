/**
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.core.trace;

import io.informant.api.ErrorMessage;
import io.informant.api.MessageSupplier;
import io.informant.api.internal.ReadableMessage;
import io.informant.common.Clock;
import io.informant.marker.PartiallyThreadSafe;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import checkers.igj.quals.Immutable;
import checkers.igj.quals.ReadOnly;
import checkers.lock.quals.GuardedBy;
import checkers.nullness.quals.LazyNonNull;
import checkers.nullness.quals.Nullable;

import com.google.common.base.Objects;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * Contains all data that has been captured for a given trace (e.g. servlet request).
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
    private final long start;

    private final AtomicBoolean stuck = new AtomicBoolean();

    private volatile boolean background;

    // lazy loaded to reduce memory when attributes are not used
    @GuardedBy("attributes")
    @LazyNonNull
    private volatile List<TraceAttribute> attributes;

    @Nullable
    private volatile String userId;

    // see performance comparison of synchronized ArrayList vs ConcurrentLinkedQueue in
    // ThreadSafeCollectionOfTenBenchmark
    @GuardedBy("metrics")
    private final List<Metric> metrics;

    // the MetricNames are tracked since they contain thread locals that need to be cleared at the
    // end of the trace
    //
    // this doesn't need to be thread safe as it is only accessed by the trace thread
    private final List<MetricNameImpl> metricNames = Lists.newArrayList();

    // root span for this trace
    private final RootSpan rootSpan;

    // stack trace data constructed from coarse-grained profiling
    @LazyNonNull
    private volatile MergedStackTree coarseMergedStackTree;
    // stack trace data constructed from fine-grained profiling
    @LazyNonNull
    private volatile MergedStackTree fineMergedStackTree;

    // the thread is needed so that stack traces can be taken from a different thread
    // a weak reference is used just to be safe and make sure it can't accidentally prevent a thread
    // from being garbage collected
    private final WeakReference<Thread> threadHolder = new WeakReference<Thread>(
            Thread.currentThread());

    // these are stored in the trace so they are only scheduled a single time, and also so they can
    // be canceled at trace completion
    @Nullable
    private volatile ScheduledFuture<?> coarseProfilingScheduledFuture;
    @Nullable
    private volatile ScheduledFuture<?> fineProfilingScheduledFuture;
    @Nullable
    private volatile ScheduledFuture<?> stuckScheduledFuture;

    private final Ticker ticker;
    private final WeavingMetricNameImpl weavingMetricName;
    private final Metric weavingMetric;

    public Trace(MetricNameImpl metricName, MessageSupplier messageSupplier, Ticker ticker,
            Clock clock, WeavingMetricNameImpl weavingMetricName) {
        this.ticker = ticker;
        start = clock.currentTimeMillis();
        id = new TraceUniqueId(start);
        long startTick = ticker.read();
        Metric metric = metricName.create();
        metric.start(startTick);
        rootSpan = new RootSpan(messageSupplier, metric, startTick, ticker);
        List<Metric> metrics = Lists.newArrayListWithCapacity(METRICS_LIST_INITIAL_CAPACITY);
        metrics.add(metric);
        // safe publish of metrics to avoid synchronization
        this.metrics = metrics;
        metricNames.add(metricName);
        // the weaving metric thread local is initialized so that it can be cached in this class
        // (otherwise it is painful to synchronize properly between clearThreadLocalMetrics()
        // and getMetricSnapshots())
        weavingMetric = weavingMetricName.create();
        this.weavingMetricName = weavingMetricName;
    }

    public long getStart() {
        return start;
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

    @ReadOnly
    public List<TraceAttribute> getAttributes() {
        if (attributes == null) {
            return ImmutableList.of();
        } else {
            List<TraceAttribute> attributes;
            synchronized (this.attributes) {
                attributes = ImmutableList.copyOf(this.attributes);
            }
            // filter out duplicates (last one wins) and order so that each plugin's attributes
            // are together (and the plugins are ordered by the order they added their first
            // attribute to this trace)
            Map<String, Map<String, TraceAttribute>> attributeMap = Maps.newLinkedHashMap();
            for (TraceAttribute attribute : attributes) {
                Map<String, TraceAttribute> pluginAttributeMap =
                        attributeMap.get(attribute.getPluginId());
                if (pluginAttributeMap == null) {
                    pluginAttributeMap = Maps.newLinkedHashMap();
                    attributeMap.put(attribute.getPluginId(), pluginAttributeMap);
                }
                pluginAttributeMap.put(attribute.getName(), attribute);
            }
            List<TraceAttribute> orderedAttributes = Lists.newArrayList();
            for (Map<String, TraceAttribute> pluginAttributeMap : attributeMap.values()) {
                for (TraceAttribute attribute : pluginAttributeMap.values()) {
                    orderedAttributes.add(attribute);
                }
            }
            return orderedAttributes;
        }
    }

    @Nullable
    public String getUserId() {
        return userId;
    }

    public boolean isError() {
        return rootSpan.getRootSpan().getErrorMessage() != null;
    }

    public boolean isFine() {
        return fineMergedStackTree != null;
    }

    // this is called from a non-trace thread
    public List<Metric> getMetrics() {
        List<Metric> copyOfMetrics;
        // metrics is a non-thread safe list, but it is guarded by itself, so ok to make a copy
        // inside of synchronized block
        synchronized (metrics) {
            copyOfMetrics = Lists.newArrayList(metrics);
        }
        if (weavingMetric.getCount() > 0) {
            copyOfMetrics.add(weavingMetric);
        }
        return copyOfMetrics;
    }

    public Span getRootSpan() {
        return rootSpan.getRootSpan();
    }

    public String getHeadline() {
        MessageSupplier messageSupplier = rootSpan.getRootSpan().getMessageSupplier();
        if (messageSupplier == null) {
            // this should be impossible for root span
            logger.error("found root span with null message supplier in trace");
            return "";
        }
        return ((ReadableMessage) messageSupplier.get()).getText();
    }

    public int getSpanCount() {
        return rootSpan.getSize();
    }

    @ReadOnly
    public Iterable<Span> getSpans() {
        return rootSpan.getSpans();
    }

    @Nullable
    public MergedStackTree getCoarseMergedStackTree() {
        return coarseMergedStackTree;
    }

    @Nullable
    public MergedStackTree getFineMergedStackTree() {
        return fineMergedStackTree;
    }

    @Nullable
    public ScheduledFuture<?> getCoarseProfilingScheduledFuture() {
        return coarseProfilingScheduledFuture;
    }

    @Nullable
    public ScheduledFuture<?> getFineProfilingScheduledFuture() {
        return fineProfilingScheduledFuture;
    }

    @Nullable
    public ScheduledFuture<?> getStuckScheduledFuture() {
        return stuckScheduledFuture;
    }

    // returns previous value
    public boolean setStuck() {
        return stuck.getAndSet(true);
    }

    public void setBackground(boolean background) {
        this.background = background;
    }

    public void setUserId(@Nullable String userId) {
        this.userId = userId;
    }

    public void setAttribute(String pluginId, String name, @Nullable String value) {
        if (attributes == null) {
            // no race condition here since only trace thread calls setAttribute()
            //
            // see performance comparison of synchronized ArrayList vs ConcurrentLinkedQueue in
            // ThreadSafeCollectionOfTenBenchmark
            attributes = Lists.newArrayListWithCapacity(ATTRIBUTES_LIST_INITIAL_CAPACITY);
        }
        synchronized (attributes) {
            attributes.add(new TraceAttribute(pluginId, name, value));
        }
    }

    public void setCoarseProfilingScheduledFuture(ScheduledFuture<?> scheduledFuture) {
        if (coarseProfilingScheduledFuture != null) {
            logger.warn("setCoarseProfilingScheduledFuture(): overwriting non-null"
                    + " coarseProfilingScheduledFuture");
        }
        this.coarseProfilingScheduledFuture = scheduledFuture;
    }

    public void setFineProfilingScheduledFuture(ScheduledFuture<?> scheduledFuture) {
        if (fineProfilingScheduledFuture != null) {
            logger.warn("setFineProfilingScheduledFuture(): overwriting non-null"
                    + " fineProfilingScheduledFuture");
        }
        this.fineProfilingScheduledFuture = scheduledFuture;
    }

    public void setStuckScheduledFuture(ScheduledFuture<?> scheduledFuture) {
        if (stuckScheduledFuture != null) {
            logger.warn("setStuckScheduledFuture(): overwriting non-null stuckScheduledFuture");
        }
        this.stuckScheduledFuture = scheduledFuture;
    }

    public Span pushSpan(MetricNameImpl metricName, MessageSupplier messageSupplier,
            boolean spanLimitBypass) {
        long startTick = ticker.read();
        Metric metric = metricName.get();
        if (metric == null) {
            metric = addMetric(metricName);
        }
        metric.start(startTick);
        return rootSpan.pushSpan(startTick, messageSupplier, metric, spanLimitBypass);
    }

    public Span addSpan(@Nullable MessageSupplier messageSupplier,
            @Nullable ErrorMessage errorMessage, boolean spanLimitBypass) {
        return rootSpan.addSpan(ticker.read(), messageSupplier, errorMessage, spanLimitBypass);
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
        // reset weaving metric thread local to prevent the thread from continuing to
        // increment the one associated to this trace
        weavingMetricName.clear();
    }

    public void captureStackTrace(boolean fine) {
        Thread thread = threadHolder.get();
        if (thread != null) {
            ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
            ThreadInfo threadInfo = threadBean.getThreadInfo(thread.getId(), Integer.MAX_VALUE);
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
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("id", id)
                .add("startDate", start)
                .add("stuck", stuck)
                .add("background", background)
                .add("attributes", attributes)
                .add("userId", userId)
                .add("metrics", metrics)
                .add("rootSpan", rootSpan)
                .add("coarseMergedStackTree", coarseMergedStackTree)
                .add("fineMergedStackTree", fineMergedStackTree)
                .add("coarseProfilingScheduledFuture", coarseProfilingScheduledFuture)
                .add("fineProfilingScheduledFuture", fineProfilingScheduledFuture)
                .add("stuckScheduledFuture", stuckScheduledFuture)
                .toString();
    }

    @Immutable
    public static class TraceAttribute {
        private final String pluginId;
        private final String name;
        @Nullable
        private final String value;
        private TraceAttribute(String pluginId, String name, @Nullable String value) {
            this.pluginId = pluginId;
            this.name = name;
            this.value = value;
        }
        public String getPluginId() {
            return pluginId;
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
