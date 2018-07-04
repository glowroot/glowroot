/*
 * Copyright 2016-2018 the original author or authors.
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
package org.glowroot.common.live;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import com.google.common.collect.Sets;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.immutables.value.Value;

import org.glowroot.common.model.OverallErrorSummaryCollector;
import org.glowroot.common.model.OverallSummaryCollector;
import org.glowroot.common.model.ProfileCollector;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.common.model.ServiceCallCollector;
import org.glowroot.common.model.TransactionNameErrorSummaryCollector;
import org.glowroot.common.model.TransactionNameSummaryCollector;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;

public interface LiveAggregateRepository {

    long mergeInOverallSummary(String agentId, SummaryQuery query,
            OverallSummaryCollector collector);

    long mergeInTransactionNameSummaries(String agentId, SummaryQuery query,
            TransactionNameSummaryCollector collector);

    long mergeInOverallErrorSummary(String agentId, SummaryQuery query,
            OverallErrorSummaryCollector collector);

    long mergeInTransactionNameErrorSummaries(String agentId, SummaryQuery query,
            TransactionNameErrorSummaryCollector collector);

    Set<String> getTransactionTypes(String agentId);

    @Nullable
    LiveResult<OverviewAggregate> getOverviewAggregates(String agentId, AggregateQuery query);

    @Nullable
    LiveResult<PercentileAggregate> getPercentileAggregates(String agentId, AggregateQuery query);

    @Nullable
    LiveResult<ThroughputAggregate> getThroughputAggregates(String agentId, AggregateQuery query);

    @Nullable
    String getFullQueryText(String agentRollupId, String fullQueryTextSha1);

    long mergeInQueries(String agentId, AggregateQuery query, QueryCollector collector)
            throws IOException;

    long mergeInServiceCalls(String agentId, AggregateQuery query, ServiceCallCollector collector)
            throws IOException;

    long mergeInMainThreadProfiles(String agentId, AggregateQuery query,
            ProfileCollector collector);

    long mergeInAuxThreadProfiles(String agentId, AggregateQuery query, ProfileCollector collector);

    void clearInMemoryData();

    @Value.Immutable
    public interface SummaryQuery {
        String transactionType();
        long from();
        long to();
        int rollupLevel();
    }

    @Value.Immutable
    public interface AggregateQuery {
        String transactionType();
        @Nullable
        String transactionName();
        long from();
        long to();
        int rollupLevel();
    }

    @Value.Immutable
    public interface OverviewAggregate {
        long captureTime();
        // aggregates use double instead of long to avoid (unlikely) 292 year nanosecond rollover
        double totalDurationNanos();
        long transactionCount();
        boolean asyncTransactions();
        List<Aggregate.Timer> mainThreadRootTimers();
        List<Aggregate.Timer> auxThreadRootTimers();
        List<Aggregate.Timer> asyncTimers();
        Aggregate.ThreadStats mainThreadStats();
        Aggregate.ThreadStats auxThreadStats();
    }

    @Value.Immutable
    public interface PercentileAggregate {
        long captureTime();
        // aggregates use double instead of long to avoid (unlikely) 292 year nanosecond rollover
        double totalDurationNanos();
        long transactionCount();
        Aggregate.Histogram durationNanosHistogram();
    }

    @Value.Immutable
    public interface ThroughputAggregate {
        long captureTime();
        long transactionCount();
        @Nullable
        Long errorCount(); // null for data inserted prior to glowroot central 0.9.18
    }

    public class LiveResult<T> {

        private final List<T> result;
        private final long revisedTo;

        public LiveResult(List<T> result, long revisedTo) {
            this.result = result;
            this.revisedTo = revisedTo;
        }

        public List<T> get() {
            return result;
        }

        public long revisedTo() {
            return revisedTo;
        }
    }

    public static class LiveAggregateRepositoryNop implements LiveAggregateRepository {

        @Override
        public long mergeInOverallSummary(String agentId, SummaryQuery query,
                OverallSummaryCollector collector) {
            return query.to();
        }

        @Override
        public long mergeInTransactionNameSummaries(String agentId, SummaryQuery query,
                TransactionNameSummaryCollector collector) {
            return query.to();
        }

        @Override
        public long mergeInOverallErrorSummary(String agentId, SummaryQuery query,
                OverallErrorSummaryCollector collector) {
            return query.to();
        }

        @Override
        public long mergeInTransactionNameErrorSummaries(String agentId, SummaryQuery query,
                TransactionNameErrorSummaryCollector collector) {
            return query.to();
        }

        @Override
        public Set<String> getTransactionTypes(String agentId) {
            return Sets.newHashSet();
        }

        @Override
        public @Nullable LiveResult<OverviewAggregate> getOverviewAggregates(String agentId,
                AggregateQuery query) {
            return null;
        }

        @Override
        public @Nullable LiveResult<PercentileAggregate> getPercentileAggregates(String agentId,
                AggregateQuery query) {
            return null;
        }

        @Override
        public @Nullable LiveResult<ThroughputAggregate> getThroughputAggregates(String agentId,
                AggregateQuery query) {
            return null;
        }

        @Override
        public @Nullable String getFullQueryText(String agentRollupId, String fullQueryTextSha1) {
            return null;
        }

        @Override
        public long mergeInQueries(String agentId, AggregateQuery query,
                QueryCollector collector) {
            return query.to();
        }

        @Override
        public long mergeInServiceCalls(String agentId, AggregateQuery query,
                ServiceCallCollector collector) {
            return query.to();
        }

        @Override
        public long mergeInMainThreadProfiles(String agentId, AggregateQuery query,
                ProfileCollector collector) {
            return query.to();
        }

        @Override
        public long mergeInAuxThreadProfiles(String agentId, AggregateQuery query,
                ProfileCollector collector) {
            return query.to();
        }

        @Override
        public void clearInMemoryData() {}
    }
}
