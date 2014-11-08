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
package org.glowroot.transaction;

import java.util.concurrent.ScheduledExecutorService;

import org.glowroot.common.ScheduledRunnable;
import org.glowroot.common.Ticker;
import org.glowroot.config.ConfigService;
import org.glowroot.config.TraceConfig;
import org.glowroot.markers.Singleton;
import org.glowroot.transaction.model.Transaction;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * Captures outlier profile for traces that exceed the configured threshold.
 * 
 * The main repeating Runnable (this) only runs every PERIOD_MILLIS at which time it checks to see
 * if there are any traces that may need stack traces scheduled before the main repeating Runnable
 * runs again (in another PERIOD_MILLIS). the main repeating Runnable schedules a repeating
 * CollectStackCommand for any trace that may need a stack trace in the next PERIOD_MILLIS. since
 * the majority of traces never end up needing stack traces this is much more efficient than
 * scheduling a repeating CollectStackCommand for every trace (this was learned the hard way).
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class OutlierProfileWatcher extends ScheduledRunnable {

    static final int PERIOD_MILLIS = 100;

    private final ScheduledExecutorService scheduledExecutor;
    private final TransactionRegistry transactionRegistry;
    private final ConfigService configService;
    private final Ticker ticker;

    OutlierProfileWatcher(ScheduledExecutorService scheduledExecutor,
            TransactionRegistry transactionRegistry, ConfigService configService, Ticker ticker) {
        this.scheduledExecutor = scheduledExecutor;
        this.transactionRegistry = transactionRegistry;
        this.configService = configService;
        this.ticker = ticker;
    }

    // look for traces that will exceed the stack trace initial delay threshold within the next
    // polling interval and schedule stack trace capture to occur at the appropriate time(s)
    @Override
    protected void runInternal() {
        TraceConfig config = configService.getTraceConfig();
        if (!config.isOutlierProfilingEnabled()) {
            return;
        }
        long currentTick = ticker.read();
        int initialDelayMillis = config.getOutlierProfilingInitialDelayMillis();
        long stackTraceThresholdTime = currentTick
                - MILLISECONDS.toNanos(initialDelayMillis - PERIOD_MILLIS);
        for (Transaction transaction : transactionRegistry.getTransactions()) {
            // if the trace will exceed the stack trace initial delay threshold before the next
            // scheduled execution of this repeating Runnable (in other words, it is within
            // PERIOD_MILLIS from exceeding the threshold) and the stack trace capture
            // hasn't already been scheduled then schedule it
            if (Ticker.lessThanOrEqual(transaction.getStartTick(), stackTraceThresholdTime)
                    && transaction.getOutlierProfileRunnable() == null) {
                scheduleProfiling(transaction, currentTick, config);
            }
        }
    }

    // schedule stack traces to be taken every X seconds
    private void scheduleProfiling(Transaction transaction, long currentTick, TraceConfig config) {
        ScheduledRunnable profileRunnable =
                new TransactionProfileRunnable(transaction, true, configService);
        long initialDelayRemainingMillis =
                getInitialDelayForCommand(transaction.getStartTick(), currentTick, config);
        profileRunnable.scheduleWithFixedDelay(scheduledExecutor, initialDelayRemainingMillis,
                config.getOutlierProfilingIntervalMillis(), MILLISECONDS);
        transaction.setOutlierProfileRunnable(profileRunnable);
    }

    private static long getInitialDelayForCommand(long startTick, long currentTick,
            TraceConfig config) {
        long traceDurationMillis = NANOSECONDS.toMillis(currentTick - startTick);
        return Math.max(0, config.getOutlierProfilingInitialDelayMillis() - traceDurationMillis);
    }
}
