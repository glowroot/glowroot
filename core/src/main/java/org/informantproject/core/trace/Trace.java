/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.core.trace;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import org.informantproject.api.ErrorMessage;
import org.informantproject.api.Logger;
import org.informantproject.api.LoggerFactory;
import org.informantproject.core.stack.MergedStackTree;
import org.informantproject.core.util.Clock;
import org.informantproject.core.util.PartiallyThreadSafe;

import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableMap;
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
@PartiallyThreadSafe("pushSpan(), addSpan(), popSpan(), startTraceMetric(),"
        + "  clearThreadLocalMetrics() can only be called from constructing thread")
public class Trace {

    private static final Logger logger = LoggerFactory.getLogger(Trace.class);

    // a unique identifier
    private final TraceUniqueId id;

    // timing data is tracked in nano seconds which cannot be converted into dates
    // (see javadoc for System.nanoTime())
    // so the start time is also tracked in a date object here
    private final Date startDate;

    private final AtomicBoolean stuck = new AtomicBoolean();

    private volatile boolean background;

    // attribute name ordering is maintained for consistent display (assumption is order of entry is
    // order of importance)
    //
    // lazy loaded to reduce memory when attributes are not used
    @GuardedBy("attributes")
    private volatile Map<String, String> attributes;

    @Nullable
    private volatile String userId;

    // this is mostly updated and rarely read, so it seems like synchronized ArrayList is the best
    // collection
    private final List<TraceMetric> traceMetrics = Collections
            .synchronizedList(new ArrayList<TraceMetric>());

    // this doesn't need to be thread safe as it is only accessed by the trace thread
    private final List<MetricImpl> metrics = Lists.newArrayList();

    // root span for this trace
    private final RootSpan rootSpan;

    // stack trace data constructed from coarse-grained profiling
    private volatile MergedStackTree coarseMergedStackTree;
    // stack trace data constructed from fine-grained profiling
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
    private final WeavingMetricImpl weavingMetric;
    private final TraceMetric weavingTraceMetric;

    public Trace(MetricImpl metric, org.informantproject.api.MessageSupplier messageSupplier,
            Clock clock, Ticker ticker, WeavingMetricImpl weavingMetric) {

        this.ticker = ticker;
        long startTimeMillis = clock.currentTimeMillis();
        id = new TraceUniqueId(startTimeMillis);
        startDate = new Date(startTimeMillis);
        long startTick = ticker.read();
        TraceMetric traceMetric = metric.start(startTick);
        rootSpan = new RootSpan(messageSupplier, traceMetric, startTick, ticker);
        traceMetrics.add(traceMetric);
        metrics.add(metric);
        traceMetric.firstStartSeen();
        // the weaving metric thread local is initialized to an empty TraceMetric instance so that
        // it can be cached in this class (otherwise it is painful to synchronize properly between
        // clearThreadLocalMetrics() and getTraceMetrics())
        weavingTraceMetric = weavingMetric.initThreadLocal();
        this.weavingMetric = weavingMetric;
    }

    public Date getStartDate() {
        return startDate;
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

    public Map<String, String> getAttributes() {
        if (attributes == null) {
            return ImmutableMap.of();
        } else {
            synchronized (attributes) {
                return Maps.newLinkedHashMap(attributes);
            }
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

    public List<TraceMetric> getTraceMetrics() {
        if (weavingTraceMetric.getCount() == 0) {
            return traceMetrics;
        } else {
            List<TraceMetric> values = Lists.newArrayList(traceMetrics);
            values.add(weavingTraceMetric);
            return values;
        }
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

    @Nullable
    public MergedStackTree getCoarseMergedStackTree() {
        return coarseMergedStackTree;
    }

    @Nullable
    public MergedStackTree getFineMergedStackTree() {
        return fineMergedStackTree;
    }

    public ScheduledFuture<?> getCoarseProfilingScheduledFuture() {
        return coarseProfilingScheduledFuture;
    }

    public ScheduledFuture<?> getFineProfilingScheduledFuture() {
        return fineProfilingScheduledFuture;
    }

    public ScheduledFuture<?> getStuckScheduledFuture() {
        return stuckScheduledFuture;
    }

    // must be called by the trace thread
    public void clearThreadLocalMetrics() {
        // reset metric thread locals to clear their state for next time
        for (MetricImpl metric : metrics) {
            metric.clearThreadLocal();
        }
        // reset weaving metric thread local to prevent the thread from continuing to
        // increment the one associated to this trace
        weavingMetric.clearThreadLocal();
    }

    // returns previous value
    boolean setStuck() {
        return stuck.getAndSet(true);
    }

    public void setBackground(boolean background) {
        this.background = background;
    }

    public void setUserId(@Nullable String userId) {
        this.userId = userId;
    }

    public void setAttribute(String name, @Nullable String value) {
        if (attributes == null) {
            // no race condition here since only trace thread calls setAttribute()
            attributes = Maps.newLinkedHashMap();
        }
        // synchronization is only for visibility guarantee
        synchronized (attributes) {
            attributes.put(name, value);
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

    void captureStackTrace(boolean fine) {
        Thread thread = threadHolder.get();
        if (thread != null) {
            ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
            ThreadInfo threadInfo = threadBean.getThreadInfo(thread.getId(), Integer.MAX_VALUE);
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

    public Span pushSpan(MetricImpl metric,
            org.informantproject.api.MessageSupplier messageSupplier) {

        long startTick = ticker.read();
        TraceMetric traceMetric = metric.start(startTick);
        Span span = rootSpan.pushSpan(startTick, messageSupplier, traceMetric);
        if (traceMetric.isFirstStart()) {
            traceMetrics.add(metric.get());
            traceMetric.firstStartSeen();
            metrics.add(metric);
        }
        return span;
    }

    // one but not both args can be null
    public Span addSpan(@Nullable org.informantproject.api.MessageSupplier messageSupplier,
            @Nullable ErrorMessage errorMessage) {

        if (messageSupplier == null && errorMessage == null) {
            logger.error("addSpan(): both args cannot be null");
        }
        return rootSpan.addSpan(ticker.read(), messageSupplier, errorMessage);
    }

    // typically pop() methods don't require the objects to pop, but for safety, the span to pop is
    // passed in just to make sure it is the one on top (and if not, then pop until is is found,
    // preventing any nasty bugs from a missed pop, e.g. a trace never being marked as complete)
    public void popSpan(Span span, long endTick, ErrorMessage errorMessage) {
        rootSpan.popSpan(span, endTick, errorMessage);
        TraceMetric traceMetric = span.getTraceMetric();
        if (traceMetric != null) {
            traceMetric.end(endTick);
        }
    }

    public TraceMetric startTraceMetric(MetricImpl metric) {
        TraceMetric traceMetric = metric.start();
        if (traceMetric.isFirstStart()) {
            traceMetrics.add(metric.get());
            traceMetric.firstStartSeen();
            metrics.add(metric);
        }
        return traceMetric;
    }
}
