/*
 * Copyright 2013-2015 the original author or authors.
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
package org.glowroot.collector;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ScheduledExecutorService;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Queues;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.Clock;
import org.glowroot.config.ConfigService;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.transaction.model.Transaction;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class AggregateCollector {

    private static final Logger logger = LoggerFactory.getLogger(TransactionProcessor.class);

    private volatile AggregateIntervalCollector activeIntervalCollector;
    private final List<AggregateIntervalCollector> pendingIntervalCollectors =
            Lists.newCopyOnWriteArrayList();

    private final ScheduledExecutorService scheduledExecutor;
    private final AggregateRepository aggregateRepository;
    private final ConfigService configService;
    private final Clock clock;

    private final long fixedAggregateIntervalMillis;

    private final BlockingQueue<PendingTransaction> pendingTransactionQueue =
            Queues.newLinkedBlockingQueue();

    private final Thread processingThread;

    private final Object lock = new Object();

    AggregateCollector(ScheduledExecutorService scheduledExecutor,
            AggregateRepository aggregateRepository, ConfigService configService,
            long fixedAggregateIntervalSeconds, Clock clock) {
        this.scheduledExecutor = scheduledExecutor;
        this.aggregateRepository = aggregateRepository;
        this.configService = configService;
        this.clock = clock;
        this.fixedAggregateIntervalMillis = fixedAggregateIntervalSeconds * 1000;
        activeIntervalCollector = new AggregateIntervalCollector(clock.currentTimeMillis(),
                fixedAggregateIntervalMillis, configService);
        // dedicated thread to aggregating transaction data
        processingThread = new Thread(new TransactionProcessor());
        processingThread.setDaemon(true);
        processingThread.setName("Glowroot-Aggregate-Collector");
        processingThread.start();
    }

    public List<AggregateIntervalCollector> getOrderedIntervalCollectorsInRange(long from,
            long to) {
        List<AggregateIntervalCollector> intervalCollectors = Lists.newArrayList();
        for (AggregateIntervalCollector intervalCollector : getOrderedAllIntervalCollectors()) {
            long endTime = intervalCollector.getEndTime();
            if (endTime >= from && endTime <= to) {
                intervalCollectors.add(intervalCollector);
            }
        }
        return intervalCollectors;
    }

    public void clearAll() {
        activeIntervalCollector.clear();
        pendingIntervalCollectors.clear();
    }

    long add(Transaction transaction) {
        // this synchronized block is to ensure traces are placed into processing queue in the
        // order of captureTime (so that queue reader can assume if captureTime indicates time to
        // flush, then no new traces will come in with prior captureTime)
        synchronized (lock) {
            long captureTime = clock.currentTimeMillis();
            pendingTransactionQueue.add(PendingTransaction.of(captureTime, transaction));
            return captureTime;
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
    void close() {
        processingThread.interrupt();
    }

    private class TransactionProcessor implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    processOne();
                } catch (InterruptedException e) {
                    // terminate successfully
                    return;
                } catch (Throwable e) {
                    // log and continue processing
                    logger.error(e.getMessage(), e);
                }
            }
        }

        private void processOne() throws InterruptedException {
            long timeToActiveIntervalEndTime =
                    Math.max(0, activeIntervalCollector.getEndTime() - clock.currentTimeMillis());
            PendingTransaction pendingTransaction =
                    pendingTransactionQueue.poll(timeToActiveIntervalEndTime + 1000, MILLISECONDS);
            if (pendingTransaction == null) {
                maybeEndOfInterval();
                return;
            }
            if (pendingTransaction.captureTime() > activeIntervalCollector.getEndTime()) {
                // flush in separate thread to avoid pending transactions from piling up quickly
                scheduledExecutor.execute(new IntervalFlusher(activeIntervalCollector));
                activeIntervalCollector = new AggregateIntervalCollector(
                        pendingTransaction.captureTime(), fixedAggregateIntervalMillis,
                        configService);
            }
            // the synchronized block is to ensure visibility of updates to this particular
            // activeIntervalCollector
            synchronized (activeIntervalCollector) {
                activeIntervalCollector.add(pendingTransaction.transaction());
            }
        }

        private void maybeEndOfInterval() {
            synchronized (lock) {
                if (pendingTransactionQueue.peek() != null) {
                    // something just crept into the queue, possibly still something from
                    // active interval, it will get picked up right away and if it is in
                    // next interval it will force active aggregate to be flushed anyways
                    return;
                }
                // this should be true since poll timed out above, but checking again to be sure
                long currentTime = clock.currentTimeMillis();
                if (currentTime > activeIntervalCollector.getEndTime()) {
                    // safe to flush, no other pending transactions can enter queue with later
                    // time (since under same lock that they use)
                    //
                    // flush in separate thread to avoid pending transactions from piling up quickly
                    scheduledExecutor.execute(new IntervalFlusher(activeIntervalCollector));
                    activeIntervalCollector = new AggregateIntervalCollector(currentTime,
                            fixedAggregateIntervalMillis, configService);
                }
            }
        }
    }

    private class IntervalFlusher implements Runnable {

        private final AggregateIntervalCollector intervalCollector;

        private IntervalFlusher(AggregateIntervalCollector intervalCollector) {
            this.intervalCollector = intervalCollector;
            pendingIntervalCollectors.add(intervalCollector);
        }

        @Override
        public void run() {
            // this synchronized block is to ensure visibility of updates to this particular
            // activeIntervalCollector
            synchronized (intervalCollector) {
                try {
                    intervalCollector.flush(aggregateRepository);
                } catch (Throwable t) {
                    // log and terminate successfully
                    logger.error(t.getMessage(), t);
                } finally {
                    pendingIntervalCollectors.remove(intervalCollector);
                }
            }
        }
    }

    @Value.Immutable
    static abstract class PendingTransactionBase {
        @Value.Parameter
        abstract long captureTime();
        @Value.Parameter
        abstract Transaction transaction();
    }
}
