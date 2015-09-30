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
package org.glowroot.agent.core.impl;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import javax.annotation.concurrent.GuardedBy;

import com.google.common.base.Strings;
import com.google.common.base.Ticker;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.core.Collector;
import org.glowroot.agent.core.config.ConfigService;
import org.glowroot.agent.core.model.TraceCreator;
import org.glowroot.agent.core.model.Transaction;
import org.glowroot.common.util.Clock;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class TransactionCollector {

    private static final Logger logger = LoggerFactory.getLogger(TransactionCollector.class);

    private static final int PENDING_LIMIT = 100;

    private final ExecutorService executorService;
    private final ConfigService configService;
    private final Collector collector;
    private final Aggregator aggregator;
    private final Clock clock;
    private final Ticker ticker;
    private final Set<Transaction> pendingTransactions = Sets.newCopyOnWriteArraySet();

    private final RateLimiter warningRateLimiter = RateLimiter.create(1.0 / 60);
    @GuardedBy("warningRateLimiter")
    private int countSinceLastWarning;

    public TransactionCollector(ExecutorService executorService, ConfigService configService,
            Collector collector, Aggregator aggregator, Clock clock, Ticker ticker) {
        this.executorService = executorService;
        this.configService = configService;
        this.collector = collector;
        this.aggregator = aggregator;
        this.clock = clock;
        this.ticker = ticker;
    }

    public boolean shouldStoreSlow(Transaction transaction) {
        if (transaction.isPartiallyStored()) {
            return true;
        }
        // check if trace-specific store threshold was set
        long slowThresholdMillis = transaction.getSlowThresholdMillisOverride();
        if (slowThresholdMillis != Transaction.USE_GENERAL_STORE_THRESHOLD) {
            return transaction.getDurationNanos() >= MILLISECONDS.toNanos(slowThresholdMillis);
        }
        // fall back to default slow trace threshold
        slowThresholdMillis = configService.getTransactionConfig().slowThresholdMillis();
        if (transaction.getDurationNanos() >= MILLISECONDS.toNanos(slowThresholdMillis)) {
            return true;
        }

        // for now lumping user recording into slow traces tab
        //
        // check if should store for user recording
        if (configService.getUserRecordingConfig().enabled()) {
            String user = transaction.getUser();
            if (!Strings.isNullOrEmpty(user)
                    && user.equalsIgnoreCase(configService.getUserRecordingConfig().user())) {
                return true;
            }
        }
        return false;
    }

    public boolean shouldStoreError(Transaction transaction) {
        return transaction.getErrorMessage() != null;
    }

    public Collection<Transaction> getPendingTransactions() {
        return pendingTransactions;
    }

    void onCompletedTransaction(final Transaction transaction) {

        transaction.onCompleteCaptureThreadInfo();
        // capture time is calculated by the aggregator because it depends on monotonically
        // increasing capture times so it can flush aggregates without concern for new data
        // arriving with a prior capture time
        long captureTime = aggregator.add(transaction);
        final boolean slow = shouldStoreSlow(transaction);
        if (!slow && !shouldStoreError(transaction)) {
            return;
        }
        if (pendingTransactions.size() >= PENDING_LIMIT) {
            logPendingLimitWarning();
            return;
        }
        pendingTransactions.add(transaction);

        // these onComplete.. methods need to be called inside the transaction thread
        transaction.onCompleteCaptureGcActivity();
        transaction.onComplete(captureTime);

        Runnable command = new Runnable() {
            @Override
            public void run() {
                try {
                    Trace trace = TraceCreator.createCompletedTrace(transaction, slow);
                    collector.collectTrace(trace);
                } catch (Throwable t) {
                    logger.error(t.getMessage(), t);
                } finally {
                    pendingTransactions.remove(transaction);
                }
            }
        };
        executorService.execute(command);
    }

    // no need to throttle partial trace storage since throttling is handled upstream by using a
    // single thread executor in PartialTraceStorageWatcher
    public void storePartialTrace(Transaction transaction) {
        try {
            Trace trace = TraceCreator.createPartialTrace(transaction, clock.currentTimeMillis(),
                    ticker.read());
            transaction.setPartiallyStored();
            // one last check if transaction has completed
            if (!transaction.isCompleted()) {
                collector.collectTrace(trace);
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    private void logPendingLimitWarning() {
        synchronized (warningRateLimiter) {
            if (warningRateLimiter.tryAcquire(0, MILLISECONDS)) {
                logger.warn("not storing a trace because of an excessive backlog of {} traces"
                        + " already waiting to be stored (this warning will appear at most once a"
                        + " minute, there were {} additional traces not stored since the last"
                        + " warning)", PENDING_LIMIT, countSinceLastWarning);
                countSinceLastWarning = 0;
            } else {
                countSinceLastWarning++;
            }
        }
    }
}
