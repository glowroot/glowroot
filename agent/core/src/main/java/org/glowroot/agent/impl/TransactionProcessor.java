/*
 * Copyright 2013-2023 the original author or authors.
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
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.concurrent.GuardedBy;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Queues;
import com.google.common.collect.Sets;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.collector.Collector;
import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.util.RateLimitedLogger;
import org.glowroot.agent.util.ThreadFactories;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.OnlyUsedByTests;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

public class TransactionProcessor {

    private static final Logger logger = LoggerFactory.getLogger(TransactionProcessor.class);

    // back pressure on transaction collection
    private static final int TRANSACTION_PENDING_LIMIT = 1000;
    // back pressure on writing captured data to disk/network
    private static final int AGGREGATE_PENDING_LIMIT = 5;

    private volatile AggregateIntervalCollector activeIntervalCollector;

    // need to guarantee these are processed in order (at least when running embedded collector
    // due to rollups relying on not seeing old data after newer data has been seen)
    private final BlockingQueue<AggregateIntervalCollector> pendingIntervalCollectors =
            Queues.newLinkedBlockingQueue(AGGREGATE_PENDING_LIMIT);

    private final ExecutorService processingExecutor;
    private final ExecutorService flushingExecutor;
    private final Collector collector;
    private final TraceCollector traceCollector;
    private final ConfigService configService;
    private final Clock clock;

    private final long aggregateIntervalMillis;

    // all structural changes to the transaction queue are made under queueLock for simplicity
    // TODO implement lock free structure
    private final PendingTransaction head = new PendingTransaction(null);
    @GuardedBy("queueLock")
    private PendingTransaction tail = head;
    @GuardedBy("queueLock")
    private int queueLength;
    private final Object queueLock = new Object();

    private final RateLimitedLogger backPressureLogger =
            new RateLimitedLogger(TransactionProcessor.class);

    private volatile boolean closed;

    public TransactionProcessor(Collector collector, TraceCollector traceCollector,
            ConfigService configService, long aggregateIntervalMillis, Clock clock) {
        this.collector = collector;
        this.traceCollector = traceCollector;
        this.configService = configService;
        this.clock = clock;
        this.aggregateIntervalMillis = aggregateIntervalMillis;
        processingExecutor = Executors
                .newSingleThreadExecutor(ThreadFactories.create("Glowroot-Aggregate-Processing"));
        flushingExecutor = Executors
                .newSingleThreadExecutor(ThreadFactories.create("Glowroot-Aggregate-Flushing"));
        activeIntervalCollector =
                new AggregateIntervalCollector(clock.currentTimeMillis(), aggregateIntervalMillis,
                        configService.getAdvancedConfig().maxTransactionAggregates(),
                        configService.getAdvancedConfig().maxQueryAggregates(),
                        configService.getAdvancedConfig().maxServiceCallAggregates(), clock);
        processingExecutor.execute(new TransactionProcessorLoop());
        flushingExecutor.execute(new AggregateFlushingLoop());
    }

    public Set<String> getTransactionTypes() {
        Set<String> transactionTypes = Sets.newHashSet();
        transactionTypes.addAll(activeIntervalCollector.getTransactionTypes());
        for (AggregateIntervalCollector intervalCollector : pendingIntervalCollectors) {
            transactionTypes.addAll(intervalCollector.getTransactionTypes());
        }
        return transactionTypes;
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

    public void clearInMemoryData() {
        activeIntervalCollector.clear();
        synchronized (pendingIntervalCollectors) {
            pendingIntervalCollectors.clear();
        }
    }

    void processOnCompletion(Transaction transaction) {
        // this synchronized block is to ensure traces are placed into processing queue in the
        // order of captureTime (so that queue reader can assume if captureTime indicates time to
        // flush, then no new traces will come in with prior captureTime)
        PendingTransaction newTail = new PendingTransaction(transaction);
        boolean exceededLimit = false;
        synchronized (queueLock) {
            if (queueLength < TRANSACTION_PENDING_LIMIT) {
                newTail.captureTime = clock.currentTimeMillis();
                tail.next = newTail;
                tail = newTail;
                queueLength++;
            } else {
                exceededLimit = true;
            }
        }
        if (exceededLimit) {
            backPressureLogger.warn("not capturing a transaction because of an excessive backlog of"
                    + " {} transactions already waiting to be captured", TRANSACTION_PENDING_LIMIT);
            transaction.setCaptureTime(clock.currentTimeMillis());
            transaction.removeFromActiveTransactions();
        }
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
        // shutdownNow() is needed here to send interrupt to flushing thread
        flushingExecutor.shutdownNow();
        if (!flushingExecutor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate executor");
        }
    }

    private class TransactionProcessorLoop implements Runnable {

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
                    MILLISECONDS.sleep(1);
                }
                return;
            }
            // remove transaction from list of active transactions
            // used to do this at the very end of Transaction.end(), but moved to here to remove the
            // (minor) cost from the transaction main path
            Transaction transaction = checkNotNull(pendingTransaction.transaction);
            transaction.setCaptureTime(pendingTransaction.captureTime);

            // send to the trace collector before removing from transaction registry so that the
            // trace collector can cover the gap (via TraceCollector.getPendingTransactions())
            // between removing the transaction from the registry and storing it
            traceCollector.collectTrace(transaction);

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
                flushAndResetActiveIntervalCollector(pendingTransaction.captureTime);
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
                flushAndResetActiveIntervalCollector(currentTime);
            }
        }

        private void flushAndResetActiveIntervalCollector(long currentTime) {
            flushActiveIntervalCollector();
            activeIntervalCollector =
                    new AggregateIntervalCollector(currentTime, aggregateIntervalMillis,
                            configService.getAdvancedConfig().maxTransactionAggregates(),
                            configService.getAdvancedConfig().maxQueryAggregates(),
                            configService.getAdvancedConfig().maxServiceCallAggregates(), clock);
        }

        private void flushActiveIntervalCollector() {
            boolean accepted;
            synchronized (pendingIntervalCollectors) {
                accepted = pendingIntervalCollectors.offer(activeIntervalCollector);
                if (accepted) {
                    pendingIntervalCollectors.notifyAll();
                }
            }
            if (!accepted) {
                // don't log under synchronized lock
                logger.warn("not storing an aggregate because of an excessive backlog of {}"
                        + " aggregates already waiting to be stored", AGGREGATE_PENDING_LIMIT);
            }
        }
    }

    private class AggregateFlushingLoop implements Runnable {
        @Override
        public void run() {
            while (!closed) {
                try {
                    AggregateIntervalCollector intervalCollector;
                    synchronized (pendingIntervalCollectors) {
                        if (pendingIntervalCollectors.peek() == null) {
                            pendingIntervalCollectors.wait();
                            continue;
                        }
                        intervalCollector = pendingIntervalCollectors.remove();
                    }
                    intervalCollector.flush(collector);
                } catch (InterruptedException e) {
                    // probably shutdown requested (see close method above)
                    logger.debug(e.getMessage(), e);
                } catch (Throwable e) {
                    // log and continue processing
                    logger.error(e.getMessage(), e);
                }
            }
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
