/*
 * Copyright 2011-2014 the original author or authors.
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
package org.glowroot.trace;

import java.util.concurrent.ScheduledExecutorService;

import org.glowroot.common.ScheduledRunnable;
import org.glowroot.common.Ticker;
import org.glowroot.config.ConfigService;
import org.glowroot.config.OutlierProfilingConfig;
import org.glowroot.markers.Singleton;
import org.glowroot.trace.model.Trace;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Captures outlier profile for traces that exceed the configured threshold.
 * 
 * The main repeating Runnable (this) only runs every CHECK_INTERVAL_MILLIS at which time it checks
 * to see if there are any traces that may need stack traces scheduled before the main repeating
 * Runnable runs again (in another CHECK_INTERVAL_MILLIS). the main repeating Runnable schedules a
 * repeating CollectStackCommand for any trace that may need a stack trace in the next
 * CHECK_INTERVAL_MILLIS. since the majority of traces never end up needing stack traces this is
 * much more efficient than scheduling a repeating CollectStackCommand for every trace (this was
 * learned the hard way).
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class OutlierProfilerWatcher extends ScheduledRunnable {

    static final int PERIOD_MILLIS = 50;

    private final ScheduledExecutorService scheduledExecutor;
    private final TraceRegistry traceRegistry;
    private final ConfigService configService;
    private final Ticker ticker;

    OutlierProfilerWatcher(ScheduledExecutorService scheduledExecutor,
            TraceRegistry traceRegistry, ConfigService configService, Ticker ticker) {
        this.scheduledExecutor = scheduledExecutor;
        this.traceRegistry = traceRegistry;
        this.configService = configService;
        this.ticker = ticker;
    }

    // look for traces that will exceed the stack trace initial delay threshold within the next
    // polling interval and schedule stack trace capture to occur at the appropriate time(s)
    @Override
    protected void runInternal() {
        // order configs by trace percentage so that lowest percentage configs have first shot
        long currentTick = ticker.read();
        OutlierProfilingConfig config = configService.getOutlierProfilingConfig();
        if (!config.isEnabled()) {
            return;
        }
        long stackTraceThresholdTime = currentTick
                - MILLISECONDS.toNanos(config.getInitialDelayMillis() - PERIOD_MILLIS);
        for (Trace trace : traceRegistry.getTraces()) {
            // if the trace will exceed the stack trace initial delay threshold before the next
            // scheduled execution of this repeating Runnable (in other words, it is within
            // PERIOD_MILLIS from exceeding the threshold) and the stack trace capture
            // hasn't already been scheduled then schedule it
            if (Ticker.lessThanOrEqual(trace.getStartTick(), stackTraceThresholdTime)
                    && trace.getOutlierProfilerScheduledRunnable() == null) {
                scheduleProfiling(trace, currentTick, config);
            }
        }
    }

    // schedule stack traces to be taken every X seconds
    private void scheduleProfiling(Trace trace, long currentTick, OutlierProfilingConfig config) {
        long endTick = getEndTickForCommand(trace.getStartTick(), config);
        ScheduledRunnable profilerScheduledRunnable =
                new ProfilerScheduledRunnable(trace, endTick, true, ticker);
        long initialDelayRemainingMillis = getInitialDelayForCommand(trace.getStartTick(),
                currentTick, config);
        profilerScheduledRunnable.scheduleWithFixedDelay(scheduledExecutor,
                initialDelayRemainingMillis, config.getIntervalMillis(), MILLISECONDS);
        trace.setOutlierProfilerScheduledRunnable(profilerScheduledRunnable);
    }

    private static long getEndTickForCommand(long startTick, OutlierProfilingConfig config) {
        // need to take max in case total is smaller than interval
        long durationMillis = Math.max(SECONDS.toMillis(config.getMaxSeconds())
                - config.getIntervalMillis() / 2, config.getIntervalMillis() / 2);
        // extra half interval is to make sure the final stack trace is grabbed if it aligns on
        // total (e.g. 1s initial delay, 1s interval, 10 second total should result in exactly 10
        // stack traces)
        return startTick + MILLISECONDS.toNanos(config.getInitialDelayMillis())
                + MILLISECONDS.toNanos(durationMillis);
    }

    private static long getInitialDelayForCommand(long startTick, long currentTick,
            OutlierProfilingConfig config) {
        long traceDurationMillis = NANOSECONDS.toMillis(currentTick - startTick);
        return Math.max(0, config.getInitialDelayMillis() - traceDurationMillis);
    }
}
