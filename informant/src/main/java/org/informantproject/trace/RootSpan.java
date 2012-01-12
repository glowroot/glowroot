/**
 * Copyright 2011 the original author or authors.
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
package org.informantproject.trace;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.informantproject.api.SpanDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ticker;

import edu.emory.mathcs.backport.java.util.Deque;
import edu.emory.mathcs.backport.java.util.concurrent.LinkedBlockingDeque;

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

    private static final ThreadLocal<Deque> spanStackHolder = new ThreadLocal<Deque>() {
        @Override
        protected Deque initialValue() {
            return new LinkedBlockingDeque();
        }
    };

    private final long startTime;
    private volatile long endTime;

    private final Span rootSpan;
    private final Queue<Span> spans = new ConcurrentLinkedQueue<Span>();
    private volatile int size;

    private final Ticker ticker;

    RootSpan(SpanDetail spanDetail, Ticker ticker) {
        this.ticker = ticker;
        startTime = ticker.read();
        rootSpan = new Span(spanDetail, startTime, startTime, 0, -1, 0);
        pushSpanInternal(rootSpan);
    }

    public Span getRootSpan() {
        return rootSpan;
    }

    public Iterable<Span> getSpans() {
        return spans;
    }

    public int getSize() {
        return size;
    }

    public long getStartTime() {
        return startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    // duration of trace in nanoseconds
    public long getDuration() {
        return endTime == 0 ? 0 : endTime - startTime;
    }

    public boolean isCompleted() {
        return endTime != 0;
    }

    // typically pop() methods don't require the span to pop, but for safety,
    // the span to pop is passed in just to make sure it is the one on top
    // (and if it is not the one on top, then pop until it is found, preventing
    // any nasty bugs from a missed pop, e.g. a trace never being marked as complete)
    void popSpan(Span span, long spanEndTime) {
        span.setEndTime(spanEndTime);
        Deque spanStack = spanStackHolder.get();
        Span pop = (Span) spanStack.removeLast();
        if (!pop.equals(span)) {
            // somehow(?) a pop was missed
            // this is just damage control
            logger.error("found " + pop.getDescription()
                    + " at the top of the stack when expecting " + span.getDescription(),
                    new IllegalStateException());
            while (!spanStack.isEmpty() && !pop.equals(span)) {
                pop = (Span) spanStack.removeLast();
            }
            if (spanStack.isEmpty() && !pop.equals(span)) {
                logger.error("popped entire stack, never found " + span.getDescription());
            }
        }
        if (spanStack.isEmpty()) {
            endTime = spanEndTime;
        }
    }

    Span pushSpan(SpanDetail spanDetail) {
        Deque spanStack = spanStackHolder.get();
        Span currentSpan = (Span) spanStack.getLast();
        Span span = new Span(spanDetail, startTime, ticker.read(), size, currentSpan.getIndex(),
                currentSpan.getLevel() + 1);
        pushSpanInternal(span);
        return span;
    }

    private void pushSpanInternal(Span span) {
        spanStackHolder.get().add(span);
        spans.add(span);
        size++;
    }
}
