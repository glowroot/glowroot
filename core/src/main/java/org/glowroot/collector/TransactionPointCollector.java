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

import java.io.IOException;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ScheduledExecutorService;

import com.google.common.collect.Maps;
import com.google.common.collect.Queues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.Clock;
import org.glowroot.markers.GuardedBy;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.markers.Singleton;
import org.glowroot.trace.model.Profile;
import org.glowroot.trace.model.Trace;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// TODO add thread info aggregation
@Singleton
class TransactionPointCollector {

    private static final Logger logger = LoggerFactory.getLogger(TraceProcessor.class);

    @GuardedBy("lock")
    private volatile Aggregates currentAggregates;
    private final Object lock = new Object();

    private final ScheduledExecutorService scheduledExecutor;
    private final TransactionPointRepository transactionPointRepository;
    private final Clock clock;

    private final long fixedTransactionPointIntervalMillis;

    private final BlockingQueue<PendingTrace> pendingTraceQueue = Queues.newLinkedBlockingQueue();

    private final Thread processingThread;

    TransactionPointCollector(ScheduledExecutorService scheduledExecutor,
            TransactionPointRepository transactionPointRepository, Clock clock,
            long fixedTransactionPointIntervalSeconds) {
        this.scheduledExecutor = scheduledExecutor;
        this.transactionPointRepository = transactionPointRepository;
        this.clock = clock;
        this.fixedTransactionPointIntervalMillis = fixedTransactionPointIntervalSeconds * 1000;
        currentAggregates = new Aggregates(clock.currentTimeMillis());
        // dedicated thread to processing aggregates
        processingThread = new Thread(new TraceProcessor());
        processingThread.setDaemon(true);
        processingThread.setName("Glowroot-Transaction-Point-Collector");
        processingThread.start();
    }

    long add(Trace trace, boolean traceWillBeStored) {
        // this synchronized block is to ensure traces are placed into processing queue in the
        // order of captureTime (so that queue reader can assume if captureTime indicates time to
        // flush, then no new traces will come in with prior captureTime)
        synchronized (lock) {
            long captureTime = clock.currentTimeMillis();
            pendingTraceQueue.add(new PendingTrace(captureTime, trace, traceWillBeStored));
            return captureTime;
        }
    }

    @OnlyUsedByTests
    void close() {
        processingThread.interrupt();
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
                } catch (Throwable e) {
                    // (e.g. could be temporary OOM or temporary disk error)
                    // log and re-try
                    logger.error(e.getMessage(), e);
                }
            }
        }

        private void processOne() throws InterruptedException {
            long timeToAggregateCaptureTime =
                    Math.max(0, currentAggregates.captureTime - clock.currentTimeMillis());
            PendingTrace pendingAggregation =
                    pendingTraceQueue.poll(timeToAggregateCaptureTime + 1000, MILLISECONDS);
            if (pendingAggregation == null) {
                maybeEndOfAggregate();
                return;
            }
            if (pendingAggregation.getCaptureTime() > currentAggregates.captureTime) {
                // flush in separate thread to avoid pending aggregates piling up quickly
                scheduledExecutor.execute(new AggregatesFlusher(currentAggregates));
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
                if (pendingTraceQueue.peek() != null) {
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
                    //
                    // flush in separate thread to avoid pending aggregates piling up quickly
                    scheduledExecutor.execute(new AggregatesFlusher(currentAggregates));
                    currentAggregates = new Aggregates(currentTime);
                }
            }
        }
    }

    private class AggregatesFlusher implements Runnable {

        private final Aggregates aggregates;

        private AggregatesFlusher(Aggregates aggregates) {
            this.aggregates = aggregates;
        }

        @Override
        public void run() {
            // this synchronized block is to ensure visibility of updates to this particular
            // currentAggregates
            synchronized (aggregates) {
                try {
                    runInternal();
                } catch (IOException e) {
                    logger.error(e.getMessage(), e);
                }
            }
        }

        private void runInternal() throws IOException {
            for (Entry<String, TypeAggregates> e : aggregates.typeAggregatesMap.entrySet()) {
                TypeAggregates typeAggregates = e.getValue();
                TransactionPoint overallPoint =
                        typeAggregates.overallPoint.build(aggregates.captureTime);
                Map<String, TransactionPoint> transactionPoints = Maps.newHashMap();
                for (Entry<String, TransactionPointBuilder> f : typeAggregates.transactionPoints
                        .entrySet()) {
                    transactionPoints.put(f.getKey(), f.getValue().build(aggregates.captureTime));
                }
                transactionPointRepository.store(e.getKey(), overallPoint, transactionPoints);
            }
        }
    }

    private class Aggregates {

        private final long captureTime;
        private final Map<String, TypeAggregates> typeAggregatesMap = Maps.newHashMap();

        private Aggregates(long currentTime) {
            this.captureTime =
                    (long) Math.ceil(currentTime / (double) fixedTransactionPointIntervalMillis)
                            * fixedTransactionPointIntervalMillis;
        }

        private void add(Trace trace, boolean traceWillBeStored) {
            TypeAggregates typeAggregates = getTypeAggregates(trace.getTransactionType());
            typeAggregates.add(trace, traceWillBeStored);
        }

        private TypeAggregates getTypeAggregates(String transactionType) {
            TypeAggregates typeAggregates;
            typeAggregates = typeAggregatesMap.get(transactionType);
            if (typeAggregates == null) {
                typeAggregates = new TypeAggregates();
                typeAggregatesMap.put(transactionType, typeAggregates);
            }
            return typeAggregates;
        }
    }

    private static class TypeAggregates {

        private final TransactionPointBuilder overallPoint = new TransactionPointBuilder();
        private final Map<String, TransactionPointBuilder> transactionPoints = Maps.newHashMap();

        private void add(Trace trace, boolean traceWillBeStored) {
            overallPoint.add(trace.getDuration());
            TransactionPointBuilder transactionPoint =
                    transactionPoints.get(trace.getTransactionName());
            if (transactionPoint == null) {
                transactionPoint = new TransactionPointBuilder();
                transactionPoints.put(trace.getTransactionName(), transactionPoint);
            }
            transactionPoint.add(trace.getDuration());
            if (trace.getError() != null) {
                overallPoint.addToErrorCount();
                transactionPoint.addToErrorCount();
            }
            if (traceWillBeStored) {
                overallPoint.addToStoredTraceCount();
                transactionPoint.addToStoredTraceCount();
            }
            overallPoint.addToTransactionMetrics(trace.getRootMetric());
            transactionPoint.addToTransactionMetrics(trace.getRootMetric());
            // only add profile to transaction, overall profile doesn't seem worth the overhead
            Profile profile = trace.getProfile();
            if (profile != null) {
                transactionPoint.addToProfile(profile);
            }

        }
    }

    private static class PendingTrace {

        private final long captureTime;
        private final Trace trace;
        private final boolean traceWillBeStored;

        private PendingTrace(long captureTime, Trace trace, boolean traceWillBeStored) {
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
}
