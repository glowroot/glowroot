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

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.informantproject.configuration.ConfigurationService;
import org.informantproject.configuration.ImmutableCoreConfiguration;
import org.informantproject.util.DaemonExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ticker;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Owns the thread (via a single threaded scheduled executor) that captures thread dumps for traces
 * that exceed the configuration threshold.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class StackCollector implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(StackCollector.class);
    private static final int CHECK_INTERVAL_MILLIS = 100;

    private final ScheduledExecutorService scheduledExecutor = DaemonExecutors
            .newSingleThreadScheduledExecutor("Informant-StackCollector");

    private final TraceRegistry traceRegistry;
    private final ConfigurationService configurationService;
    private final Ticker ticker;

    @Inject
    public StackCollector(TraceRegistry traceRegistry, ConfigurationService configurationService,
            Ticker ticker) {

        this.traceRegistry = traceRegistry;
        this.configurationService = configurationService;
        this.ticker = ticker;
        // the main repeating Runnable (this) only runs every CHECK_INTERVAL_MILLIS at which time it
        // checks to see if there are any traces that may need stack traces scheduled before the
        // main repeating Runnable runs again (in another CHECK_INTERVAL_MILLIS).
        // the main repeating Runnable schedules a repeating CollectStackCommand for any trace that
        // may need a stack trace in the next CHECK_INTERVAL_MILLIS.
        // since the majority of traces never end up needing stack traces this is much more
        // efficient than scheduling a repeating CollectStackCommand for every trace (this was
        // learned the hard way).
        scheduledExecutor.scheduleWithFixedDelay(this, 0, CHECK_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS);
    }

    public void run() {
        try {
            runInternal();
        } catch (Exception e) {
            // log and terminate this thread successfully
            logger.error(e.getMessage(), e);
        } catch (Error e) {
            // log and re-throw serious error which will terminate subsequent scheduled executions
            // (see ScheduledExecutorService.scheduleWithFixedDelay())
            logger.error(e.getMessage(), e);
            throw e;
        }
    }

    public void shutdown() {
        logger.debug("shutdown()");
        scheduledExecutor.shutdownNow();
    }

    // look for traces that will exceed the stack trace initial delay threshold within the next
    // polling interval and schedule stack trace capture to occur at the appropriate time(s)
    private void runInternal() {
        ImmutableCoreConfiguration configuration = configurationService.getCoreConfiguration();
        long currentTime = ticker.read();
        if (configuration.getStackTraceInitialDelayMillis()
                != ImmutableCoreConfiguration.THRESHOLD_DISABLED) {
            // stack trace threshold is not disabled
            long stackTraceThresholdTime = currentTime - TimeUnit.MILLISECONDS.toNanos(
                    configuration.getStackTraceInitialDelayMillis() - CHECK_INTERVAL_MILLIS);
            for (Trace trace : traceRegistry.getTraces()) {
                // if the trace will exceed the stack trace initial delay threshold before the next
                // scheduled execution of this repeating Runnable (in other words, it is within
                // COMMAND_INTERVAL_MILLIS from exceeding the threshold) and the stack trace capture
                // hasn't already been scheduled then schedule it
                if (NanoUtils.isLessThan(trace.getStartTime(), stackTraceThresholdTime)
                        && trace.getCaptureStackTraceScheduledFuture() == null) {

                    // schedule stack traces to be taken every X seconds
                    long initialDelayMillis = getMillisUntilTraceReachesThreshold(trace,
                            configuration.getStackTraceInitialDelayMillis());
                    CollectStackCommand command = new CollectStackCommand(trace);
                    ScheduledFuture<?> captureStackTraceScheduledFuture = scheduledExecutor
                            .scheduleWithFixedDelay(command, initialDelayMillis,
                                    configuration.getStackTracePeriodMillis(),
                                    TimeUnit.MILLISECONDS);
                    trace.setCaptureStackTraceScheduledFuture(captureStackTraceScheduledFuture);
                } else {
                    // since the list of traces are ordered by start time, if this trace
                    // didn't meet the threshold then no subsequent trace will meet the threshold
                    break;
                }
            }
        }
    }

    private long getMillisUntilTraceReachesThreshold(Trace trace, int thresholdMillis) {
        long traceDurationTime = ticker.read() - trace.getStartTime();
        return Math.max(0, thresholdMillis - TimeUnit.NANOSECONDS.toMillis(traceDurationTime));
    }
}
