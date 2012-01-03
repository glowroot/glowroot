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
 * Owns the thread (via a single threaded scheduled executor) that watches out for stuck traces.
 * When it finds a stuck trace it sends it to {@link TraceSink#onStuckTrace(Trace)}. This ensures
 * that a trace that never ends is still captured even though normal collection occurs at the end of
 * a trace.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class StuckTraceCollector implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(StuckTraceCollector.class);
    private static final int CHECK_INTERVAL_MILLIS = 100;

    private final ScheduledExecutorService scheduledExecutor = DaemonExecutors
            .newSingleThreadScheduledExecutor("Informant-StuckTraceCollector");

    private final TraceService traceService;
    private final TraceSink traceSink;
    private final ConfigurationService configurationService;
    private final Ticker ticker;

    @Inject
    public StuckTraceCollector(TraceService traceService, TraceSink traceSink,
            ConfigurationService configurationService, Ticker ticker) {

        this.traceService = traceService;
        this.traceSink = traceSink;
        this.configurationService = configurationService;
        this.ticker = ticker;
        // wait to schedule the real stuck thread command until it is within CHECK_INTERVAL_MILLIS
        // from needing to start
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

    // look for traces that will exceed the stuck threshold within the next polling interval and
    // schedule stuck message generation to occur at the appropriate time(s)
    private void runInternal() {
        ImmutableCoreConfiguration configuration = configurationService.getCoreConfiguration();
        long currentTime = ticker.read();
        if (configuration.getStuckThresholdMillis()
                != ImmutableCoreConfiguration.THRESHOLD_DISABLED) {
            // stuck threshold is not disabled
            long stuckMessageThresholdTime = currentTime - TimeUnit.MILLISECONDS.toNanos(
                    configuration.getStuckThresholdMillis() - CHECK_INTERVAL_MILLIS);
            for (Trace trace : traceService.getTraces()) {
                // if the trace is within CHECK_INTERVAL_MILLIS from hitting the stuck
                // thread threshold and the stuck thread messaging hasn't already been scheduled
                // then schedule it
                if (NanoUtils.isLessThan(trace.getStartTime(), stuckMessageThresholdTime)
                        && trace.getStuckCommandScheduledFuture() == null) {
                    // schedule stuck thread message
                    long initialDelayMillis = getMillisUntilTraceReachesThreshold(trace,
                            configuration.getStuckThresholdMillis());
                    CollectStuckTraceCommand command = new CollectStuckTraceCommand(trace,
                            traceSink);
                    ScheduledFuture<?> stuckCommandScheduledFuture = scheduledExecutor.schedule(
                            command, initialDelayMillis, TimeUnit.MILLISECONDS);
                    trace.setStuckCommandScheduledFuture(stuckCommandScheduledFuture);
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
        return thresholdMillis - TimeUnit.NANOSECONDS.toMillis(traceDurationTime);
    }
}
