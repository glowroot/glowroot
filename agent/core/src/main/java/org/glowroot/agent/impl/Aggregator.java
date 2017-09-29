/*
 * Copyright 2013-2017 the original author or authors.
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
package org.glowroot.agent.impl;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.collector.Collector;
import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.util.RateLimitedLogger;
import org.glowroot.agent.util.ThreadFactories;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.OnlyUsedByTests;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.SECONDS;

public class Aggregator {

    private static final Logger logger = LoggerFactory.getLogger(TransactionProcessor.class);

    // back pressure on transaction collection
    private static final int TRANSACTION_PENDING_LIMIT = 1000;
    // back pressure on aggregate flushing
    private static final int AGGREGATE_PENDING_LIMIT = 5;

    private volatile AggregateIntervalCollector activeIntervalCollector;
    private final List<AggregateIntervalCollector> pendingIntervalCollectors =
            Lists.newCopyOnWriteArrayList();

    private final ExecutorService processingExecutor;
    private final ExecutorService flushingExecutor;
    private final Collector collector;
    private final ConfigService configService;
    private final Clock clock;

    private final long aggregateIntervalMillis;

    // all structural changes to the transaction queue are made under queueLock for simplicity
    // TODO implement lock free structure
    private final PendingTransaction head = new PendingTransaction(null);
    // tail is non-volatile since only accessed under lock
    private PendingTransaction tail = head;
    private final Object queueLock = new Object();
    @GuardedBy("queueLock")
    private int queueLength;

    private final RateLimitedLogger backPressureLogger = new RateLimitedLogger(Aggregator.class);

    private volatile boolean closed;

    public Aggregator(Collector collector, ConfigService configService,
            long aggregateIntervalMillis, Clock clock) {
        this.collector = collector;
        this.configService = configService;
        this.clock = clock;
        this.aggregateIntervalMillis = aggregateIntervalMillis;
        processingExecutor = Executors
                .newSingleThreadExecutor(ThreadFactories.create("Glowroot-Aggregate-Processing"));
        flushingExecutor = Executors
                .newSingleThreadExecutor(ThreadFactories.create("Glowroot-Aggregate-Flushing"));
        activeIntervalCollector =
                new AggregateIntervalCollector(clock.currentTimeMillis(), aggregateIntervalMillis,
                        configService.getAdvancedConfig().maxAggregateTransactionsPerType(),
                        configService.getAdvancedConfig().maxAggregateQueriesPerType(),
                        configService.getAdvancedConfig().maxAggregateServiceCallsPerType(), clock);
        processingExecutor.execute(new TransactionProcessor());
    }

    // from is non-inclusive
    public List<AggregateIntervalCollector> getOrderedIntervalCollectorsInRange(long from,
            long to) {
        List<AggregateIntervalCollector> intervalCollectors = Lists.newArrayList();
        for (AggregateIntervalCollector intervalCollector : getOrderedAllIntervalCollectors()) {
            long captureTime = intervalCollector.getCaptureTime();
            if (captureTime > from && captureTime <= to) {
                intervalCollectors.add(intervalCollector);
            }
        }
        return intervalCollectors;
    }

    public void clearInMemoryAggregate() {
        activeIntervalCollector.clear();
    }

    long add(Transaction transaction) {
        // this synchronized block is to ensure traces are placed into processing queue in the
        // order of captureTime (so that queue reader can assume if captureTime indicates time to
        // flush, then no new traces will come in with prior captureTime)
        PendingTransaction newTail = new PendingTransaction(transaction);
        long captureTime;
        synchronized (queueLock) {
            captureTime = clock.currentTimeMillis();
            if (queueLength >= TRANSACTION_PENDING_LIMIT) {
                backPressureLogger.warn("not aggregating a transaction because of an excessive"
                        + " backlog of {} transactions already waiting to be aggregated",
                        TRANSACTION_PENDING_LIMIT);
                transaction.removeFromActiveTransactions();
            } else {
                newTail.captureTime = captureTime;
                tail.next = newTail;
                tail = newTail;
                queueLength++;
            }
        }
        return captureTime;
    }

    private List<AggregateIntervalCollector> getOrderedAllIntervalCollectors() {
        // grab active first then pending (and de-dup) to make sure one is not missed between states
        AggregateIntervalCollector activeIntervalCollector = this.activeIntervalCollector;
        List<AggregateIntervalCollector> intervalCollectors =
                Lists.newArrayList(pendingIntervalCollectors);
        if (intervalCollectors.isEmpty()) {
            // common case
            return ImmutableList.of(activeIntervalCollector);
        } else if (!intervalCollectors.contains(activeIntervalCollector)) {
            intervalCollectors.add(activeIntervalCollector);
            return intervalCollectors;
        } else {
            return intervalCollectors;
        }
    }

    @OnlyUsedByTests
    public void close() throws InterruptedException {
        closed = true;
        processingExecutor.shutdown();
        if (!processingExecutor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate executor");
        }
        flushingExecutor.shutdown();
        if (!flushingExecutor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate executor");
        }
    }

    private class TransactionProcessor implements Runnable {

        @Override
        public void run() {
            while (!closed) {
                try {
                    processOne();
                } catch (Throwable e) {
                    // log and continue processing
                    logger.error(e.getMessage(), e);
                }
            }
        }

        private void processOne() throws InterruptedException {
            PendingTransaction pendingTransaction = head.next;
            if (pendingTransaction == null) {
                if (clock.currentTimeMillis() > activeIntervalCollector.getCaptureTime()) {
                    maybeEndOfInterval();
                } else {
                    // TODO benchmark other alternatives to sleep (e.g. wait/notify)
                    Thread.sleep(1);
                }
                return;
            }
            // remove transaction from list of active transactions
            // used to do this at the very end of Transaction.end(), but moved to here to remove the
            // (minor) cost from the transaction main path
            Transaction transaction = checkNotNull(pendingTransaction.transaction);
            transaction.removeFromActiveTransactions();

            // remove head
            synchronized (queueLock) {
                PendingTransaction next = pendingTransaction.next;
                head.next = next;
                if (next == null) {
                    tail = head;
                }
                queueLength--;
            }
            if (pendingTransaction.captureTime > activeIntervalCollector.getCaptureTime()) {
                flushActiveIntervalCollector();
                activeIntervalCollector = new AggregateIntervalCollector(
                        pendingTransaction.captureTime, aggregateIntervalMillis,
                        configService.getAdvancedConfig().maxAggregateTransactionsPerType(),
                        configService.getAdvancedConfig().maxAggregateQueriesPerType(),
                        configService.getAdvancedConfig().maxAggregateServiceCallsPerType(), clock);
            }
            activeIntervalCollector.add(transaction);
        }

        private void maybeEndOfInterval() {
            long currentTime;
            boolean safeToFlush;
            synchronized (queueLock) {
                if (head.next != null) {
                    // something just crept into the queue, possibly still something from active
                    // interval, it will get picked up right away and if it is in next interval it
                    // will force active aggregate to be flushed anyways
                    return;
                }
                currentTime = clock.currentTimeMillis();
                safeToFlush = currentTime > activeIntervalCollector.getCaptureTime();
            }
            if (safeToFlush) {
                // safe to flush, no other pending transactions can enter queue with later time
                // (since the check above was done under same lock used to add to queue)
                flushActiveIntervalCollector();
                activeIntervalCollector = new AggregateIntervalCollector(currentTime,
                        aggregateIntervalMillis,
                        configService.getAdvancedConfig().maxAggregateTransactionsPerType(),
                        configService.getAdvancedConfig().maxAggregateQueriesPerType(),
                        configService.getAdvancedConfig().maxAggregateServiceCallsPerType(), clock);
            }
        }

        private void flushActiveIntervalCollector() {
            if (pendingIntervalCollectors.size() >= AGGREGATE_PENDING_LIMIT) {
                logger.warn("not storing an aggregate because of an excessive backlog of {}"
                        + " aggregates already waiting to be stored", AGGREGATE_PENDING_LIMIT);
                return;
            }
            final AggregateIntervalCollector intervalCollector = activeIntervalCollector;
            pendingIntervalCollectors.add(intervalCollector);
            // flush in separate thread to avoid pending transactions from piling up quickly
            flushingExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        intervalCollector.flush(collector);
                    } catch (Throwable t) {
                        logger.error(t.getMessage(), t);
                    } finally {
                        pendingIntervalCollectors.remove(intervalCollector);
                    }
                }
            });
        }
    }

    private static class PendingTransaction {

        private final @Nullable Transaction transaction; // only null for head
        private volatile long captureTime;
        private volatile @Nullable PendingTransaction next;

        private PendingTransaction(@Nullable Transaction transaction) {
            this.transaction = transaction;
        }
    }
}
