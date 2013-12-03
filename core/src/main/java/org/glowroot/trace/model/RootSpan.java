/*
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
package org.glowroot.trace.model;

import java.util.List;
import java.util.Queue;

import checkers.nullness.quals.Nullable;
import com.google.common.base.Objects;
import com.google.common.base.Ticker;
import com.google.common.collect.Lists;
import com.google.common.collect.Queues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.ErrorMessage;
import org.glowroot.api.MessageSupplier;
import org.glowroot.markers.PartiallyThreadSafe;

/**
 * The "span" terminology is borrowed from <a
 * href="http://research.google.com/pubs/pub36356.html">Dapper</a>.
 * 
 * This must support updating by a single thread and reading by multiple threads.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@PartiallyThreadSafe("pushSpan(), addSpan(), popSpan() can only be called from constructing thread")
class RootSpan {

    private static final Logger logger = LoggerFactory.getLogger(RootSpan.class);

    // spanStack doesn't need to be thread safe since it is only access by the trace thread
    private final List<Span> spanStack = Lists.newArrayList();

    private final long startTick;
    private volatile long endTick;

    private final Span rootSpan;
    private final Queue<Span> spans = Queues.newConcurrentLinkedQueue();
    // tracking size of spans queue since ConcurrentLinkedQueue.size() is slow
    private volatile int size;

    // this doesn't need to be thread safe as it is only accessed by the trace thread
    private boolean spanLimitExceeded;

    private final Ticker ticker;

    RootSpan(MessageSupplier messageSupplier, Metric metric, long startTick, Ticker ticker) {
        this.startTick = startTick;
        this.ticker = ticker;
        rootSpan = new Span(messageSupplier, startTick, startTick, 0, metric);
        pushSpanInternal(rootSpan);
    }

    Span getRootSpan() {
        return rootSpan;
    }

    Iterable<Span> getSpans() {
        return spans;
    }

    int getSize() {
        return size;
    }

    long getStartTick() {
        return startTick;
    }

    long getEndTick() {
        return endTick;
    }

    // duration of trace in nanoseconds
    long getDuration() {
        return endTick == 0 ? ticker.read() - startTick : endTick - startTick;
    }

    boolean isCompleted() {
        return endTick != 0;
    }

    Span pushSpan(long startTick, MessageSupplier messageSupplier, Metric metric) {
        Span span = createSpan(startTick, messageSupplier, null, metric, false);
        pushSpanInternal(span);
        return span;
    }

    // typically pop() methods don't require the objects to pop, but for safety, the span is
    // passed in just to make sure it is the one on top (and if not, then pop until it is found,
    // preventing any nasty bugs from a missed pop, e.g. a span never being marked as complete)
    void popSpan(Span span, long endTick, @Nullable ErrorMessage errorMessage) {
        span.setErrorMessage(errorMessage);
        span.setEndTick(endTick);
        popSpanSafe(span);
        if (spanStack.isEmpty()) {
            this.endTick = endTick;
        }
    }

    Span addSpan(long startTick, long endTick, @Nullable MessageSupplier messageSupplier,
            @Nullable ErrorMessage errorMessage, boolean limitBypassed) {
        Span span = createSpan(startTick, messageSupplier, errorMessage, null, limitBypassed);
        spans.add(span);
        size++;
        span.setEndTick(endTick);
        return span;
    }

    void addSpanLimitExceededMarkerIfNeeded() {
        if (spanLimitExceeded) {
            return;
        }
        spanLimitExceeded = true;
        spans.add(Span.getLimitExceededMarker());
        size++;
    }

    private Span createSpan(long startTick, @Nullable MessageSupplier messageSupplier,
            @Nullable ErrorMessage errorMessage, @Nullable Metric metric, boolean limitBypassed) {
        if (!limitBypassed && spanLimitExceeded) {
            // just in case the spanLimit property is changed in the middle of a trace this resets
            // the flag so that it can be triggered again (and possibly then a second limit marker)
            spanLimitExceeded = false;
            // also a different marker ("limit extended") is placed in the spans so that the ui can
            // display this scenario sensibly
            spans.add(Span.getLimitExtendedMarker());
            size++;
        }
        Span currentSpan = spanStack.get(spanStack.size() - 1);
        // limit bypassed spans have no proper nesting, so put them directly under the root
        int nestingLevel = limitBypassed ? 1 : currentSpan.getNestingLevel() + 1;
        Span span = new Span(messageSupplier, this.startTick, startTick, nestingLevel, metric);
        span.setErrorMessage(errorMessage);
        return span;
    }

    private void pushSpanInternal(Span span) {
        spanStack.add(span);
        spans.add(span);
        size++;
    }

    private void popSpanSafe(Span span) {
        if (spanStack.isEmpty()) {
            logger.error("span stack is empty, cannot pop span: {}", span);
            return;
        }
        Span pop = spanStack.remove(spanStack.size() - 1);
        if (!pop.equals(span)) {
            // somehow(?) a pop was missed (or maybe too many pops), this is just damage control
            logger.error("found span {} at top of stack when expecting span {}", pop, span);
            while (!spanStack.isEmpty() && !pop.equals(span)) {
                pop = spanStack.remove(spanStack.size() - 1);
            }
            if (spanStack.isEmpty() && !pop.equals(span)) {
                logger.error("popped entire stack, never found span: {}", span);
            }
        }
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("spanStack", spanStack)
                .add("startTick", startTick)
                .add("endTick", endTick)
                .add("rootSpan", rootSpan)
                .add("spans", spans)
                .add("size", size)
                .toString();
    }
}
