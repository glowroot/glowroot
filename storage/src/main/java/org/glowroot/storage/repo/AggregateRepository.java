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
package org.glowroot.storage.repo;

import java.util.List;

import org.immutables.value.Value;

import org.glowroot.common.live.LiveAggregateRepository.ErrorPoint;
import org.glowroot.common.live.LiveAggregateRepository.OverallErrorSummary;
import org.glowroot.common.live.LiveAggregateRepository.OverallSummary;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.live.LiveAggregateRepository.ThroughputAggregate;
import org.glowroot.common.live.LiveAggregateRepository.TransactionErrorSummary;
import org.glowroot.common.live.LiveAggregateRepository.TransactionSummary;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.wire.api.model.AggregateOuterClass.OverallAggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.TransactionAggregate;

public interface AggregateRepository {

    void store(String serverGroup, long captureTime, List<OverallAggregate> overallAggregates,
            List<TransactionAggregate> transactionAggregates) throws Exception;

    // captureTimeFrom is non-inclusive
    OverallSummary readOverallSummary(String serverGroup, String transactionType,
            long captureTimeFrom, long captureTimeTo) throws Exception;

    // query.from() is non-inclusive
    Result<TransactionSummary> readTransactionSummaries(TransactionSummaryQuery query)
            throws Exception;

    // captureTimeFrom is non-inclusive
    OverallErrorSummary readOverallErrorSummary(String serverGroup, String transactionType,
            long captureTimeFrom, long captureTimeTo, int rollupLevel) throws Exception;

    // captureTimeFrom is non-inclusive
    Result<TransactionErrorSummary> readTransactionErrorSummaries(ErrorSummaryQuery query)
            throws Exception;

    // captureTimeFrom is INCLUSIVE
    List<OverviewAggregate> readOverallOverviewAggregates(String serverGroup,
            String transactionType, long captureTimeFrom, long captureTimeTo, int rollupLevel)
                    throws Exception;

    // captureTimeFrom is INCLUSIVE
    List<PercentileAggregate> readOverallPercentileAggregates(String serverGroup,
            String transactionType, long captureTimeFrom, long captureTimeTo, int rollupLevel)
                    throws Exception;

    // captureTimeFrom is INCLUSIVE
    List<ThroughputAggregate> readOverallThroughputAggregates(String serverGroup,
            String transactionType, long captureTimeFrom, long captureTimeTo, int rollupLevel)
                    throws Exception;

    // captureTimeFrom is INCLUSIVE
    List<OverviewAggregate> readTransactionOverviewAggregates(String serverGroup,
            String transactionType, String transactionName, long captureTimeFrom,
            long captureTimeTo, int rollupLevel) throws Exception;

    // captureTimeFrom is INCLUSIVE
    List<PercentileAggregate> readTransactionPercentileAggregates(String serverGroup,
            String transactionType, String transactionName, long captureTimeFrom,
            long captureTimeTo, int rollupLevel) throws Exception;

    // captureTimeFrom is INCLUSIVE
    List<ThroughputAggregate> readTransactionThroughputAggregates(String serverGroup,
            String transactionType, String transactionName, long captureTimeFrom,
            long captureTimeTo, int rollupLevel) throws Exception;

    // captureTimeFrom is non-inclusive
    void mergeInOverallProfiles(ProfileCollector mergedProfile, String serverGroup,
            String transactionType, long captureTimeFrom, long captureTimeTo, int rollupLevel)
                    throws Exception;

    // captureTimeFrom is non-inclusive
    void mergeInTransactionProfiles(ProfileCollector mergedProfile, String serverGroup,
            String transactionType, String transactionName, long captureTimeFrom,
            long captureTimeTo, int rollupLevel) throws Exception;

    // captureTimeFrom is non-inclusive
    void mergeInOverallQueries(QueryCollector mergedQueries, String serverGroup,
            String transactionType, long captureTimeFrom, long captureTimeTo, int rollupLevel)
                    throws Exception;

    // captureTimeFrom is non-inclusive
    void mergeInTransactionQueries(QueryCollector mergedQueries, String serverGroup,
            String transactionType, String transactionName, long captureTimeFrom,
            long captureTimeTo, int rollupLevel) throws Exception;

    List<ErrorPoint> readOverallErrorPoints(String serverGroup, String transactionType,
            long captureTimeFrom, long captureTimeTo, int rollupLevel) throws Exception;

    List<ErrorPoint> readTransactionErrorPoints(String serverGroup, String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo, int rollupLevel)
                    throws Exception;

    // captureTimeFrom is non-inclusive
    boolean shouldHaveOverallQueries(String serverGroup, String transactionType,
            long captureTimeFrom, long captureTimeTo) throws Exception;

    // captureTimeFrom is non-inclusive
    boolean shouldHaveTransactionQueries(String serverGroup, String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo) throws Exception;

    // captureTimeFrom is non-inclusive
    boolean shouldHaveOverallProfile(String serverGroup, String transactionType,
            long captureTimeFrom, long captureTimeTo) throws Exception;

    // captureTimeFrom is non-inclusive
    boolean shouldHaveTransactionProfile(String serverGroup, String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo) throws Exception;

    long getDataPointIntervalMillis(String serverGroup, long captureTimeFrom, long captureTimeTo);

    int getRollupLevelForView(String serverGroup, long captureTimeFrom, long captureTimeTo);

    void deleteAll(String serverGroup) throws Exception;

    @Value.Immutable
    public interface TransactionSummaryQuery {
        String serverGroup();
        String transactionType();
        // from is non-inclusive
        long from();
        long to();
        TransactionSummarySortOrder sortOrder();
        int limit();
    }

    @Value.Immutable
    public interface ErrorSummaryQuery {
        String serverGroup();
        String transactionType();
        // from is non-inclusive
        long from();
        long to();
        AggregateRepository.ErrorSummarySortOrder sortOrder();
        int limit();
    }

    public enum TransactionSummarySortOrder {
        TOTAL_TIME, AVERAGE_TIME, THROUGHPUT
    }

    public enum ErrorSummarySortOrder {
        ERROR_COUNT, ERROR_RATE
    }
}
