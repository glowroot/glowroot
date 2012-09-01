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

import java.util.concurrent.ScheduledExecutorService;

import javax.annotation.concurrent.ThreadSafe;

import org.informantproject.core.stack.MergedStackTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Captures a stack trace for the thread executing a trace and stores the stack trace in the
 * {@link Trace}'s {@link MergedStackTree}.
 * 
 * Designed to be scheduled and run in a separate thread as soon as the trace exceeds a given
 * threshold, and then again at specified intervals after that (e.g. via
 * {@link ScheduledExecutorService#scheduleWithFixedDelay}).
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
class CollectStackCommand implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(CollectStackCommand.class);

    private final Trace trace;
    private volatile boolean tracePreviouslyCompleted;

    CollectStackCommand(Trace trace) {
        this.trace = trace;
    }

    public void run() {
        if (trace.isCompleted()) {
            if (tracePreviouslyCompleted) {
                logger.warn("trace '{}' already completed", trace.getRootSpan()
                        .getMessageSupplier().get().getText());
                throw new IllegalStateException("Trace already completed, just throwing to"
                        + " terminate subsequent scheduled executions");
            } else {
                // there is a small window between trace completion and cancellation of this command
                // so give it one extra chance to be completed normally
                tracePreviouslyCompleted = true;
                return;
            }
        }
        try {
            trace.captureStackTrace();
        } catch (Exception e) {
            // log and terminate successfully
            logger.error(e.getMessage(), e);
        } catch (Error e) {
            // log and re-throw serious error which will terminate subsequent scheduled executions
            // (see ScheduledExecutorService.scheduleWithFixedDelay())
            logger.error(e.getMessage(), e);
            throw e;
        }
    }
}
