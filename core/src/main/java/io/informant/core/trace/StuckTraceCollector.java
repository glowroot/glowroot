/**
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
package io.informant.core.trace;

import io.informant.api.Logger;
import io.informant.api.LoggerFactory;
import io.informant.core.config.ConfigService;
import io.informant.core.config.GeneralConfig;
import io.informant.core.util.DaemonExecutors;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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

    private final TraceRegistry traceRegistry;
    private final TraceSink traceSink;
    private final ConfigService configService;
    private final Ticker ticker;

    @Inject
    StuckTraceCollector(TraceRegistry traceRegistry, TraceSink traceSink,
            ConfigService configService, Ticker ticker) {

        this.traceRegistry = traceRegistry;
        this.traceSink = traceSink;
        this.configService = configService;
        this.ticker = ticker;
        // wait to schedule the real stuck thread command until it is within CHECK_INTERVAL_MILLIS
        // from needing to start
        scheduledExecutor
                .scheduleAtFixedRate(this, 0, CHECK_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
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

    // look for traces that will exceed the stuck threshold within the next polling interval and
    // schedule stuck trace command to run at the appropriate time(s)
    private void runInternal() {
        GeneralConfig config = configService.getGeneralConfig();
        if (config.getStuckThresholdSeconds() != GeneralConfig.STORE_THRESHOLD_DISABLED) {
            // stuck threshold is not disabled
            long stuckThresholdTick = ticker.read()
                    - TimeUnit.SECONDS.toNanos(config.getStuckThresholdSeconds())
                    + TimeUnit.MILLISECONDS.toNanos(CHECK_INTERVAL_MILLIS);
            for (Trace trace : traceRegistry.getTraces()) {
                // if the trace is within CHECK_INTERVAL_MILLIS from hitting the stuck
                // thread threshold and the stuck thread messaging hasn't already been scheduled
                // then schedule it
                if (Nanoseconds.lessThan(trace.getStartTick(), stuckThresholdTick)
                        && trace.getStuckScheduledFuture() == null) {
                    // schedule stuck thread
                    long initialDelayMillis = Math.max(0,
                            TimeUnit.SECONDS.toMillis(config.getStuckThresholdSeconds()
                                    - TimeUnit.NANOSECONDS.toMillis(trace.getDuration())));
                    CollectStuckTraceCommand command = new CollectStuckTraceCommand(trace,
                            traceSink);
                    ScheduledFuture<?> scheduledFuture = scheduledExecutor.schedule(command,
                            initialDelayMillis, TimeUnit.MILLISECONDS);
                    trace.setStuckScheduledFuture(scheduledFuture);
                } else {
                    // since the list of traces are ordered by start time, if this trace
                    // didn't meet the threshold then no subsequent trace will meet the threshold
                    break;
                }
            }
        }
    }
}
