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
package org.glowroot.collector;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.CharSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.ScratchBuffer;
import org.glowroot.config.ConfigService;
import org.glowroot.transaction.model.Profile;
import org.glowroot.transaction.model.Transaction;

public class AggregateIntervalCollector {

    private static final Logger logger = LoggerFactory.getLogger(AggregateIntervalCollector.class);

    private static final AtomicBoolean maxAggregateTransactionsWarnLogged = new AtomicBoolean();

    private final long endTime;
    private final Map<String, IntervalTypeCollector> typeCollectors = Maps.newConcurrentMap();
    private final int maxAggregateTransactionsPerTransactionType;
    private final int maxAggregateQueriesPerQueryType;

    AggregateIntervalCollector(long currentTime, long fixedAggregateIntervalMillis,
            ConfigService configService) {
        endTime = (long) Math.ceil(currentTime / (double) fixedAggregateIntervalMillis)
                * fixedAggregateIntervalMillis;
        this.maxAggregateTransactionsPerTransactionType =
                configService.getAdvancedConfig().maxAggregateTransactionsPerTransactionType();
        this.maxAggregateQueriesPerQueryType =
                configService.getAdvancedConfig().maxAggregateQueriesPerQueryType();
    }

    public long getEndTime() {
        return endTime;
    }

    void add(Transaction transaction) {
        IntervalTypeCollector typeBuilder = getTypeBuilder(transaction.getTransactionType());
        typeBuilder.add(transaction);
    }

    void flush(AggregateRepository aggregateRepository) throws Exception {
        List<Aggregate> overallAggregates = Lists.newArrayList();
        List<Aggregate> transactionAggregates = Lists.newArrayList();
        ScratchBuffer scratchBuffer = new ScratchBuffer();
        for (Entry<String, IntervalTypeCollector> e : typeCollectors.entrySet()) {
            IntervalTypeCollector intervalTypeCollector = e.getValue();
            overallAggregates.add(build(intervalTypeCollector.overallBuilder, scratchBuffer));
            for (Entry<String, AggregateBuilder> f : intervalTypeCollector.transactionBuilders
                    .entrySet()) {
                transactionAggregates.add(build(f.getValue(), scratchBuffer));
            }
        }
        aggregateRepository.store(overallAggregates, transactionAggregates, endTime);
    }

    public @Nullable TransactionSummary getLiveOverallSummary(String transactionType) {
        IntervalTypeCollector intervalTypeCollector = typeCollectors.get(transactionType);
        if (intervalTypeCollector == null) {
            return null;
        }
        AggregateBuilder aggregateBuilder = intervalTypeCollector.overallBuilder;
        synchronized (aggregateBuilder) {
            return aggregateBuilder.getLiveTransactionSummary();
        }
    }

    public List<TransactionSummary> getLiveTransactionSummaries(String transactionType) {
        IntervalTypeCollector intervalTypeCollector = typeCollectors.get(transactionType);
        if (intervalTypeCollector == null) {
            return ImmutableList.of();
        }
        List<TransactionSummary> transactionSummaries = Lists.newArrayList();
        for (Entry<String, AggregateBuilder> entry : intervalTypeCollector.transactionBuilders
                .entrySet()) {
            AggregateBuilder aggregateBuilder = entry.getValue();
            synchronized (aggregateBuilder) {
                transactionSummaries.add(aggregateBuilder.getLiveTransactionSummary());
            }
        }
        return transactionSummaries;
    }

    public @Nullable ErrorSummary getLiveOverallErrorSummary(String transactionType) {
        IntervalTypeCollector intervalTypeCollector = typeCollectors.get(transactionType);
        if (intervalTypeCollector == null) {
            return null;
        }
        AggregateBuilder aggregateBuilder = intervalTypeCollector.overallBuilder;
        synchronized (aggregateBuilder) {
            return aggregateBuilder.getLiveErrorSummary();
        }
    }

    public List<ErrorSummary> getLiveTransactionErrorSummaries(String transactionType) {
        IntervalTypeCollector intervalTypeCollector = typeCollectors.get(transactionType);
        if (intervalTypeCollector == null) {
            return ImmutableList.of();
        }
        List<ErrorSummary> errorSummaries = Lists.newArrayList();
        for (Entry<String, AggregateBuilder> entry : intervalTypeCollector.transactionBuilders
                .entrySet()) {
            AggregateBuilder aggregateBuilder = entry.getValue();
            synchronized (aggregateBuilder) {
                errorSummaries.add(aggregateBuilder.getLiveErrorSummary());
            }
        }
        return errorSummaries;
    }

    public @Nullable Aggregate getLiveAggregate(String transactionType,
            @Nullable String transactionName, long liveCaptureTime) throws IOException {
        AggregateBuilder aggregateBuilder = getAggregateBuilder(transactionType, transactionName);
        if (aggregateBuilder == null) {
            return null;
        }
        synchronized (aggregateBuilder) {
            long capturedAt = Math.min(liveCaptureTime, endTime);
            return aggregateBuilder.build(capturedAt, new ScratchBuffer());
        }
    }

    public @Nullable ErrorPoint getLiveErrorPoint(String transactionType,
            @Nullable String transactionName, long liveCaptureTime) throws IOException {
        AggregateBuilder aggregateBuilder = getAggregateBuilder(transactionType, transactionName);
        if (aggregateBuilder == null) {
            return null;
        }
        synchronized (aggregateBuilder) {
            long capturedAt = Math.min(liveCaptureTime, endTime);
            return aggregateBuilder.buildErrorPoint(capturedAt);
        }
    }

    public @Nullable QueryAggregate getLiveQueryAggregate(String transactionType,
            @Nullable String transactionName) throws IOException {
        AggregateBuilder aggregateBuilder = getAggregateBuilder(transactionType, transactionName);
        if (aggregateBuilder == null) {
            return null;
        }
        String queriesJson;
        synchronized (aggregateBuilder) {
            queriesJson = aggregateBuilder.getQueriesJson();
        }
        if (queriesJson == null) {
            return null;
        }
        return QueryAggregate.of(endTime, CharSource.wrap(queriesJson));
    }

    public @Nullable ProfileAggregate getLiveProfileAggregate(String transactionType,
            @Nullable String transactionName) throws IOException {
        AggregateBuilder aggregateBuilder = getAggregateBuilder(transactionType, transactionName);
        if (aggregateBuilder == null) {
            return null;
        }
        String profileJson;
        synchronized (aggregateBuilder) {
            profileJson = aggregateBuilder.getProfileJson();
        }
        if (profileJson == null) {
            return null;
        }
        return ProfileAggregate.of(endTime, CharSource.wrap(profileJson));
    }

    void clear() {
        typeCollectors.clear();
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

    private Aggregate build(AggregateBuilder aggregateBuilder, ScratchBuffer scratchBuffer)
            throws IOException {
        synchronized (aggregateBuilder) {
            return aggregateBuilder.build(endTime, scratchBuffer);
        }
    }

    private @Nullable AggregateBuilder getAggregateBuilder(String transactionType,
            @Nullable String transactionName) {
        IntervalTypeCollector intervalTypeCollector = typeCollectors.get(transactionType);
        if (intervalTypeCollector == null) {
            return null;
        }
        if (transactionName == null) {
            return intervalTypeCollector.overallBuilder;
        } else {
            return intervalTypeCollector.transactionBuilders.get(transactionName);
        }
    }

    private class IntervalTypeCollector {

        private final String transactionType;
        private final AggregateBuilder overallBuilder;
        private final Map<String, AggregateBuilder> transactionBuilders = Maps.newConcurrentMap();

        private IntervalTypeCollector(String transactionType) {
            this.transactionType = transactionType;
            overallBuilder =
                    new AggregateBuilder(transactionType, null, maxAggregateQueriesPerQueryType);
        }

        private void add(Transaction transaction) {
            Profile profile = transaction.getProfile();
            synchronized (overallBuilder) {
                overallBuilder.add(transaction);
                overallBuilder.addToTimers(transaction.getRootTimer());
                overallBuilder.addToQueries(transaction.getQueries());
                if (profile != null) {
                    overallBuilder.addToProfile(profile);
                }
            }
            AggregateBuilder transactionBuilder =
                    transactionBuilders.get(transaction.getTransactionName());
            if (transactionBuilder == null
                    && transactionBuilders.size() < maxAggregateTransactionsPerTransactionType) {
                transactionBuilder = new AggregateBuilder(transactionType,
                        transaction.getTransactionName(), maxAggregateQueriesPerQueryType);
                transactionBuilders.put(transaction.getTransactionName(), transactionBuilder);
            }
            if (transactionBuilder == null) {
                if (!maxAggregateTransactionsWarnLogged.getAndSet(true)) {
                    logger.warn("the max transaction names per transaction type was exceeded"
                            + " during the current interval. consider increasing the limit under"
                            + " Configuration > Advanced, or reducing the number of transaction"
                            + " names by configuring instrumentation points under Configuration"
                            + " > Instrumentation that override the transaction name.");
                }
                return;
            }
            synchronized (transactionBuilder) {
                transactionBuilder.add(transaction);
                transactionBuilder.addToTimers(transaction.getRootTimer());
                transactionBuilder.addToQueries(transaction.getQueries());
                if (profile != null) {
                    overallBuilder.addToProfile(profile);
                    transactionBuilder.addToProfile(profile);
                }
            }
        }
    }
}
