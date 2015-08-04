/*
 * Copyright 2011-2015 the original author or authors.
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

import com.google.common.base.Ticker;

import org.glowroot.common.ScheduledRunnable;
import org.glowroot.common.Tickers;
import org.glowroot.config.ConfigService;
import org.glowroot.transaction.model.Transaction;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

class ImmediateTraceStoreWatcher extends ScheduledRunnable {

    static final int PERIOD_MILLIS = 1000;

    private final ScheduledExecutorService scheduledExecutor;
    private final TransactionRegistry transactionRegistry;
    private final TransactionCollector transactionCollector;
    private final ConfigService configService;
    private final Ticker ticker;

    ImmediateTraceStoreWatcher(ScheduledExecutorService scheduledExecutor,
            TransactionRegistry transactionRegistry, TransactionCollector transactionCollector,
            ConfigService configService, Ticker ticker) {
        this.scheduledExecutor = scheduledExecutor;
        this.transactionRegistry = transactionRegistry;
        this.transactionCollector = transactionCollector;
        this.configService = configService;
        this.ticker = ticker;
    }

    // look for traces that will exceed the partial store threshold within the next polling interval
    // and schedule partial trace command to run at the appropriate time(s)
    @Override
    protected void runInternal() {
        int immediatePartialStoreThresholdSeconds =
                configService.getAdvancedConfig().immediatePartialStoreThresholdSeconds();
        long immediatePartialStoreTick =
                ticker.read() - SECONDS.toNanos(immediatePartialStoreThresholdSeconds)
                        + MILLISECONDS.toNanos(PERIOD_MILLIS);
        for (Transaction transaction : transactionRegistry.getTransactions()) {
            // if the transaction is within PERIOD_MILLIS from hitting the partial trace store
            // threshold and the partial trace store hasn't already been scheduled then schedule it
            if (Tickers.lessThanOrEqual(transaction.getStartTick(), immediatePartialStoreTick)
                    && transaction.getImmedateTraceStoreRunnable() == null) {
                // schedule partial trace storage
                long initialDelayMillis =
                        Math.max(0, SECONDS.toMillis(immediatePartialStoreThresholdSeconds)
                                - NANOSECONDS.toMillis(transaction.getDuration()));
                ScheduledRunnable immediateTraceStoreRunnable =
                        new ImmediateTraceStoreRunnable(transaction, transactionCollector);
                // repeat at minimum every 60 seconds (in case partial store threshold is set very
                // small)
                long repeatingIntervalSeconds = Math.max(60, immediatePartialStoreThresholdSeconds);
                immediateTraceStoreRunnable.scheduleAtFixedRate(scheduledExecutor,
                        initialDelayMillis, SECONDS.toMillis(repeatingIntervalSeconds),
                        MILLISECONDS);
                transaction.setImmediateTraceStoreRunnable(immediateTraceStoreRunnable);
            }
        }
    }
}
