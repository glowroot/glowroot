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
package io.informant.trace;

import com.google.common.base.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.markers.ThreadSafe;
import io.informant.trace.model.Trace;

/**
 * Scheduled to run as soon as the trace exceeds a given threshold.
 * 
 * If the {@link Trace} has already completed when this is run then it does nothing.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
class CollectStuckTraceCommand implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(CollectStuckTraceCommand.class);

    private final Trace trace;
    private final TraceCollector traceCollector;
    private volatile boolean tracePreviouslyCompleted;

    CollectStuckTraceCommand(Trace trace, TraceCollector traceCollector) {
        this.trace = trace;
        this.traceCollector = traceCollector;
    }

    public void run() {
        logger.debug("run(): trace.id={}", trace.getId());
        if (trace.isCompleted()) {
            if (tracePreviouslyCompleted) {
                logger.warn("trace already completed: {}", trace);
                throw new IllegalStateException("Trace already completed, just throwing to"
                        + " terminate subsequent scheduled executions");
            } else {
                // there is a small window between trace completion and cancellation of this command
                // so give it one extra chance to be completed normally
                tracePreviouslyCompleted = true;
                return;
            }
        }
        if (trace.setStuck()) {
            // already marked as stuck
            return;
        }
        traceCollector.onStuckTrace(trace);
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("trace", trace)
                .add("tracePreviouslyCompleted", tracePreviouslyCompleted)
                .toString();
    }
}
