/*
 * Copyright 2016-2017 the original author or authors.
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

import javax.annotation.Nullable;

import org.immutables.value.Value;

import org.glowroot.common.model.OverallErrorSummaryCollector;
import org.glowroot.common.model.OverallSummaryCollector;
import org.glowroot.common.model.ProfileCollector;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.common.model.ServiceCallCollector;
import org.glowroot.common.model.TransactionErrorSummaryCollector;
import org.glowroot.common.model.TransactionSummaryCollector;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;

public interface LiveAggregateRepository {

    long mergeInOverallSummary(String agentId, OverallQuery query,
            OverallSummaryCollector collector);

    long mergeInTransactionSummaries(String agentId, OverallQuery query,
            TransactionSummaryCollector collector);

    long mergeInOverallErrorSummary(String agentId, OverallQuery query,
            OverallErrorSummaryCollector collector);

    long mergeInTransactionErrorSummaries(String agentId, OverallQuery query,
            TransactionErrorSummaryCollector collector);

    @Nullable
    LiveResult<OverviewAggregate> getOverviewAggregates(String agentId, TransactionQuery query);

    @Nullable
    LiveResult<PercentileAggregate> getPercentileAggregates(String agentId, TransactionQuery query);

    @Nullable
    LiveResult<ThroughputAggregate> getThroughputAggregates(String agentId, TransactionQuery query);

    @Nullable
    String getFullQueryText(String agentRollupId, String fullQueryTextSha1);

    long mergeInQueries(String agentId, TransactionQuery query, QueryCollector collector)
            throws IOException;

    long mergeInServiceCalls(String agentId, TransactionQuery query, ServiceCallCollector collector)
            throws IOException;

    long mergeInMainThreadProfiles(String agentId, TransactionQuery query,
            ProfileCollector collector);

    long mergeInAuxThreadProfiles(String agentId, TransactionQuery query,
            ProfileCollector collector);

    void clearInMemoryAggregate();

    @Value.Immutable
    public interface OverallQuery {
        String transactionType();
        long from();
        long to();
        int rollupLevel();
    }

    @Value.Immutable
    public interface TransactionQuery {
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
        @Nullable
        Aggregate.ThreadStats mainThreadStats();
        @Nullable
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
        public long mergeInOverallSummary(String agentId, OverallQuery query,
                OverallSummaryCollector collector) {
            return query.to();
        }

        @Override
        public long mergeInTransactionSummaries(String agentId, OverallQuery query,
                TransactionSummaryCollector collector) {
            return query.to();
        }

        @Override
        public long mergeInOverallErrorSummary(String agentId, OverallQuery query,
                OverallErrorSummaryCollector collector) {
            return query.to();
        }

        @Override
        public long mergeInTransactionErrorSummaries(String agentId, OverallQuery query,
                TransactionErrorSummaryCollector collector) {
            return query.to();
        }

        @Override
        public @Nullable LiveResult<OverviewAggregate> getOverviewAggregates(String agentId,
                TransactionQuery query) {
            return null;
        }

        @Override
        public @Nullable LiveResult<PercentileAggregate> getPercentileAggregates(String agentId,
                TransactionQuery query) {
            return null;
        }

        @Override
        public @Nullable LiveResult<ThroughputAggregate> getThroughputAggregates(String agentId,
                TransactionQuery query) {
            return null;
        }

        @Override
        public @Nullable String getFullQueryText(String agentRollupId, String fullQueryTextSha1) {
            return null;
        }

        @Override
        public long mergeInQueries(String agentId, TransactionQuery query,
                QueryCollector collector) {
            return query.to();
        }

        @Override
        public long mergeInServiceCalls(String agentId, TransactionQuery query,
                ServiceCallCollector collector) {
            return query.to();
        }

        @Override
        public long mergeInMainThreadProfiles(String agentId, TransactionQuery query,
                ProfileCollector collector) {
            return query.to();
        }

        @Override
        public long mergeInAuxThreadProfiles(String agentId, TransactionQuery query,
                ProfileCollector collector) {
            return query.to();
        }

        @Override
        public void clearInMemoryAggregate() {}
    }
}
