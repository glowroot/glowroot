/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.agent.model;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.Collector;
import org.glowroot.common.live.LiveAggregateRepository.ErrorPoint;
import org.glowroot.common.live.LiveAggregateRepository.OverallErrorSummary;
import org.glowroot.common.live.LiveAggregateRepository.OverallSummary;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.live.LiveAggregateRepository.ThroughputAggregate;
import org.glowroot.common.live.LiveAggregateRepository.TransactionErrorSummary;
import org.glowroot.common.live.LiveAggregateRepository.TransactionSummary;
import org.glowroot.common.model.LazyHistogram.ScratchBuffer;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.OverallAggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.TransactionAggregate;
import org.glowroot.wire.api.model.ProfileTreeOuterClass.ProfileTree;

public class AggregateIntervalCollector {

    private static final Logger logger = LoggerFactory.getLogger(AggregateIntervalCollector.class);

    private static final AtomicBoolean maxAggregateTransactionsWarnLogged = new AtomicBoolean();

    private final long captureTime;
    private final Map<String, IntervalTypeCollector> typeCollectors = Maps.newConcurrentMap();
    private final int maxAggregateTransactionsPerTransactionType;
    private final int maxAggregateQueriesPerQueryType;

    public AggregateIntervalCollector(long currentTime, long aggregateIntervalMillis,
            int maxAggregateTransactionsPerTransactionType, int maxAggregateQueriesPerQueryType) {
        captureTime = (long) Math.ceil(currentTime / (double) aggregateIntervalMillis)
                * aggregateIntervalMillis;
        this.maxAggregateTransactionsPerTransactionType =
                maxAggregateTransactionsPerTransactionType;
        this.maxAggregateQueriesPerQueryType = maxAggregateQueriesPerQueryType;
    }

    public long getCaptureTime() {
        return captureTime;
    }

    public void add(Transaction transaction) {
        IntervalTypeCollector typeCollector = getTypeCollector(transaction.getTransactionType());
        typeCollector.add(transaction);
    }

    public void flush(Collector collector) throws Exception {
        List<OverallAggregate> overallAggregates = Lists.newArrayList();
        List<TransactionAggregate> transactionAggregates = Lists.newArrayList();
        ScratchBuffer scratchBuffer = new ScratchBuffer();
        for (Entry<String, IntervalTypeCollector> e : typeCollectors.entrySet()) {
            IntervalTypeCollector intervalTypeCollector = e.getValue();
            overallAggregates.add(buildOverallAggregate(e.getKey(),
                    intervalTypeCollector.overallAggregateCollector, scratchBuffer));
            for (Entry<String, AggregateCollector> f : intervalTypeCollector.transactionAggregateCollectors
                    .entrySet()) {
                transactionAggregates.add(buildTransactionAggregate(e.getKey(), f.getKey(),
                        f.getValue(), scratchBuffer));
            }
        }
        collector.collectAggregates(captureTime, overallAggregates, transactionAggregates);
    }

    public @Nullable OverallSummary getLiveOverallSummary(String transactionType) {
        IntervalTypeCollector intervalTypeCollector = typeCollectors.get(transactionType);
        if (intervalTypeCollector == null) {
            return null;
        }
        AggregateCollector aggregateCollector = intervalTypeCollector.overallAggregateCollector;
        synchronized (aggregateCollector) {
            return aggregateCollector.getLiveOverallSummary();
        }
    }

    public List<TransactionSummary> getLiveTransactionSummaries(String transactionType) {
        IntervalTypeCollector intervalTypeCollector = typeCollectors.get(transactionType);
        if (intervalTypeCollector == null) {
            return ImmutableList.of();
        }
        List<TransactionSummary> transactionSummaries = Lists.newArrayList();
        for (Entry<String, AggregateCollector> entry : intervalTypeCollector.transactionAggregateCollectors
                .entrySet()) {
            AggregateCollector aggregateCollector = entry.getValue();
            synchronized (aggregateCollector) {
                transactionSummaries.add(aggregateCollector.getLiveTransactionSummary());
            }
        }
        return transactionSummaries;
    }

    public @Nullable OverallErrorSummary getLiveOverallErrorSummary(String transactionType) {
        IntervalTypeCollector intervalTypeCollector = typeCollectors.get(transactionType);
        if (intervalTypeCollector == null) {
            return null;
        }
        AggregateCollector aggregateCollector = intervalTypeCollector.overallAggregateCollector;
        synchronized (aggregateCollector) {
            return aggregateCollector.getLiveOverallErrorSummary();
        }
    }

    public List<TransactionErrorSummary> getLiveTransactionErrorSummaries(String transactionType) {
        IntervalTypeCollector intervalTypeCollector = typeCollectors.get(transactionType);
        if (intervalTypeCollector == null) {
            return ImmutableList.of();
        }
        List<TransactionErrorSummary> errorSummaries = Lists.newArrayList();
        for (Entry<String, AggregateCollector> entry : intervalTypeCollector.transactionAggregateCollectors
                .entrySet()) {
            AggregateCollector aggregateCollector = entry.getValue();
            synchronized (aggregateCollector) {
                errorSummaries.add(aggregateCollector.getLiveTransactionErrorSummary());
            }
        }
        return errorSummaries;
    }

    public @Nullable OverviewAggregate getLiveOverviewAggregate(String transactionType,
            @Nullable String transactionName, long liveCaptureTime) throws IOException {
        AggregateCollector aggregateCollector =
                getAggregateCollector(transactionType, transactionName);
        if (aggregateCollector == null) {
            return null;
        }
        synchronized (aggregateCollector) {
            long capturedAt = Math.min(liveCaptureTime, captureTime);
            return aggregateCollector.buildLiveOverviewAggregate(capturedAt);
        }
    }

    public @Nullable PercentileAggregate getLivePercentileAggregate(String transactionType,
            @Nullable String transactionName, long liveCaptureTime) throws IOException {
        AggregateCollector aggregateCollector =
                getAggregateCollector(transactionType, transactionName);
        if (aggregateCollector == null) {
            return null;
        }
        synchronized (aggregateCollector) {
            long capturedAt = Math.min(liveCaptureTime, captureTime);
            return aggregateCollector.buildLivePercentileAggregate(capturedAt);
        }
    }

    public @Nullable ThroughputAggregate getLiveThroughputAggregate(String transactionType,
            @Nullable String transactionName, long liveCaptureTime) throws IOException {
        AggregateCollector aggregateCollector =
                getAggregateCollector(transactionType, transactionName);
        if (aggregateCollector == null) {
            return null;
        }
        synchronized (aggregateCollector) {
            long capturedAt = Math.min(liveCaptureTime, captureTime);
            return aggregateCollector.buildLiveThroughputAggregate(capturedAt);
        }
    }

    public @Nullable ErrorPoint getLiveErrorPoint(String transactionType,
            @Nullable String transactionName, long liveCaptureTime) throws IOException {
        AggregateCollector aggregateCollector =
                getAggregateCollector(transactionType, transactionName);
        if (aggregateCollector == null) {
            return null;
        }
        synchronized (aggregateCollector) {
            long capturedAt = Math.min(liveCaptureTime, captureTime);
            return aggregateCollector.buildErrorPoint(capturedAt);
        }
    }

    public List<Aggregate.QueriesByType> getLiveQueries(String transactionType,
            @Nullable String transactionName) throws IOException {
        AggregateCollector aggregateCollector =
                getAggregateCollector(transactionType, transactionName);
        if (aggregateCollector == null) {
            return ImmutableList.of();
        }
        synchronized (aggregateCollector) {
            return aggregateCollector.getLiveQueries();
        }
    }

    public @Nullable ProfileTree getLiveProfile(String transactionType,
            @Nullable String transactionName) throws IOException {
        AggregateCollector aggregateCollector =
                getAggregateCollector(transactionType, transactionName);
        if (aggregateCollector == null) {
            return null;
        }
        synchronized (aggregateCollector) {
            return aggregateCollector.getLiveProfile();
        }
    }

    public void clear() {
        typeCollectors.clear();
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

    private OverallAggregate buildOverallAggregate(String transactionType,
            AggregateCollector aggregateCollector, ScratchBuffer scratchBuffer) throws IOException {
        synchronized (aggregateCollector) {
            return OverallAggregate.newBuilder()
                    .setTransactionType(transactionType)
                    .setAggregate(aggregateCollector.build(scratchBuffer))
                    .build();
        }
    }

    private TransactionAggregate buildTransactionAggregate(String transactionType,
            String transactionName, AggregateCollector aggregateCollector,
            ScratchBuffer scratchBuffer) throws IOException {
        synchronized (aggregateCollector) {
            return TransactionAggregate.newBuilder()
                    .setTransactionType(transactionType)
                    .setTransactionName(transactionName)
                    .setAggregate(aggregateCollector.build(scratchBuffer))
                    .build();
        }
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
            overallAggregateCollector =
                    new AggregateCollector(null, maxAggregateQueriesPerQueryType);
        }

        private void add(Transaction transaction) {
            Profile profile = transaction.getProfile();
            synchronized (overallAggregateCollector) {
                overallAggregateCollector.add(transaction);
                overallAggregateCollector.mergeRootTimer(transaction.getRootTimer());
                overallAggregateCollector.mergeQueries(transaction.getQueries());
                if (profile != null) {
                    overallAggregateCollector.mergeProfile(profile);
                }
            }
            AggregateCollector transactionAggregateCollector =
                    transactionAggregateCollectors.get(transaction.getTransactionName());
            if (transactionAggregateCollector == null && transactionAggregateCollectors
                    .size() < maxAggregateTransactionsPerTransactionType) {
                transactionAggregateCollector = new AggregateCollector(
                        transaction.getTransactionName(), maxAggregateQueriesPerQueryType);
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
            synchronized (transactionAggregateCollector) {
                transactionAggregateCollector.add(transaction);
                transactionAggregateCollector.mergeRootTimer(transaction.getRootTimer());
                transactionAggregateCollector.mergeQueries(transaction.getQueries());
                if (profile != null) {
                    overallAggregateCollector.mergeProfile(profile);
                    transactionAggregateCollector.mergeProfile(profile);
                }
            }
        }
    }
}
