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
package org.glowroot.common.repo;

import java.sql.SQLException;

import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;

import org.glowroot.common.util.Styles;

public interface AggregateRepository {

    // captureTimeFrom is non-inclusive
    OverallSummary readOverallSummary(String transactionType, long captureTimeFrom,
            long captureTimeTo) throws Exception;

    // query.from() is non-inclusive
    Result<TransactionSummary> readTransactionSummaries(TransactionSummaryQuery query)
            throws Exception;

    // captureTimeFrom is non-inclusive
    OverallErrorSummary readOverallErrorSummary(String transactionType, long captureTimeFrom,
            long captureTimeTo, int rollupLevel) throws Exception;

    // captureTimeFrom is non-inclusive
    Result<TransactionErrorSummary> readTransactionErrorSummaries(ErrorSummaryQuery query,
            int rollupLevel) throws Exception;

    // captureTimeFrom is INCLUSIVE
    ImmutableList<OverviewAggregate> readOverallOverviewAggregates(String transactionType,
            long captureTimeFrom, long captureTimeTo, int rollupLevel) throws Exception;

    // captureTimeFrom is INCLUSIVE
    ImmutableList<PercentileAggregate> readOverallPercentileAggregates(String transactionType,
            long captureTimeFrom, long captureTimeTo, int rollupLevel) throws Exception;

    // captureTimeFrom is INCLUSIVE
    ImmutableList<OverviewAggregate> readTransactionOverviewAggregates(String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo, int rollupLevel)
                    throws Exception;

    // captureTimeFrom is INCLUSIVE
    ImmutableList<PercentileAggregate> readTransactionPercentileAggregates(String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo, int rollupLevel)
                    throws Exception;

    // captureTimeFrom is non-inclusive
    void mergeInOverallQueries(QueryCollector mergedQueries, String transactionType,
            long captureTimeFrom, long captureTimeTo, int rollupLevel) throws Exception;

    // captureTimeFrom is non-inclusive
    void mergeInTransactionQueries(QueryCollector mergedQueries, String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo, int rollupLevel)
                    throws Exception;

    // captureTimeFrom is non-inclusive
    void mergeInOverallProfile(ProfileCollector mergedProfile, String transactionType,
            long captureTimeFrom, long captureTimeTo, int rollupLevel) throws Exception;

    // captureTimeFrom is non-inclusive
    void mergeInTransactionProfile(ProfileCollector mergedProfile, String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo, int rollupLevel)
                    throws Exception;

    ImmutableList<ErrorPoint> readOverallErrorPoints(String transactionType, long captureTimeFrom,
            long captureTimeTo, int rollupLevel) throws Exception;

    ImmutableList<ErrorPoint> readTransactionErrorPoints(String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo, int rollupLevel)
                    throws Exception;

    // captureTimeFrom is non-inclusive
    boolean shouldHaveOverallQueries(String transactionType, long captureTimeFrom,
            long captureTimeTo) throws Exception;

    // captureTimeFrom is non-inclusive
    boolean shouldHaveTransactionQueries(String transactionType, String transactionName,
            long captureTimeFrom, long captureTimeTo) throws Exception;

    // captureTimeFrom is non-inclusive
    boolean shouldHaveOverallProfile(String transactionType, long captureTimeFrom,
            long captureTimeTo) throws Exception;

    // captureTimeFrom is non-inclusive
    boolean shouldHaveTransactionProfile(String transactionType, String transactionName,
            long captureTimeFrom, long captureTimeTo) throws Exception;

    long getDataPointIntervalMillis(long captureTimeFrom, long captureTimeTo);

    int getRollupLevelForView(long captureTimeFrom, long captureTimeTo);

    // only supported by local storage implementation
    void deleteAll() throws SQLException;

    @Value.Immutable
    public interface TransactionSummaryQuery {
        String transactionType();
        // from is non-inclusive
        long from();
        long to();
        TransactionSummarySortOrder sortOrder();
        int limit();
    }

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
    public interface ErrorSummaryQuery {
        String transactionType();
        // from is non-inclusive
        long from();
        long to();
        AggregateRepository.ErrorSummarySortOrder sortOrder();
        int limit();
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
    @Styles.AllParameters
    public interface ErrorPoint {
        long captureTime();
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
        MutableTimerNode syntheticRootTimer();
    }

    @Value.Immutable
    public interface PercentileAggregate {
        long captureTime();
        // aggregates use double instead of long to avoid (unlikely) 292 year nanosecond rollover
        double totalNanos();
        long transactionCount();
        LazyHistogram histogram();
    }

    public enum TransactionSummarySortOrder {
        TOTAL_TIME, AVERAGE_TIME, THROUGHPUT
    }

    public enum ErrorSummarySortOrder {
        ERROR_COUNT, ERROR_RATE
    }
}
