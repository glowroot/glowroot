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
package org.glowroot.agent.live;

import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.collect.Lists;

import org.glowroot.agent.impl.AggregateIntervalCollector;
import org.glowroot.agent.impl.Aggregator;
import org.glowroot.common.live.LiveAggregateRepository;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;

public class LiveAggregateRepositoryImpl implements LiveAggregateRepository {

    private final Aggregator aggregator;

    public LiveAggregateRepositoryImpl(Aggregator aggregator) {
        this.aggregator = aggregator;
    }

    @Override
    public Set<String> getLiveTransactionTypes(String serverId) {
        return aggregator.getLiveTransactionTypes();
    }

    // from is non-inclusive
    @Override
    public @Nullable LiveResult<OverallSummary> getLiveOverallSummary(
            final String transactionType, long from, long to) throws Exception {
        return map(from, to, new Mapper<OverallSummary>() {
            @Override
            public @Nullable OverallSummary map(AggregateIntervalCollector collector) {
                return collector.getLiveOverallSummary(transactionType);
            }
        });
    }

    // from is non-inclusive
    @Override
    public @Nullable LiveResult<List<TransactionSummary>> getLiveTransactionSummaries(
            final String transactionType, long from, long to) throws Exception {
        return map(from, to, new Mapper<List<TransactionSummary>>() {
            @Override
            public List<TransactionSummary> map(AggregateIntervalCollector collector) {
                return collector.getLiveTransactionSummaries(transactionType);
            }
        });
    }

    // from is INCLUSIVE
    @Override
    public @Nullable LiveResult<OverviewAggregate> getLiveOverviewAggregates(
            final String transactionType, final @Nullable String transactionName, long from,
            long to, final long liveCaptureTime) throws Exception {
        return map(from - 1, to, new Mapper<OverviewAggregate>() {
            @Override
            public @Nullable OverviewAggregate map(AggregateIntervalCollector collector)
                    throws Exception {
                return collector.getLiveOverviewAggregate(transactionType, transactionName,
                        liveCaptureTime);
            }
        });
    }

    // from is INCLUSIVE
    @Override
    public @Nullable LiveResult<PercentileAggregate> getLivePercentileAggregates(
            final String transactionType, final @Nullable String transactionName, long from,
            long to, final long liveCaptureTime) throws Exception {
        return map(from - 1, to, new Mapper<PercentileAggregate>() {
            @Override
            public @Nullable PercentileAggregate map(AggregateIntervalCollector collector)
                    throws Exception {
                return collector.getLivePercentileAggregate(transactionType, transactionName,
                        liveCaptureTime);
            }
        });
    }

    // from is INCLUSIVE
    @Override
    public @Nullable LiveResult<ThroughputAggregate> getLiveThroughputAggregates(
            final String transactionType, final @Nullable String transactionName, long from,
            long to, final long liveCaptureTime) throws Exception {
        return map(from - 1, to, new Mapper<ThroughputAggregate>() {
            @Override
            public @Nullable ThroughputAggregate map(AggregateIntervalCollector collector)
                    throws Exception {
                return collector.getLiveThroughputAggregate(transactionType, transactionName,
                        liveCaptureTime);
            }
        });
    }

    // from is non-inclusive
    @Override
    public @Nullable LiveResult<Profile> getLiveProfile(final String transactionType,
            final @Nullable String transactionName, long from, long to) throws Exception {
        return map(from, to, new Mapper<Profile>() {
            @Override
            public @Nullable Profile map(AggregateIntervalCollector collector)
                    throws Exception {
                return collector.getLiveProfile(transactionType, transactionName);
            }
        });
    }

    // from is non-inclusive
    @Override
    public @Nullable LiveResult<List<Aggregate.QueriesByType>> getLiveQueries(
            final String transactionType, final @Nullable String transactionName, long from,
            long to) throws Exception {
        return map(from, to, new Mapper<List<Aggregate.QueriesByType>>() {
            @Override
            public List<Aggregate.QueriesByType> map(AggregateIntervalCollector collector)
                    throws Exception {
                return collector.getLiveQueries(transactionType, transactionName);
            }
        });
    }

    // from is non-inclusive
    @Override
    public @Nullable LiveResult<OverallErrorSummary> getLiveOverallErrorSummary(
            final String transactionType, long from, long to) throws Exception {
        return map(from, to, new Mapper<OverallErrorSummary>() {
            @Override
            public @Nullable OverallErrorSummary map(AggregateIntervalCollector collector) {
                return collector.getLiveOverallErrorSummary(transactionType);
            }
        });
    }

    // from is non-inclusive
    @Override
    public @Nullable LiveResult<List<TransactionErrorSummary>> getLiveTransactionErrorSummaries(
            final String transactionType, long from, long to) throws Exception {
        return map(from, to, new Mapper<List<TransactionErrorSummary>>() {
            @Override
            public List<TransactionErrorSummary> map(AggregateIntervalCollector collector) {
                return collector.getLiveTransactionErrorSummaries(transactionType);
            }
        });
    }

    @Override
    public void clearAll() {
        aggregator.clearAll();
    }

    @Nullable
    private <T> LiveResult<T> map(long from, long to, Mapper<T> mapper) throws Exception {
        List<AggregateIntervalCollector> collectors =
                aggregator.getOrderedIntervalCollectorsInRange(from, to);
        if (collectors.isEmpty()) {
            return null;
        }
        long initialCaptureTime = collectors.get(0).getCaptureTime();
        List<T> list = Lists.newArrayList();
        for (AggregateIntervalCollector collector : collectors) {
            T item = mapper.map(collector);
            if (item == null) {
                continue;
            }
            if (item instanceof List && ((List<?>) item).isEmpty()) {
                continue;
            }
            list.add(item);
        }
        if (list.isEmpty()) {
            return null;
        }
        return new LiveResult<T>(list, initialCaptureTime);
    }

    private interface Mapper<T> {
        @Nullable
        T map(AggregateIntervalCollector collector) throws Exception;
    }
}
