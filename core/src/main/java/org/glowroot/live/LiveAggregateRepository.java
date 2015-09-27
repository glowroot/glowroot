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
package org.glowroot.live;

import java.util.List;

import javax.annotation.Nullable;

import org.immutables.value.Value;

import org.glowroot.collector.spi.model.AggregateOuterClass.Aggregate;
import org.glowroot.collector.spi.model.ProfileTreeOuterClass.ProfileTree;
import org.glowroot.common.util.Styles;

public interface LiveAggregateRepository {

    // non-inclusive
    @Nullable
    LiveResult<OverallSummary> getLiveOverallSummary(long serverId, String transactionType,
            long from, long to) throws Exception;

    // non-inclusive
    @Nullable
    LiveResult<List<TransactionSummary>> getLiveTransactionSummaries(long serverId,
            String transactionType, long from, long to) throws Exception;

    @Nullable
    LiveResult<OverviewAggregate> getLiveOverviewAggregates(long serverId, String transactionType,
            @Nullable String transactionName, long from, long to, long liveCaptureTime)
                    throws Exception;

    @Nullable
    LiveResult<PercentileAggregate> getLivePercentileAggregates(long serverId,
            String transactionType, @Nullable String transactionName, long from, long to,
            long liveCaptureTime) throws Exception;

    @Nullable
    LiveResult<ProfileTree> getLiveProfileTree(long serverId, String transactionType,
            @Nullable String transactionName, long from, long to) throws Exception;

    @Nullable
    LiveResult<List<Aggregate.QueriesByType>> getLiveQueries(long serverId, String transactionType,
            @Nullable String transactionName, long from, long to) throws Exception;

    @Nullable
    LiveResult<OverallErrorSummary> getLiveOverallErrorSummary(long serverId,
            String transactionType, long from, long to) throws Exception;

    @Nullable
    LiveResult<List<TransactionErrorSummary>> getLiveTransactionErrorSummaries(long serverId,
            String transactionType, long from, long to) throws Exception;

    @Nullable
    LiveResult<ErrorPoint> getLiveErrorPoints(long serverId, String transactionType,
            @Nullable String transactionName, long from, long to, long liveCaptureTime)
                    throws Exception;

    void clearAll();

    @Value.Immutable
    public interface OverallSummary {
        // aggregates use double instead of long to avoid (unlikely) 292 year nanosecond rollover
        double totalNanos();
        long transactionCount();
    }

    @Value.Immutable
    public interface TransactionSummary {
        String transactionName();
        // aggregates use double instead of long to avoid (unlikely) 292 year nanosecond rollover
        double totalNanos();
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
        double totalNanos();
        long transactionCount();
        double totalCpuNanos(); // -1 means N/A
        double totalBlockedNanos(); // -1 means N/A
        double totalWaitedNanos(); // -1 means N/A
        double totalAllocatedBytes(); // -1 means N/A
        List<Aggregate.Timer> rootTimers();
    }

    @Value.Immutable
    public interface PercentileAggregate {
        long captureTime();
        // aggregates use double instead of long to avoid (unlikely) 292 year nanosecond rollover
        double totalNanos();
        long transactionCount();
        Aggregate.Histogram histogram();
    }

    @Value.Immutable
    @Styles.AllParameters
    public interface ErrorPoint {
        long captureTime();
        long errorCount();
        long transactionCount();
    }

    public class LiveResult<T> {

        private final List<T> result;
        private final long initialCaptureTime;

        public LiveResult(List<T> result, long initialCaptureTime) {
            this.result = result;
            this.initialCaptureTime = initialCaptureTime;
        }

        public List<T> get() {
            return result;
        }

        public long initialCaptureTime() {
            return initialCaptureTime;
        }
    }

    public class LiveAggregateRepositoryNop implements LiveAggregateRepository {

        @Override
        public @Nullable LiveResult<OverallSummary> getLiveOverallSummary(long serverId,
                String transactionType, long from, long to) {
            return null;
        }

        @Override
        public @Nullable LiveResult<List<TransactionSummary>> getLiveTransactionSummaries(
                long serverId, String transactionType, long from, long to) {
            return null;
        }

        @Override
        public @Nullable LiveResult<OverviewAggregate> getLiveOverviewAggregates(long serverId,
                String transactionType, @Nullable String transactionName, long from, long to,
                long liveCaptureTime) {
            return null;
        }

        @Override
        public @Nullable LiveResult<PercentileAggregate> getLivePercentileAggregates(long serverId,
                String transactionType, @Nullable String transactionName, long from, long to,
                long liveCaptureTime) {
            return null;
        }

        @Override
        public @Nullable LiveResult<ProfileTree> getLiveProfileTree(long serverId,
                String transactionType, @Nullable String transactionName, long from, long to) {
            return null;
        }

        @Override
        public @Nullable LiveResult<List<Aggregate.QueriesByType>> getLiveQueries(long serverId,
                String transactionType, @Nullable String transactionName, long from, long to) {
            return null;
        }

        @Override
        public @Nullable LiveResult<OverallErrorSummary> getLiveOverallErrorSummary(long serverId,
                String transactionType, long from, long to) {
            return null;
        }

        @Override
        public @Nullable LiveResult<List<TransactionErrorSummary>> getLiveTransactionErrorSummaries(
                long serverId, String transactionType, long from, long to) {
            return null;
        }

        @Override
        public @Nullable LiveResult<ErrorPoint> getLiveErrorPoints(long serverId,
                String transactionType, @Nullable String transactionName, long from, long to,
                long liveCaptureTime) {
            return null;
        }

        @Override
        public void clearAll() {}
    }
}
