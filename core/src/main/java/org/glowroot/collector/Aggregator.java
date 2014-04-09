/*
 * Copyright 2013-2014 the original author or authors.
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

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ScheduledExecutorService;

import javax.annotation.concurrent.GuardedBy;

import com.google.common.collect.Maps;
import com.google.common.collect.Queues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.Clock;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.markers.Singleton;
import org.glowroot.trace.model.Trace;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// TODO add JvmInfo aggregation
@Singleton
class Aggregator {

    private static final Logger logger = LoggerFactory.getLogger(TraceProcessor.class);

    @GuardedBy("lock")
    private volatile Aggregates currentAggregates;
    private final Object lock = new Object();

    private final ScheduledExecutorService scheduledExecutor;
    private final AggregateRepository aggregateRepository;
    private final Clock clock;

    private final long fixedAggregationIntervalMillis;

    private final BlockingQueue<PendingAggregation> pendingAggregationQueue =
            Queues.newLinkedBlockingQueue();

    private final Thread processingThread;

    Aggregator(ScheduledExecutorService scheduledExecutor, AggregateRepository aggregateRepository,
            Clock clock, long fixedAggregationIntervalSeconds) {
        this.scheduledExecutor = scheduledExecutor;
        this.aggregateRepository = aggregateRepository;
        this.clock = clock;
        this.fixedAggregationIntervalMillis = fixedAggregationIntervalSeconds * 1000;
        currentAggregates = new Aggregates(clock.currentTimeMillis());
        // dedicated thread to processing aggregates
        processingThread = new Thread(new TraceProcessor());
        processingThread.setDaemon(true);
        processingThread.setName("Glowroot-Aggregator");
        processingThread.start();
    }

    long add(Trace trace, boolean traceWillBeStored) {
        // this synchronized block is to ensure traces are placed into processing queue in the
        // order of captureTime (so that queue reader can assume if captureTime indicates time to
        // flush, then no new traces will come in with prior captureTime)
        synchronized (lock) {
            long captureTime = clock.currentTimeMillis();
            pendingAggregationQueue.add(
                    new PendingAggregation(captureTime, trace, traceWillBeStored));
            return captureTime;
        }
    }

    @OnlyUsedByTests
    void close() {
        processingThread.interrupt();
    }

    private class Aggregates {

        private final long captureTime;
        private final AggregateBuilder overallAggregate = new AggregateBuilder();
        private final AggregateBuilder bgOverallAggregate = new AggregateBuilder();
        private final Map<String, AggregateBuilder> transactionAggregates = Maps.newHashMap();
        private final Map<String, AggregateBuilder> bgTransactionAggregates = Maps.newHashMap();

        private Aggregates(long currentTime) {
            this.captureTime = (long) Math.ceil(currentTime
                    / (double) fixedAggregationIntervalMillis) * fixedAggregationIntervalMillis;
        }

        private void add(Trace trace, boolean traceWillBeStored) {
            AggregateBuilder overallAggregate;
            Map<String, AggregateBuilder> transactionAggregates;
            if (trace.isBackground()) {
                overallAggregate = this.bgOverallAggregate;
                transactionAggregates = this.bgTransactionAggregates;
            } else {
                overallAggregate = this.overallAggregate;
                transactionAggregates = this.transactionAggregates;
            }
            overallAggregate.add(trace.getDuration());
            AggregateBuilder transactionAggregate =
                    transactionAggregates.get(trace.getTransactionName());
            if (transactionAggregate == null) {
                transactionAggregate = new AggregateBuilder();
                transactionAggregates.put(trace.getTransactionName(), transactionAggregate);
            }
            transactionAggregate.add(trace.getDuration());
            if (trace.getError() != null) {
                overallAggregate.addToErrorCount();
                transactionAggregate.addToErrorCount();
            }
            if (traceWillBeStored) {
                overallAggregate.addToStoredTraceCount();
                transactionAggregate.addToStoredTraceCount();
            }
            overallAggregate.addToMetrics(trace.getRootMetric());
            transactionAggregate.addToMetrics(trace.getRootMetric());
        }
    }

    private static class PendingAggregation {

        private final long captureTime;
        private final Trace trace;
        private final boolean traceWillBeStored;

        private PendingAggregation(long captureTime, Trace trace, boolean traceWillBeStored) {
            this.captureTime = captureTime;
            this.trace = trace;
            this.traceWillBeStored = traceWillBeStored;
        }

        private long getCaptureTime() {
            return captureTime;
        }

        private Trace getTrace() {
            return trace;
        }

        private boolean isTraceWillBeStored() {
            return traceWillBeStored;
        }
    }

    private class TraceProcessor implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    processOne();
                } catch (InterruptedException e) {
                    // terminate successfully
                    return;
                } catch (Exception e) {
                    // log and re-try
                    logger.error(e.getMessage(), e);
                } catch (Throwable t) {
                    // serious error, log and terminate
                    logger.error(t.getMessage(), t);
                    return;
                }
            }
        }

        private void processOne() throws InterruptedException {
            long timeToAggregateCaptureTime =
                    Math.max(0, currentAggregates.captureTime - clock.currentTimeMillis());
            PendingAggregation pendingAggregation =
                    pendingAggregationQueue.poll(timeToAggregateCaptureTime + 1000, MILLISECONDS);
            if (pendingAggregation == null) {
                maybeEndOfAggregate();
                return;
            }
            if (pendingAggregation.getCaptureTime() > currentAggregates.captureTime) {
                flushInSeparateThread(currentAggregates);
                currentAggregates = new Aggregates(pendingAggregation.getCaptureTime());
            }
            // the synchronized block is to ensure visibility of updates to this particular
            // currentAggregates
            synchronized (currentAggregates) {
                currentAggregates.add(pendingAggregation.getTrace(),
                        pendingAggregation.isTraceWillBeStored());
            }
        }

        private void maybeEndOfAggregate() {
            synchronized (lock) {
                if (pendingAggregationQueue.peek() != null) {
                    // something just crept into the queue, possibly still something from
                    // current aggregate, it will get picked up right away and if it is in
                    // next aggregate interval it will force current aggregate to be flushed
                    // anyways
                    return;
                }
                // this should be true since poll timed out above, but checking again to be sure
                long currentTime = clock.currentTimeMillis();
                if (currentTime > currentAggregates.captureTime) {
                    // safe to flush, no other pending aggregates can enter queue with later
                    // time (since under same lock that they use)
                    flushInSeparateThread(currentAggregates);
                    currentAggregates = new Aggregates(currentTime);
                }
            }
        }

        // flush in separate thread to avoid pending aggregates piling up quickly
        private void flushInSeparateThread(final Aggregates aggregates) {
            scheduledExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    // this synchronized block is to ensure visibility of updates to this particular
                    // currentAggregates
                    synchronized (aggregates) {
                        aggregateRepository.store(aggregates.captureTime,
                                aggregates.overallAggregate, aggregates.transactionAggregates,
                                aggregates.bgOverallAggregate, aggregates.bgTransactionAggregates);
                    }
                }
            });
        }
    }
}
