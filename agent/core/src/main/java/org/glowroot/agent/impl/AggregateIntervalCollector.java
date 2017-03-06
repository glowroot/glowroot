/*
 * Copyright 2015-2017 the original author or authors.
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

import java.io.IOException;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import com.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.collector.Collector;
import org.glowroot.agent.collector.Collector.AggregateReader;
import org.glowroot.agent.collector.Collector.AggregateVisitor;
import org.glowroot.agent.model.Profile;
import org.glowroot.agent.model.QueryCollector.SharedQueryTextCollector;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.live.LiveAggregateRepository.ThroughputAggregate;
import org.glowroot.common.model.LazyHistogram.ScratchBuffer;
import org.glowroot.common.model.OverallErrorSummaryCollector;
import org.glowroot.common.model.OverallSummaryCollector;
import org.glowroot.common.model.ProfileCollector;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.common.model.ServiceCallCollector;
import org.glowroot.common.model.TransactionErrorSummaryCollector;
import org.glowroot.common.model.TransactionSummaryCollector;
import org.glowroot.common.repo.Utils;
import org.glowroot.common.util.Clock;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;

public class AggregateIntervalCollector {

    private static final Logger logger = LoggerFactory.getLogger(AggregateIntervalCollector.class);

    private static final AtomicBoolean maxAggregateTransactionsWarnLogged = new AtomicBoolean();

    private final long captureTime;
    private final int maxAggregateTransactionsPerTransactionType;
    private final int maxAggregateQueriesPerType;
    private final int maxAggregateServiceCallsPerType;
    private final Clock clock;

    @GuardedBy("lock")
    private final Map<String, IntervalTypeCollector> typeCollectors = Maps.newHashMap();

    private final Object lock = new Object();

    AggregateIntervalCollector(long currentTime, long aggregateIntervalMillis,
            int maxAggregateTransactionsPerTransactionType, int maxAggregateQueriesPerType,
            int maxAggregateServiceCallsPerType, Clock clock) {
        captureTime = Utils.getRollupCaptureTime(currentTime, aggregateIntervalMillis);
        this.maxAggregateTransactionsPerTransactionType =
                maxAggregateTransactionsPerTransactionType;
        this.maxAggregateQueriesPerType = maxAggregateQueriesPerType;
        this.maxAggregateServiceCallsPerType = maxAggregateServiceCallsPerType;
        this.clock = clock;
    }

    public long getCaptureTime() {
        return captureTime;
    }

    public void add(Transaction transaction) {
        synchronized (lock) {
            IntervalTypeCollector typeCollector =
                    getTypeCollector(transaction.getTransactionType());
            typeCollector.add(transaction);
        }
    }

    public void mergeOverallSummaryInto(OverallSummaryCollector collector, String transactionType) {
        synchronized (lock) {
            IntervalTypeCollector typeCollector = typeCollectors.get(transactionType);
            if (typeCollector == null) {
                return;
            }
            typeCollector.overallAggregateCollector.mergeOverallSummaryInto(collector);
        }
    }

    public void mergeTransactionSummariesInto(TransactionSummaryCollector collector,
            String transactionType) {
        synchronized (lock) {
            IntervalTypeCollector typeCollector = typeCollectors.get(transactionType);
            if (typeCollector == null) {
                return;
            }
            for (AggregateCollector aggregateCollector : typeCollector.transactionAggregateCollectors
                    .values()) {
                aggregateCollector.mergeTransactionSummariesInto(collector);
            }
        }
    }

    public void mergeOverallErrorSummaryInto(OverallErrorSummaryCollector collector,
            String transactionType) {
        synchronized (lock) {
            IntervalTypeCollector typeCollector = typeCollectors.get(transactionType);
            if (typeCollector == null) {
                return;
            }
            typeCollector.overallAggregateCollector.mergeOverallErrorSummaryInto(collector);
        }
    }

    public void mergeTransactionErrorSummariesInto(TransactionErrorSummaryCollector collector,
            String transactionType) {
        synchronized (lock) {
            IntervalTypeCollector typeCollector = typeCollectors.get(transactionType);
            if (typeCollector == null) {
                return;
            }
            for (AggregateCollector aggregateCollector : typeCollector.transactionAggregateCollectors
                    .values()) {
                aggregateCollector.mergeTransactionErrorSummariesInto(collector);
            }
        }
    }

    public @Nullable OverviewAggregate getOverviewAggregate(String transactionType,
            @Nullable String transactionName) {
        synchronized (lock) {
            AggregateCollector aggregateCollector =
                    getAggregateCollector(transactionType, transactionName);
            if (aggregateCollector == null) {
                return null;
            }
            long liveCaptureTime = Math.min(captureTime, clock.currentTimeMillis());
            return aggregateCollector.getOverviewAggregate(liveCaptureTime);
        }
    }

    public @Nullable PercentileAggregate getPercentileAggregate(String transactionType,
            @Nullable String transactionName) {
        synchronized (lock) {
            AggregateCollector aggregateCollector =
                    getAggregateCollector(transactionType, transactionName);
            if (aggregateCollector == null) {
                return null;
            }
            long liveCaptureTime = Math.min(captureTime, clock.currentTimeMillis());
            return aggregateCollector.getPercentileAggregate(liveCaptureTime);
        }
    }

    public @Nullable ThroughputAggregate getThroughputAggregate(String transactionType,
            @Nullable String transactionName) {
        synchronized (lock) {
            AggregateCollector aggregateCollector =
                    getAggregateCollector(transactionType, transactionName);
            if (aggregateCollector == null) {
                return null;
            }
            long liveCaptureTime = Math.min(captureTime, clock.currentTimeMillis());
            return aggregateCollector.getThroughputAggregate(liveCaptureTime);
        }
    }

    public @Nullable String getFullQueryText(String fullQueryTextSha1) {
        synchronized (lock) {
            for (IntervalTypeCollector typeCollector : typeCollectors.values()) {
                String fullQueryText = typeCollector.getFullQueryText(fullQueryTextSha1);
                if (fullQueryText != null) {
                    return fullQueryText;
                }
            }
            return null;
        }
    }

    public void mergeQueriesInto(QueryCollector collector, String transactionType,
            @Nullable String transactionName) throws IOException {
        synchronized (lock) {
            AggregateCollector aggregateCollector =
                    getAggregateCollector(transactionType, transactionName);
            if (aggregateCollector == null) {
                return;
            }
            aggregateCollector.mergeQueriesInto(collector);
        }
    }

    public void mergeServiceCallsInto(ServiceCallCollector collector, String transactionType,
            @Nullable String transactionName) throws IOException {
        synchronized (lock) {
            AggregateCollector aggregateCollector =
                    getAggregateCollector(transactionType, transactionName);
            if (aggregateCollector == null) {
                return;
            }
            aggregateCollector.mergeServiceCallsInto(collector);
        }
    }

    public void mergeMainThreadProfilesInto(ProfileCollector collector, String transactionType,
            @Nullable String transactionName) {
        synchronized (lock) {
            AggregateCollector aggregateCollector =
                    getAggregateCollector(transactionType, transactionName);
            if (aggregateCollector == null) {
                return;
            }
            aggregateCollector.mergeMainThreadProfilesInto(collector);
        }
    }

    public void mergeAuxThreadProfilesInto(ProfileCollector collector, String transactionType,
            @Nullable String transactionName) {
        synchronized (lock) {
            AggregateCollector aggregateCollector =
                    getAggregateCollector(transactionType, transactionName);
            if (aggregateCollector == null) {
                return;
            }
            aggregateCollector.mergeAuxThreadProfilesInto(collector);
        }
    }

    void flush(Collector collector) throws Exception {
        collector.collectAggregates(new AggregatesImpl(captureTime));
    }

    void clear() {
        synchronized (lock) {
            typeCollectors.clear();
        }
    }

    private IntervalTypeCollector getTypeCollector(String transactionType) {
        IntervalTypeCollector typeCollector;
        typeCollector = typeCollectors.get(transactionType);
        if (typeCollector == null) {
            typeCollector = new IntervalTypeCollector();
            typeCollectors.put(transactionType, typeCollector);
        }
        return typeCollector;
    }

    private @Nullable AggregateCollector getAggregateCollector(String transactionType,
            @Nullable String transactionName) {
        IntervalTypeCollector intervalTypeCollector = typeCollectors.get(transactionType);
        if (intervalTypeCollector == null) {
            return null;
        }
        if (transactionName == null) {
            return intervalTypeCollector.overallAggregateCollector;
        } else {
            return intervalTypeCollector.transactionAggregateCollectors.get(transactionName);
        }
    }

    private class IntervalTypeCollector {

        private final AggregateCollector overallAggregateCollector;
        private final Map<String, AggregateCollector> transactionAggregateCollectors =
                Maps.newConcurrentMap();

        private IntervalTypeCollector() {
            overallAggregateCollector = new AggregateCollector(null, maxAggregateQueriesPerType,
                    maxAggregateServiceCallsPerType);
        }

        private void add(Transaction transaction) {
            merge(transaction, overallAggregateCollector);
            AggregateCollector transactionAggregateCollector =
                    transactionAggregateCollectors.get(transaction.getTransactionName());
            if (transactionAggregateCollector == null && transactionAggregateCollectors
                    .size() < maxAggregateTransactionsPerTransactionType) {
                transactionAggregateCollector =
                        new AggregateCollector(transaction.getTransactionName(),
                                maxAggregateQueriesPerType, maxAggregateServiceCallsPerType);
                transactionAggregateCollectors.put(transaction.getTransactionName(),
                        transactionAggregateCollector);
            }
            if (transactionAggregateCollector == null) {
                if (!maxAggregateTransactionsWarnLogged.getAndSet(true)) {
                    logger.warn("the max transaction names per transaction type was exceeded"
                            + " during the current interval. consider increasing the limit under"
                            + " Configuration > Advanced, or reducing the number of transaction"
                            + " names by configuring instrumentation points under Configuration"
                            + " > Instrumentation that override the transaction name.");
                }
                return;
            }
            merge(transaction, transactionAggregateCollector);
        }

        private void merge(Transaction transaction, AggregateCollector aggregateCollector) {
            aggregateCollector.add(transaction);
            aggregateCollector.getMainThreadRootTimers()
                    .mergeRootTimer(transaction.getMainThreadRootTimer());
            transaction.mergeAuxThreadTimersInto(aggregateCollector.getAuxThreadRootTimers());
            transaction.mergeAsyncTimersInto(aggregateCollector.getAsyncTimers());
            transaction.mergeQueriesInto(aggregateCollector.getQueryCollector());
            transaction.mergeServiceCallsInto(aggregateCollector.getServiceCallCollector());
            Profile mainThreadProfile = transaction.getMainThreadProfile();
            if (mainThreadProfile != null) {
                aggregateCollector.mergeMainThreadProfile(mainThreadProfile);
            }
            Profile auxThreadProfile = transaction.getAuxThreadProfile();
            if (auxThreadProfile != null) {
                aggregateCollector.mergeAuxThreadProfile(auxThreadProfile);
            }
        }

        private @Nullable String getFullQueryText(String fullQueryTextSha1) {
            String fullQueryText = overallAggregateCollector.getFullQueryText(fullQueryTextSha1);
            if (fullQueryText != null) {
                return fullQueryText;
            }
            for (AggregateCollector aggregateCollector : transactionAggregateCollectors.values()) {
                fullQueryText = aggregateCollector.getFullQueryText(fullQueryTextSha1);
                if (fullQueryText != null) {
                    return fullQueryText;
                }
            }
            return null;
        }
    }

    private class AggregatesImpl implements AggregateReader {

        private final long captureTime;

        private AggregatesImpl(long captureTime) {
            this.captureTime = captureTime;
        }

        @Override
        public long captureTime() {
            return captureTime;
        }

        @Override
        public void accept(AggregateVisitor aggregateVisitor) throws Exception {
            synchronized (lock) {
                SharedQueryTextCollector sharedQueryTextCollector = new SharedQueryTextCollector();
                ScratchBuffer scratchBuffer = new ScratchBuffer();
                for (Entry<String, IntervalTypeCollector> e : typeCollectors.entrySet()) {
                    String transactionType = e.getKey();
                    IntervalTypeCollector intervalTypeCollector = e.getValue();
                    Aggregate overallAggregate = intervalTypeCollector.overallAggregateCollector
                            .build(sharedQueryTextCollector, scratchBuffer);
                    aggregateVisitor.visitOverallAggregate(transactionType,
                            sharedQueryTextCollector.getAndClearLastestSharedQueryTexts(),
                            overallAggregate);
                    for (Entry<String, AggregateCollector> f : intervalTypeCollector.transactionAggregateCollectors
                            .entrySet()) {
                        Aggregate transactionAggregate =
                                f.getValue().build(sharedQueryTextCollector, scratchBuffer);
                        aggregateVisitor.visitTransactionAggregate(transactionType, f.getKey(),
                                sharedQueryTextCollector.getAndClearLastestSharedQueryTexts(),
                                transactionAggregate);
                    }
                }
            }
        }
    }
}
