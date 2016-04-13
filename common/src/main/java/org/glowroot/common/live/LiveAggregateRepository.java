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
import org.glowroot.common.util.Styles;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;

public interface LiveAggregateRepository {

    long mergeInOverallSummary(OverallSummaryCollector collector, OverallQuery query);

    long mergeInTransactionSummaries(TransactionSummaryCollector collector, OverallQuery query);

    long mergeInOverallErrorSummary(OverallErrorSummaryCollector collector, OverallQuery query);

    long mergeInTransactionErrorSummaries(TransactionErrorSummaryCollector collector,
            OverallQuery query);

    @Nullable
    LiveResult<OverviewAggregate> getOverviewAggregates(TransactionQuery query);

    @Nullable
    LiveResult<PercentileAggregate> getPercentileAggregates(TransactionQuery query);

    @Nullable
    LiveResult<ThroughputAggregate> getThroughputAggregates(TransactionQuery query);

    long mergeInQueries(QueryCollector collector, TransactionQuery query) throws IOException;

    long mergeInServiceCalls(ServiceCallCollector collector, TransactionQuery query)
            throws IOException;

    long mergeInMainThreadProfiles(ProfileCollector collector, TransactionQuery query);

    long mergeInAuxThreadProfiles(ProfileCollector collector, TransactionQuery query);

    @Value.Immutable
    public interface OverallQuery {
        String agentRollup();
        String transactionType();
        long from();
        long to();
        int rollupLevel();
    }

    @Value.Immutable
    public interface TransactionQuery {
        String agentRollup();
        String transactionType();
        @Nullable
        String transactionName();
        long from();
        long to();
        int rollupLevel();
    }

    @Value.Immutable
    public interface OverallSummary {
        // aggregates use double instead of long to avoid (unlikely) 292 year nanosecond rollover
        double totalDurationNanos();
        long transactionCount();
    }

    @Value.Immutable
    public interface TransactionSummary {
        String transactionName();
        // aggregates use double instead of long to avoid (unlikely) 292 year nanosecond rollover
        double totalDurationNanos();
        long transactionCount();
    }

    @Value.Immutable
    public interface OverallErrorSummary {
        long errorCount();
        long transactionCount();
    }

    @Value.Immutable
    public interface TransactionErrorSummary {
        String transactionName();
        long errorCount();
        long transactionCount();
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
        List<Aggregate.Timer> asyncRootTimers();
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
    @Styles.AllParameters
    public interface ThroughputAggregate {
        long captureTime();
        long transactionCount();
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

    public enum SummarySortOrder {
        TOTAL_TIME, AVERAGE_TIME, THROUGHPUT
    }

    public enum ErrorSummarySortOrder {
        ERROR_COUNT, ERROR_RATE
    }

    public static class LiveAggregateRepositoryNop implements LiveAggregateRepository {

        @Override
        public long mergeInOverallSummary(OverallSummaryCollector collector, OverallQuery query) {
            return query.to();
        }

        @Override
        public long mergeInTransactionSummaries(TransactionSummaryCollector collector,
                OverallQuery query) {
            return query.to();
        }

        @Override
        public long mergeInOverallErrorSummary(OverallErrorSummaryCollector collector,
                OverallQuery query) {
            return query.to();
        }

        @Override
        public long mergeInTransactionErrorSummaries(TransactionErrorSummaryCollector collector,
                OverallQuery query) {
            return query.to();
        }

        @Override
        public @Nullable LiveResult<OverviewAggregate> getOverviewAggregates(
                TransactionQuery query) {
            return null;
        }

        @Override
        public @Nullable LiveResult<PercentileAggregate> getPercentileAggregates(
                TransactionQuery query) {
            return null;
        }

        @Override
        public @Nullable LiveResult<ThroughputAggregate> getThroughputAggregates(
                TransactionQuery query) {
            return null;
        }

        @Override
        public long mergeInQueries(QueryCollector collector, TransactionQuery query) {
            return query.to();
        }

        @Override
        public long mergeInServiceCalls(ServiceCallCollector collector, TransactionQuery query) {
            return query.to();
        }

        @Override
        public long mergeInMainThreadProfiles(ProfileCollector collector, TransactionQuery query) {
            return query.to();
        }

        @Override
        public long mergeInAuxThreadProfiles(ProfileCollector collector, TransactionQuery query) {
            return query.to();
        }
    }
}
