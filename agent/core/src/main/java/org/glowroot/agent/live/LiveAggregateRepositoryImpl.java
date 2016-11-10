/*
 * Copyright 2016 the original author or authors.
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

import java.io.IOException;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.collect.Lists;

import org.glowroot.agent.impl.AggregateIntervalCollector;
import org.glowroot.agent.impl.Aggregator;
import org.glowroot.common.live.LiveAggregateRepository;
import org.glowroot.common.model.OverallErrorSummaryCollector;
import org.glowroot.common.model.OverallSummaryCollector;
import org.glowroot.common.model.ProfileCollector;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.common.model.ServiceCallCollector;
import org.glowroot.common.model.TransactionErrorSummaryCollector;
import org.glowroot.common.model.TransactionSummaryCollector;

public class LiveAggregateRepositoryImpl implements LiveAggregateRepository {

    private final Aggregator aggregator;

    public LiveAggregateRepositoryImpl(Aggregator aggregator) {
        this.aggregator = aggregator;
    }

    @Override
    public long mergeInOverallSummary(String agentId, OverallQuery query,
            OverallSummaryCollector collector) {
        List<AggregateIntervalCollector> intervalCollectors =
                aggregator.getOrderedIntervalCollectorsInRange(query.from(), query.to());
        long revisedTo = query.to();
        for (AggregateIntervalCollector intervalCollector : intervalCollectors) {
            intervalCollector.mergeOverallSummaryInto(collector, query.transactionType());
            revisedTo = Math.min(revisedTo, intervalCollector.getCaptureTime() - 1);
        }
        return revisedTo;
    }

    @Override
    public long mergeInTransactionSummaries(String agentId, OverallQuery query,
            TransactionSummaryCollector collector) {
        List<AggregateIntervalCollector> intervalCollectors =
                aggregator.getOrderedIntervalCollectorsInRange(query.from(), query.to());
        long revisedTo = query.to();
        for (AggregateIntervalCollector intervalCollector : intervalCollectors) {
            intervalCollector.mergeTransactionSummariesInto(collector, query.transactionType());
            revisedTo = Math.min(revisedTo, intervalCollector.getCaptureTime() - 1);
        }
        return revisedTo;
    }

    @Override
    public long mergeInOverallErrorSummary(String agentId, OverallQuery query,
            OverallErrorSummaryCollector collector) {
        List<AggregateIntervalCollector> intervalCollectors =
                aggregator.getOrderedIntervalCollectorsInRange(query.from(), query.to());
        long revisedTo = query.to();
        for (AggregateIntervalCollector intervalCollector : intervalCollectors) {
            intervalCollector.mergeOverallErrorSummaryInto(collector, query.transactionType());
            revisedTo = Math.min(revisedTo, intervalCollector.getCaptureTime() - 1);
        }
        return revisedTo;
    }

    @Override
    public long mergeInTransactionErrorSummaries(String agentId, OverallQuery query,
            TransactionErrorSummaryCollector collector) {
        List<AggregateIntervalCollector> intervalCollectors =
                aggregator.getOrderedIntervalCollectorsInRange(query.from(), query.to());
        long revisedTo = query.to();
        for (AggregateIntervalCollector intervalCollector : intervalCollectors) {
            intervalCollector.mergeTransactionErrorSummariesInto(collector,
                    query.transactionType());
            revisedTo = Math.min(revisedTo, intervalCollector.getCaptureTime() - 1);
        }
        return revisedTo;
    }

    @Override
    public @Nullable LiveResult<OverviewAggregate> getOverviewAggregates(String agentId,
            TransactionQuery query) {
        List<AggregateIntervalCollector> intervalCollectors =
                aggregator.getOrderedIntervalCollectorsInRange(query.from(), query.to());
        if (intervalCollectors.isEmpty()) {
            return null;
        }
        List<OverviewAggregate> overviewAggregates = Lists.newArrayList();
        long revisedTo = query.to();
        for (AggregateIntervalCollector intervalCollector : intervalCollectors) {
            OverviewAggregate overviewAggregate = intervalCollector
                    .getOverviewAggregate(query.transactionType(), query.transactionName());
            if (overviewAggregate != null) {
                overviewAggregates.add(overviewAggregate);
            }
            revisedTo = Math.min(revisedTo, intervalCollector.getCaptureTime() - 1);
        }
        return new LiveResult<OverviewAggregate>(overviewAggregates, revisedTo);
    }

    @Override
    public @Nullable LiveResult<PercentileAggregate> getPercentileAggregates(String agentId,
            TransactionQuery query) {
        List<AggregateIntervalCollector> intervalCollectors =
                aggregator.getOrderedIntervalCollectorsInRange(query.from(), query.to());
        if (intervalCollectors.isEmpty()) {
            return null;
        }
        List<PercentileAggregate> percentileAggregates = Lists.newArrayList();
        long revisedTo = query.to();
        for (AggregateIntervalCollector intervalCollector : intervalCollectors) {
            PercentileAggregate percentileAggregate = intervalCollector
                    .getPercentileAggregate(query.transactionType(), query.transactionName());
            if (percentileAggregate != null) {
                percentileAggregates.add(percentileAggregate);
            }
            revisedTo = Math.min(revisedTo, intervalCollector.getCaptureTime() - 1);
        }
        return new LiveResult<PercentileAggregate>(percentileAggregates, revisedTo);
    }

    @Override
    public @Nullable LiveResult<ThroughputAggregate> getThroughputAggregates(String agentId,
            TransactionQuery query) {
        List<AggregateIntervalCollector> intervalCollectors =
                aggregator.getOrderedIntervalCollectorsInRange(query.from(), query.to());
        if (intervalCollectors.isEmpty()) {
            return null;
        }
        List<ThroughputAggregate> throughputAggregates = Lists.newArrayList();
        long revisedTo = query.to();
        for (AggregateIntervalCollector intervalCollector : intervalCollectors) {
            ThroughputAggregate throughputAggregate = intervalCollector
                    .getThroughputAggregate(query.transactionType(), query.transactionName());
            if (throughputAggregate != null) {
                throughputAggregates.add(throughputAggregate);
            }
            revisedTo = Math.min(revisedTo, intervalCollector.getCaptureTime() - 1);
        }
        return new LiveResult<ThroughputAggregate>(throughputAggregates, revisedTo);
    }

    @Override
    public @Nullable String getFullQueryText(String agentRollupId, String fullQueryTextSha1) {
        List<AggregateIntervalCollector> intervalCollectors =
                aggregator.getOrderedIntervalCollectorsInRange(0, Long.MAX_VALUE);
        for (AggregateIntervalCollector intervalCollector : intervalCollectors) {
            String fullQueryText = intervalCollector.getFullQueryText(fullQueryTextSha1);
            if (fullQueryText != null) {
                return fullQueryText;
            }
        }
        return null;
    }

    @Override
    public long mergeInQueries(String agentId, TransactionQuery query, QueryCollector collector)
            throws IOException {
        List<AggregateIntervalCollector> intervalCollectors =
                aggregator.getOrderedIntervalCollectorsInRange(query.from(), query.to());
        long revisedTo = query.to();
        for (AggregateIntervalCollector intervalCollector : intervalCollectors) {
            intervalCollector.mergeQueriesInto(collector, query.transactionType(),
                    query.transactionName());
            revisedTo = Math.min(revisedTo, intervalCollector.getCaptureTime() - 1);
        }
        return revisedTo;
    }

    @Override
    public long mergeInServiceCalls(String agentId, TransactionQuery query,
            ServiceCallCollector collector)
            throws IOException {
        List<AggregateIntervalCollector> intervalCollectors =
                aggregator.getOrderedIntervalCollectorsInRange(query.from(), query.to());
        long revisedTo = query.to();
        for (AggregateIntervalCollector intervalCollector : intervalCollectors) {
            intervalCollector.mergeServiceCallsInto(collector, query.transactionType(),
                    query.transactionName());
            revisedTo = Math.min(revisedTo, intervalCollector.getCaptureTime() - 1);
        }
        return revisedTo;
    }

    @Override
    public long mergeInMainThreadProfiles(String agentId, TransactionQuery query,
            ProfileCollector collector) {
        List<AggregateIntervalCollector> intervalCollectors =
                aggregator.getOrderedIntervalCollectorsInRange(query.from(), query.to());
        long revisedTo = query.to();
        for (AggregateIntervalCollector intervalCollector : intervalCollectors) {
            intervalCollector.mergeMainThreadProfilesInto(collector, query.transactionType(),
                    query.transactionName());
            revisedTo = Math.min(revisedTo, intervalCollector.getCaptureTime() - 1);
        }
        return revisedTo;
    }

    @Override
    public long mergeInAuxThreadProfiles(String agentId, TransactionQuery query,
            ProfileCollector collector) {
        List<AggregateIntervalCollector> intervalCollectors =
                aggregator.getOrderedIntervalCollectorsInRange(query.from(), query.to());
        long revisedTo = query.to();
        for (AggregateIntervalCollector intervalCollector : intervalCollectors) {
            intervalCollector.mergeAuxThreadProfilesInto(collector, query.transactionType(),
                    query.transactionName());
            revisedTo = Math.min(revisedTo, intervalCollector.getCaptureTime() - 1);
        }
        return revisedTo;
    }

    @Override
    public void clearInMemoryAggregate() {
        aggregator.clearInMemoryAggregate();
    }
}
