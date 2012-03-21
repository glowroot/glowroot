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

import java.lang.ref.WeakReference;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.informantproject.api.Optional;
import org.informantproject.api.RootSpanDetail;
import org.informantproject.api.SpanDetail;
import org.informantproject.core.stack.MergedStackTree;
import org.informantproject.core.util.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.base.Ticker;

/**
 * Contains all data that has been captured for a given trace (e.g. servlet request).
 * 
 * This class needs to be thread safe, only one thread updates it, but multiple threads can read it
 * at the same time as it is being updated.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class Trace {

    private static final Logger logger = LoggerFactory.getLogger(Trace.class);

    // a unique identifier
    private final TraceUniqueId id;

    // timing data is tracked in nano seconds which cannot be converted into dates
    // (see javadoc for System.nanoTime())
    // so the start time is also tracked in a date object here
    private final Date startDate;

    private final AtomicBoolean stuck = new AtomicBoolean();

    // stores the thread name(s) at trace start and at each stack trace capture
    private final List<String> threadNames = new CopyOnWriteArrayList<String>();

    // stores timing info so that summary metric data can be reported for a given trace
    private final MetricData metricData = new MetricData();

    // root span for this trace
    private final RootSpan rootSpan;

    // used to prevent recording overlapping metric timings for the same span
    private final LinkedList<String> spanNameStack = new LinkedList<String>();

    // stack trace data constructed from sampled stack traces
    // this is lazy instantiated since most traces won't exceed the threshold for stack sampling
    // and early initialization would use up memory unnecessarily
    private final Supplier<MergedStackTree> mergedStackTreeSupplier = Suppliers
            .memoize(new Supplier<MergedStackTree>() {
                public MergedStackTree get() {
                    return new MergedStackTree();
                }
            });

    // the thread is needed so that stack traces can be taken from a different thread
    // a weak reference is used just to be safe and make sure it can't accidentally prevent a thread
    // from being garbage collected
    private final WeakReference<Thread> threadHolder = new WeakReference<Thread>(
            Thread.currentThread());

    // these are stored in the trace so that they can be cancelled
    private volatile ScheduledFuture<?> captureStackTraceScheduledFuture;
    private volatile ScheduledFuture<?> stuckCommandScheduledFuture;

    Trace(String spanName, SpanDetail spanDetail, Clock clock, Ticker ticker) {
        long startTimeMillis = clock.currentTimeMillis();
        id = new TraceUniqueId(startTimeMillis);
        startDate = new Date(startTimeMillis);
        rootSpan = new RootSpan(spanDetail, ticker);
        spanNameStack.add(spanName);
        addThreadName(Thread.currentThread());
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

    public boolean isCompleted() {
        return rootSpan.isCompleted();
    }

    public boolean isStuck() {
        return stuck.get();
    }

    public Optional<String> getUsername() {
        return ((RootSpanDetail) rootSpan.getRootSpan().getSpanDetail()).getUsername();
    }

    public RootSpan getRootSpan() {
        return rootSpan;
    }

    public MergedStackTree getMergedStackTree() {
        return mergedStackTreeSupplier.get();
    }

    public MetricData getMetricData() {
        return metricData;
    }

    public List<String> getThreadNames() {
        return threadNames;
    }

    public ScheduledFuture<?> getCaptureStackTraceScheduledFuture() {
        return captureStackTraceScheduledFuture;
    }

    public ScheduledFuture<?> getStuckCommandScheduledFuture() {
        return stuckCommandScheduledFuture;
    }

    // returns previous value
    boolean setStuck() {
        return stuck.getAndSet(true);
    }

    // this method doesn't need to be synchronized
    void setCaptureStackTraceScheduledFuture(ScheduledFuture<?> stackTraceScheduledFuture) {
        this.captureStackTraceScheduledFuture = stackTraceScheduledFuture;
    }

    // this method doesn't need to be synchronized
    void setStuckCommandScheduledFuture(ScheduledFuture<?> stuckCommandScheduledFuture) {
        this.stuckCommandScheduledFuture = stuckCommandScheduledFuture;
    }

    void captureStackTrace() {
        Thread thread = threadHolder.get();
        if (thread != null) {
            // TODO gather thread names at different point? maybe during contextual trace?
            addThreadName(thread);
            mergedStackTreeSupplier.get().captureStackTrace(thread);
        }
    }

    Span pushSpan(String spanName, SpanDetail spanDetail) {
        spanNameStack.add(spanName);
        return rootSpan.pushSpan(spanDetail);
    }

    // this case (without detail) is optimized to only take ticker readings when necessary, in other
    // words, when span name is the top-most such span name on the span name stack (whereas the case
    // above - with detail - already takes ticker readings as part of span creation so no need for
    // such optimization since the span duration can just be pulled from the span)
    boolean pushSpanWithoutDetail(String spanName) {
        boolean alreadyPresent = spanNameStack.contains(spanName);
        if (!alreadyPresent) {
            spanNameStack.add(spanName);
        }
        return alreadyPresent;
    }

    // typically pop() methods don't require the objects to pop, but for safety, the spanName and
    // span to pop is passed in just to make sure they are the ones on top (and if not, then pop
    // until they are found, preventing any nasty bugs from a missed pop, e.g. a trace never being
    // marked as complete)
    void popSpan(String spanName, Span span, long endTick, StackTraceElement[] stackTraceElements) {
        popSpanNameSafe(spanName);
        metricData.recordData(spanName, endTick - span.getStartTick());
        rootSpan.popSpan(span, endTick, stackTraceElements);
    }

    // typically pop() methods don't require the objects to pop, but for safety, the spanName is
    // passed in just to make sure it is the one on top (and if not, then pop until it is found,
    // preventing any nasty bugs from a missed pop, e.g. a span never being marked as complete)
    void popSpanWithoutDetail(String spanName, long duration) {
        popSpanNameSafe(spanName);
        metricData.recordData(spanName, duration);
    }

    private void popSpanNameSafe(String spanName) {
        String poppedSpanName = spanNameStack.removeLast();
        if (!poppedSpanName.equals(spanName)) {
            // somehow(?) a pop was missed, this is just damage control
            logger.error("found '{}' at the top of the span name stack when expecting '{}'",
                    poppedSpanName, spanName);
            while (!spanNameStack.isEmpty() && !poppedSpanName.equals(spanName)) {
                poppedSpanName = spanNameStack.removeLast();
            }
            if (spanNameStack.isEmpty() && !poppedSpanName.equals(spanName)) {
                logger.error("popped entire span name stack, never found '{}'", spanName);
            }
        }
    }

    private void addThreadName(Thread thread) {
        String threadName = thread.getName();
        if (!threadNames.contains(threadName)) {
            // intentionally calling contains first for performance even though add()
            // performs a similar check, this is because of the specific implementation of
            // CopyOnWriteArraySet.add() which performs checking for a duplicate
            // at the same time as copying since (as the implementors wrote)
            // "This wins in the most common case where [the value] is not present".
            // however for us, typically the thread name will already be present (since it
            // typically doesn't change), so this would be inefficient in our case
            threadNames.add(threadName);
        }
    }
}
