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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.informantproject.api.Logger;
import org.informantproject.api.LoggerFactory;
import org.informantproject.core.config.CoarseProfilingConfig;
import org.informantproject.core.config.ConfigService;
import org.informantproject.core.util.DaemonExecutors;

import com.google.common.base.Ticker;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Owns the thread (via a single threaded scheduled executor) that captures coarse-grained thread
 * dumps for traces that exceed the configured threshold.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class CoarseGrainedProfiler implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(CoarseGrainedProfiler.class);
    private static final int CHECK_INTERVAL_MILLIS = 100;

    private final ScheduledExecutorService scheduledExecutor = DaemonExecutors
            .newSingleThreadScheduledExecutor("Informant-CoarseGrainedProfiler");

    private final TraceRegistry traceRegistry;
    private final ConfigService configService;
    private final Ticker ticker;

    @Inject
    CoarseGrainedProfiler(TraceRegistry traceRegistry, ConfigService configService, Ticker ticker) {

        this.traceRegistry = traceRegistry;
        this.configService = configService;
        this.ticker = ticker;
        // the main repeating Runnable (this) only runs every CHECK_INTERVAL_MILLIS at which time it
        // checks to see if there are any traces that may need stack traces scheduled before the
        // main repeating Runnable runs again (in another CHECK_INTERVAL_MILLIS).
        // the main repeating Runnable schedules a repeating CollectStackCommand for any trace that
        // may need a stack trace in the next CHECK_INTERVAL_MILLIS.
        // since the majority of traces never end up needing stack traces this is much more
        // efficient than scheduling a repeating CollectStackCommand for every trace (this was
        // learned the hard way).
        scheduledExecutor.scheduleAtFixedRate(this, 0, CHECK_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS);
    }

    public void run() {
        try {
            runInternal();
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

    public void close() {
        logger.debug("close()");
        scheduledExecutor.shutdownNow();
    }

    // look for traces that will exceed the stack trace initial delay threshold within the next
    // polling interval and schedule stack trace capture to occur at the appropriate time(s)
    private void runInternal() {
        // order configs by trace percentage so that lowest percentage configs have first shot
        long currentTick = ticker.read();
        CoarseProfilingConfig config = configService.getCoarseProfilingConfig();
        if (!config.isEnabled()) {
            return;
        }
        // TODO implement totalMillis
        long stackTraceThresholdTime = currentTick - TimeUnit.MILLISECONDS.toNanos(
                config.getInitialDelayMillis() - CHECK_INTERVAL_MILLIS);
        for (Trace trace : traceRegistry.getTraces()) {
            // if the trace will exceed the stack trace initial delay threshold before the next
            // scheduled execution of this repeating Runnable (in other words, it is within
            // COMMAND_INTERVAL_MILLIS from exceeding the threshold) and the stack trace capture
            // hasn't already been scheduled then schedule it
            if (!Nanoseconds.lessThan(trace.getStartTick(), stackTraceThresholdTime)) {
                // since the list of traces are ordered by start time, if this trace
                // didn't meet the threshold then no subsequent trace will meet the threshold
                break;
            }
            if (trace.getCoarseProfilingScheduledFuture() == null) {
                scheduleProfiling(currentTick, config, trace);
            }
        }
    }

    // schedule stack traces to be taken every X seconds
    private void scheduleProfiling(long currentTick, CoarseProfilingConfig config, Trace trace) {
        long traceDurationMillis = TimeUnit.NANOSECONDS
                .toMillis(currentTick - trace.getStartTick());
        long initialDelayRemainingMillis = Math.max(0, config.getInitialDelayMillis()
                - traceDurationMillis);
        // extra half interval at the end to make sure the final stack trace is grabbed if it aligns
        // on total (e.g. 100ms interval, 1 second total should result in exactly 10 stack traces)
        long endTick = trace.getStartTick() + TimeUnit.SECONDS.toNanos(config.getTotalSeconds())
                + TimeUnit.MILLISECONDS.toNanos(config.getIntervalMillis()) / 2;
        CollectStackCommand command = new CollectStackCommand(trace, endTick, false, ticker);
        ScheduledFuture<?> scheduledFuture = scheduledExecutor.scheduleAtFixedRate(command,
                initialDelayRemainingMillis, config.getIntervalMillis(), TimeUnit.MILLISECONDS);
        trace.setCoarseProfilingScheduledFuture(scheduledFuture);
    }
}
