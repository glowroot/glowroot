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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Designed to be scheduled and run as soon as the trace exceeds a given threshold.
 * 
 * If the {@link Trace} has already completed when this is run then it does nothing.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
class CollectStuckTraceCommand implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(CollectStuckTraceCommand.class);

    // since it's possible for this scheduled command to live for a while
    // after the trace has completed, a weak reference is used to make sure
    // it won't prevent the (larger) trace structure from being garbage collected
    private final WeakReference<Trace> traceHolder;

    private final TraceSink traceSink;

    CollectStuckTraceCommand(Trace trace, TraceSink traceSink) {
        this.traceHolder = new WeakReference<Trace>(trace);
        this.traceSink = traceSink;
    }

    public void run() {
        Trace trace = traceHolder.get();
        if (trace == null || trace.isCompleted()) {
            // already completed
            return;
        }
        logger.debug("run(): trace.id={}", trace.getId());
        if (trace.setStuck()) {
            // already marked as stuck
            return;
        }
        traceSink.onStuckTrace(trace);
    }
}
