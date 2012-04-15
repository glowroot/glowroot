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

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.informantproject.api.SpanDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ticker;
import com.google.common.collect.Lists;

/**
 * The "span" terminology is borrowed from <a
 * href="http://research.google.com/pubs/pub36356.html">Dapper</a>.
 * 
 * This must support updating by a single thread and reading by multiple threads.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
// TODO make it work with spawned threads
public class RootSpan {

    private static final Logger logger = LoggerFactory.getLogger(RootSpan.class);

    // spanStack doesn't need to be thread safe since it is only access by the trace thread
    private final List<SpanImpl> spanStack = Lists.newArrayList();

    private final long startTick;
    private volatile long endTick;

    private final SpanImpl rootSpan;
    private final Queue<SpanImpl> spans = new ConcurrentLinkedQueue<SpanImpl>();
    private volatile int size;

    private final Ticker ticker;

    RootSpan(SpanDetail spanDetail, TraceMetricImpl traceMetric, long startTick, Ticker ticker) {
        this.startTick = startTick;
        this.ticker = ticker;
        rootSpan = new SpanImpl(spanDetail, startTick, startTick, 0, -1, 0, traceMetric);
        pushSpanInternal(rootSpan);
    }

    public SpanImpl getRootSpan() {
        return rootSpan;
    }

    public Iterable<SpanImpl> getSpans() {
        return spans;
    }

    public int getSize() {
        return size;
    }

    public long getStartTick() {
        return startTick;
    }

    public long getEndTick() {
        return endTick;
    }

    // duration of trace in nanoseconds
    public long getDuration() {
        return endTick == 0 ? ticker.read() - startTick : endTick - startTick;
    }

    public boolean isCompleted() {
        return endTick != 0;
    }

    SpanImpl pushSpan(long startTick, SpanDetail spanDetail, TraceMetricImpl traceMetric) {
        SpanImpl currentSpan = spanStack.get(spanStack.size() - 1);
        SpanImpl span = new SpanImpl(spanDetail, this.startTick, startTick, size,
                currentSpan.getIndex(), currentSpan.getLevel() + 1, traceMetric);
        pushSpanInternal(span);
        return span;
    }

    // typically pop() methods don't require the objects to pop, but for safety, the span is
    // passed in just to make sure it is the one on top (and if not, then pop until it is found,
    // preventing any nasty bugs from a missed pop, e.g. a span never being marked as complete)
    void popSpan(SpanImpl span, long endTick, StackTraceElement[] stackTraceElements) {
        span.setEndTick(endTick);
        span.setStackTraceElements(stackTraceElements);
        popSpanSafe(span);
        if (spanStack.isEmpty()) {
            this.endTick = endTick;
        }
    }

    private void pushSpanInternal(SpanImpl span) {
        spanStack.add(span);
        spans.add(span);
        size++;
    }

    private void popSpanSafe(SpanImpl span) {
        if (spanStack.isEmpty()) {
            logger.error("span stack is empty, cannot pop '{}'", span.getDescription());
            return;
        }
        SpanImpl pop = spanStack.remove(spanStack.size() - 1);
        if (!pop.equals(span)) {
            // somehow(?) a pop was missed (or maybe too many pops), this is just damage control
            logger.error("found '{}' at the top of the stack when expecting '{}'",
                    pop.getDescription(), span.getDescription());
            while (!spanStack.isEmpty() && !pop.equals(span)) {
                pop = spanStack.remove(spanStack.size() - 1);
            }
            if (spanStack.isEmpty() && !pop.equals(span)) {
                logger.error("popped entire stack, never found '{}'", span.getDescription());
            }
        }
    }
}
