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

import javax.annotation.Nullable;

import com.google.common.collect.Lists;

import org.glowroot.agent.impl.Aggregator;
import org.glowroot.agent.model.AggregateIntervalCollector;
import org.glowroot.common.live.LiveAggregateRepository;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.ProfileTreeOuterClass.ProfileTree;

public class LiveAggregateRepositoryImpl implements LiveAggregateRepository {

    private final Aggregator aggregator;

    public LiveAggregateRepositoryImpl(Aggregator aggregator) {
        this.aggregator = aggregator;
    }

    // from is non-inclusive
    @Override
    public @Nullable LiveResult<OverallSummary> getLiveOverallSummary(String server,
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
            String server, final String transactionType, long from, long to) throws Exception {
        return map(from, to, new Mapper<List<TransactionSummary>>() {
            @Override
            public List<TransactionSummary> map(AggregateIntervalCollector collector) {
                return collector.getLiveTransactionSummaries(transactionType);
            }
        });
    }

    @Override
    public @Nullable LiveResult<OverviewAggregate> getLiveOverviewAggregates(String server,
            final String transactionType, final @Nullable String transactionName, long from,
            long to, final long liveCaptureTime) throws Exception {
        return map(from, to, new Mapper<OverviewAggregate>() {
            @Override
            public @Nullable OverviewAggregate map(AggregateIntervalCollector collector)
                    throws Exception {
                return collector.getLiveOverviewAggregate(transactionType, transactionName,
                        liveCaptureTime);
            }
        });
    }

    @Override
    public @Nullable LiveResult<PercentileAggregate> getLivePercentileAggregates(String server,
            final String transactionType, final @Nullable String transactionName, long from,
            long to, final long liveCaptureTime) throws Exception {
        return map(from, to, new Mapper<PercentileAggregate>() {
            @Override
            public @Nullable PercentileAggregate map(AggregateIntervalCollector collector)
                    throws Exception {
                return collector.getLivePercentileAggregate(transactionType, transactionName,
                        liveCaptureTime);
            }
        });
    }

    @Override
    public @Nullable LiveResult<ProfileTree> getLiveProfileTree(String server,
            final String transactionType, final @Nullable String transactionName, long from,
            long to) throws Exception {
        return map(from, to, new Mapper<ProfileTree>() {
            @Override
            public @Nullable ProfileTree map(AggregateIntervalCollector collector)
                    throws Exception {
                return collector.getLiveProfile(transactionType, transactionName);
            }
        });
    }

    @Override
    public @Nullable LiveResult<List<Aggregate.QueriesByType>> getLiveQueries(String server,
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

    @Override
    public @Nullable LiveResult<OverallErrorSummary> getLiveOverallErrorSummary(String server,
            final String transactionType, long from, long to) throws Exception {
        return map(from, to, new Mapper<OverallErrorSummary>() {
            @Override
            public @Nullable OverallErrorSummary map(AggregateIntervalCollector collector) {
                return collector.getLiveOverallErrorSummary(transactionType);
            }
        });
    }

    @Override
    public @Nullable LiveResult<List<TransactionErrorSummary>> getLiveTransactionErrorSummaries(
            String server, final String transactionType, long from, long to) throws Exception {
        return map(from, to, new Mapper<List<TransactionErrorSummary>>() {
            @Override
            public List<TransactionErrorSummary> map(AggregateIntervalCollector collector) {
                return collector.getLiveTransactionErrorSummaries(transactionType);
            }
        });
    }

    @Override
    public @Nullable LiveResult<ErrorPoint> getLiveErrorPoints(String server,
            final String transactionType, final @Nullable String transactionName, long from,
            long to, final long liveCaptureTime) throws Exception {
        return map(from, to, new Mapper<ErrorPoint>() {
            @Override
            public @Nullable ErrorPoint map(AggregateIntervalCollector collector) throws Exception {
                return collector.getLiveErrorPoint(transactionType, transactionName,
                        liveCaptureTime);
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
