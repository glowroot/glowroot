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

import org.glowroot.collector.TraceCollectorImpl;
import org.glowroot.common.ScheduledRunnable;
import org.glowroot.common.Ticker;
import org.glowroot.config.ConfigService;
import org.glowroot.config.GeneralConfig;
import org.glowroot.markers.Singleton;
import org.glowroot.trace.model.Trace;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Owns the thread (via a single threaded scheduled executor) that watches out for stuck traces.
 * When it finds a stuck trace it sends it to {@link TraceCollectorImpl#onStuckTrace(Trace)}. This
 * ensures that a trace that never ends is still captured even though normal collection occurs at
 * the end of a trace.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class StuckTraceWatcher extends ScheduledRunnable {

    static final int PERIOD_MILLIS = 100;

    private final ScheduledExecutorService scheduledExecutor;
    private final TraceRegistry traceRegistry;
    private final TraceCollector traceCollector;
    private final ConfigService configService;
    private final Ticker ticker;

    StuckTraceWatcher(ScheduledExecutorService scheduledExecutor, TraceRegistry traceRegistry,
            TraceCollector traceCollector, ConfigService configService, Ticker ticker) {
        this.scheduledExecutor = scheduledExecutor;
        this.traceRegistry = traceRegistry;
        this.traceCollector = traceCollector;
        this.configService = configService;
        this.ticker = ticker;
    }

    // look for traces that will exceed the stuck threshold within the next polling interval and
    // schedule stuck trace command to run at the appropriate time(s)
    @Override
    protected void runInternal() {
        GeneralConfig config = configService.getGeneralConfig();
        long stuckThresholdTick = ticker.read()
                - SECONDS.toNanos(config.getStuckThresholdSeconds())
                + MILLISECONDS.toNanos(PERIOD_MILLIS);
        for (Trace trace : traceRegistry.getTraces()) {
            // if the trace is within PERIOD_MILLIS from hitting the stuck thread threshold and
            // the stuck thread messaging hasn't already been scheduled then schedule it
            if (Ticker.lessThanOrEqual(trace.getStartTick(), stuckThresholdTick)
                    && trace.getStuckScheduledRunnable() == null) {
                // schedule stuck thread
                long initialDelayMillis = Math.max(0,
                        SECONDS.toMillis(config.getStuckThresholdSeconds()
                                - NANOSECONDS.toMillis(trace.getDuration())));
                ScheduledRunnable stuckTraceScheduledRunnable =
                        new StuckTraceScheduledRunnable(trace, traceCollector);
                // repeat at minimum every 60 seconds (in case stuck threshold is set very small)
                long repeatingIntervalSeconds = Math.max(60, config.getStuckThresholdSeconds());
                stuckTraceScheduledRunnable.scheduleWithFixedDelay(scheduledExecutor,
                        initialDelayMillis, SECONDS.toMillis(repeatingIntervalSeconds),
                        MILLISECONDS);
                trace.setStuckScheduledRunnable(stuckTraceScheduledRunnable);
            }
        }
    }
}
