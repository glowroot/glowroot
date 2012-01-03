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
package org.informantproject.trace;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;

import org.informantproject.api.SpanDetail;
import org.informantproject.util.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ticker;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Primary service for working with traces. All trace state is encapsulated inside
 * {@link TraceRegistry}.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public final class TraceService {

    private static final Logger logger = LoggerFactory.getLogger(TraceService.class);

    private final TraceRegistry traceRegistry;
    private final TraceSink traceSink;
    private final Clock clock;
    private final Ticker ticker;

    @Inject
    public TraceService(TraceRegistry traceRegistry, TraceSink traceSink, Clock clock,
            Ticker ticker) {

        this.traceRegistry = traceRegistry;
        this.traceSink = traceSink;
        this.clock = clock;
        this.ticker = ticker;
    }

    // it is very important that calls to pushSpan() are wrapped in try block with
    // a finally block executing popSpan()
    public Span pushSpan(SpanDetail spanDetail) {
        // span limit is handled inside PluginServicesImpl
        Trace currentTrace = getCurrentTrace();
        if (currentTrace == null) {
            currentTrace = new Trace(spanDetail, clock, ticker);
            traceRegistry.setCurrentTrace(currentTrace);
            traceRegistry.addTrace(currentTrace);
            return currentTrace.getRootSpan().getRootSpan();
        } else {
            return currentTrace.getRootSpan().pushSpan(spanDetail);
        }
    }

    // typically pop() methods don't require the span to pop, but for safety,
    // the span to pop is passed in just to make sure it is the one on top
    // (and if it is not the one on top, then pop until it is found, preventing
    // any nasty bugs from a missed pop, e.g. a trace never being marked as complete)
    public void popSpan(Span span, long elementEndTime) {
        Trace currentTrace = getCurrentTrace();
        currentTrace.getRootSpan().popSpan(span, elementEndTime);
        if (currentTrace.isCompleted()) {
            // the root span has been popped off
            cancelScheduledFuture(currentTrace.getCaptureStackTraceScheduledFuture());
            cancelScheduledFuture(currentTrace.getStuckCommandScheduledFuture());
            traceRegistry.setCurrentTrace(null);
            traceRegistry.removeTrace(currentTrace);
            traceSink.onCompletedTrace(currentTrace);
        }
    }

    public void recordSummaryData(String spanSummaryKey, long duration) {
        Trace currentTrace = getCurrentTrace();
        // aggregate info is only tracked within an active trace
        if (currentTrace != null) {
            currentTrace.recordSpanDuration(spanSummaryKey, duration);
        }
    }

    public Trace getCurrentTrace() {
        return traceRegistry.getCurrentTrace();
    }

    public boolean isCurrentRootSpanDisabled() {
        return traceRegistry.isCurrentRootSpanDisabled();
    }

    public void setCurrentRootSpanDisabled(boolean disabled) {
        traceRegistry.setCurrentRootSpanDisabled(disabled);
    }

    public Collection<Trace> getTracesExceptCurrent() {
        Trace currentTrace = getCurrentTrace();
        if (currentTrace == null) {
            return getTraces();
        } else {
            List<Trace> tracesExceptCurrent = new ArrayList<Trace>(getTraces());
            tracesExceptCurrent.remove(currentTrace);
            return tracesExceptCurrent;
        }
    }

    // returns list of traces ordered by start time
    public Collection<Trace> getTraces() {
        return traceRegistry.getTraces();
    }

    private static void cancelScheduledFuture(ScheduledFuture<?> scheduledFuture) {
        if (scheduledFuture == null) {
            return;
        }
        boolean success = scheduledFuture.cancel(false);
        if (!success) {
            // execution failed due to an error (probably programming error)
            try {
                scheduledFuture.get();
            } catch (InterruptedException e) {
                logger.error(e.getMessage(), e);
            } catch (ExecutionException e) {
                logger.error(e.getMessage(), e);
            }
        }
    }
}
