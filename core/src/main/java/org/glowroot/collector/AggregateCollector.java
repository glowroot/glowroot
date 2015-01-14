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
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ScheduledExecutorService;

import javax.annotation.concurrent.GuardedBy;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Queues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.collector.AggregateBuilder.ScratchBuffer;
import org.glowroot.common.Clock;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.transaction.model.Profile;
import org.glowroot.transaction.model.Transaction;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

class AggregateCollector {

    private static final Logger logger = LoggerFactory.getLogger(TransactionProcessor.class);

    @GuardedBy("lock")
    private volatile IntervalCollector currentIntervalCollector;
    private final Object lock = new Object();

    private final ScheduledExecutorService scheduledExecutor;
    private final AggregateRepository aggregateRepository;
    private final Clock clock;

    private final long fixedAggregateIntervalMillis;

    private final BlockingQueue<PendingTransaction> pendingTransactionQueue =
            Queues.newLinkedBlockingQueue();

    private final Thread processingThread;

    AggregateCollector(ScheduledExecutorService scheduledExecutor,
            AggregateRepository aggregateRepository, Clock clock,
            long fixedAggregateIntervalSeconds) {
        this.scheduledExecutor = scheduledExecutor;
        this.aggregateRepository = aggregateRepository;
        this.clock = clock;
        this.fixedAggregateIntervalMillis = fixedAggregateIntervalSeconds * 1000;
        currentIntervalCollector = new IntervalCollector(clock.currentTimeMillis());
        // dedicated thread to aggregating transaction data
        processingThread = new Thread(new TransactionProcessor());
        processingThread.setDaemon(true);
        processingThread.setName("Glowroot-Aggregate-Collector");
        processingThread.start();
    }

    long add(Transaction transaction) {
        // this synchronized block is to ensure traces are placed into processing queue in the
        // order of captureTime (so that queue reader can assume if captureTime indicates time to
        // flush, then no new traces will come in with prior captureTime)
        synchronized (lock) {
            long captureTime = clock.currentTimeMillis();
            pendingTransactionQueue.add(new PendingTransaction(captureTime, transaction));
            return captureTime;
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
            long timeToCurrentIntervalEndTime =
                    Math.max(0, currentIntervalCollector.endTime - clock.currentTimeMillis());
            PendingTransaction pendingTransaction =
                    pendingTransactionQueue.poll(timeToCurrentIntervalEndTime + 1000, MILLISECONDS);
            if (pendingTransaction == null) {
                maybeEndOfInterval();
                return;
            }
            if (pendingTransaction.getCaptureTime() > currentIntervalCollector.endTime) {
                // flush in separate thread to avoid pending transactions from piling up quickly
                scheduledExecutor.execute(new IntervalFlusher(currentIntervalCollector));
                currentIntervalCollector =
                        new IntervalCollector(pendingTransaction.getCaptureTime());
            }
            // the synchronized block is to ensure visibility of updates to this particular
            // currentIntervalCollector
            synchronized (currentIntervalCollector) {
                currentIntervalCollector.add(pendingTransaction.getTransaction());
            }
        }

        private void maybeEndOfInterval() {
            synchronized (lock) {
                if (pendingTransactionQueue.peek() != null) {
                    // something just crept into the queue, possibly still something from
                    // current interval, it will get picked up right away and if it is in
                    // next interval it will force current aggregate to be flushed anyways
                    return;
                }
                // this should be true since poll timed out above, but checking again to be sure
                long currentTime = clock.currentTimeMillis();
                if (currentTime > currentIntervalCollector.endTime) {
                    // safe to flush, no other pending transactions can enter queue with later
                    // time (since under same lock that they use)
                    //
                    // flush in separate thread to avoid pending transactions from piling up quickly
                    scheduledExecutor.execute(new IntervalFlusher(currentIntervalCollector));
                    currentIntervalCollector = new IntervalCollector(currentTime);
                }
            }
        }
    }

    private class IntervalFlusher implements Runnable {

        private final IntervalCollector intervalCollector;

        private IntervalFlusher(IntervalCollector intervalCollector) {
            this.intervalCollector = intervalCollector;
        }

        @Override
        public void run() {
            // this synchronized block is to ensure visibility of updates to this particular
            // currentIntervalCollector
            synchronized (intervalCollector) {
                try {
                    runInternal();
                } catch (Throwable t) {
                    // log and terminate successfully
                    logger.error(t.getMessage(), t);
                }
            }
        }

        private void runInternal() throws Exception {
            List<Aggregate> overallAggregates = Lists.newArrayList();
            List<Aggregate> transactionAggregates = Lists.newArrayList();
            Map<String, IntervalTypeCollector> typeCollectors = intervalCollector.typeCollectors;
            ScratchBuffer scratchBuffer = new ScratchBuffer();
            for (Entry<String, IntervalTypeCollector> e : typeCollectors.entrySet()) {
                IntervalTypeCollector intervalTypeCollector = e.getValue();
                overallAggregates.add(intervalTypeCollector.overallBuilder.build(
                        intervalCollector.endTime, scratchBuffer));
                Map<String, AggregateBuilder> transactionBuilders =
                        intervalTypeCollector.transactionBuilders;
                for (Entry<String, AggregateBuilder> f : transactionBuilders.entrySet()) {
                    transactionAggregates.add(f.getValue().build(intervalCollector.endTime,
                            scratchBuffer));
                }
            }
            aggregateRepository.store(overallAggregates, transactionAggregates);
        }
    }

    private class IntervalCollector {

        private final long endTime;
        private final Map<String, IntervalTypeCollector> typeCollectors = Maps.newHashMap();

        private IntervalCollector(long currentTime) {
            this.endTime = (long) Math.ceil(currentTime / (double) fixedAggregateIntervalMillis)
                    * fixedAggregateIntervalMillis;
        }

        private void add(Transaction transaction) {
            IntervalTypeCollector typeBuilder = getTypeBuilder(transaction.getTransactionType());
            typeBuilder.add(transaction);
        }

        private IntervalTypeCollector getTypeBuilder(String transactionType) {
            IntervalTypeCollector typeBuilder;
            typeBuilder = typeCollectors.get(transactionType);
            if (typeBuilder == null) {
                typeBuilder = new IntervalTypeCollector(transactionType);
                typeCollectors.put(transactionType, typeBuilder);
            }
            return typeBuilder;
        }
    }

    private static class IntervalTypeCollector {

        private final String transactionType;
        private final AggregateBuilder overallBuilder;
        private final Map<String, AggregateBuilder> transactionBuilders = Maps.newHashMap();

        private IntervalTypeCollector(String transactionType) {
            this.transactionType = transactionType;
            overallBuilder = new AggregateBuilder(transactionType, null);
        }

        private void add(Transaction transaction) {
            overallBuilder.add(transaction.getDuration(), transaction.getError() != null);
            AggregateBuilder transactionBuilder =
                    transactionBuilders.get(transaction.getTransactionName());
            if (transactionBuilder == null) {
                transactionBuilder =
                        new AggregateBuilder(transactionType, transaction.getTransactionName());
                transactionBuilders.put(transaction.getTransactionName(), transactionBuilder);
            }
            transactionBuilder.add(transaction.getDuration(), transaction.getError() != null);
            overallBuilder.addToMetrics(transaction.getRootMetric());
            transactionBuilder.addToMetrics(transaction.getRootMetric());
            Profile profile = transaction.getProfile();
            if (profile != null) {
                overallBuilder.addToProfile(profile);
                transactionBuilder.addToProfile(profile);
            }
        }
    }

    private static class PendingTransaction {

        private final long captureTime;
        private final Transaction transaction;

        private PendingTransaction(long captureTime, Transaction transaction) {
            this.captureTime = captureTime;
            this.transaction = transaction;
        }

        private long getCaptureTime() {
            return captureTime;
        }

        private Transaction getTransaction() {
            return transaction;
        }
    }
}
