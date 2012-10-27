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
package io.informant.core.trace;

import io.informant.api.Logger;
import io.informant.api.LoggerFactory;

import java.util.concurrent.ScheduledExecutorService;

import javax.annotation.concurrent.ThreadSafe;

import com.google.common.base.Objects;
import com.google.common.base.Ticker;

/**
 * Captures a stack trace for the thread executing a trace and stores the stack trace in the
 * {@link Trace}'s {@link MergedStackTree}.
 * 
 * Designed to be scheduled and run in a separate thread as soon as the trace exceeds a given
 * threshold, and then again at specified intervals after that (e.g. via
 * {@link ScheduledExecutorService#scheduleAtFixedRate}).
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
class CollectStackCommand implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(CollectStackCommand.class);

    private final Trace trace;
    private final long endTick;
    private final boolean fine;
    private final Ticker ticker;

    CollectStackCommand(Trace trace, long endTick, boolean fine, Ticker ticker) {
        this.trace = trace;
        this.endTick = endTick;
        this.fine = fine;
        this.ticker = ticker;
    }

    public void run() {
        if (ticker.read() >= endTick) {
            // just throwing to terminate subsequent scheduled executions
            throw new TerminateScheduledActionException();
        }
        if (trace.isCompleted()) {
            // there is a small window between trace completion and cancellation of this command,
            // plus, should a stop-the-world gc occur in this small window, even two command
            // executions can fire one right after the other in the small window (assuming the first
            // didn't throw an exception which it does now), since this command is scheduled using
            // ScheduledExecutorService.scheduleAtFixedRate()
            throw new TerminateScheduledActionException();
        }
        try {
            trace.captureStackTrace(fine);
        } catch (Error e) {
            // log and re-throw serious error which will terminate subsequent scheduled executions
            // (see ScheduledExecutorService.scheduleAtFixedRate())
            logger.error(e.getMessage(), e);
            throw e;
        } catch (Throwable t) {
            // log and terminate successfully
            logger.error(t.getMessage(), t);
        }
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("trace", trace)
                .add("endTick", endTick)
                .add("fine", fine)
                .toString();
    }
}
