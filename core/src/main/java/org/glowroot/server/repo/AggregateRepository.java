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
package org.glowroot.server.repo;

import java.sql.SQLException;
import java.util.List;

import org.immutables.value.Value;

import org.glowroot.common.model.QueryCollector;
import org.glowroot.live.LiveAggregateRepository.ErrorPoint;
import org.glowroot.live.LiveAggregateRepository.OverallErrorSummary;
import org.glowroot.live.LiveAggregateRepository.OverallSummary;
import org.glowroot.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.live.LiveAggregateRepository.TransactionErrorSummary;
import org.glowroot.live.LiveAggregateRepository.TransactionSummary;

public interface AggregateRepository {

    // captureTimeFrom is non-inclusive
    OverallSummary readOverallSummary(long serverId, String transactionType, long captureTimeFrom,
            long captureTimeTo) throws Exception;

    // query.from() is non-inclusive
    Result<TransactionSummary> readTransactionSummaries(TransactionSummaryQuery query)
            throws Exception;

    // captureTimeFrom is non-inclusive
    OverallErrorSummary readOverallErrorSummary(long serverId, String transactionType,
            long captureTimeFrom, long captureTimeTo, int rollupLevel) throws Exception;

    // captureTimeFrom is non-inclusive
    Result<TransactionErrorSummary> readTransactionErrorSummaries(ErrorSummaryQuery query,
            int rollupLevel) throws Exception;

    // captureTimeFrom is INCLUSIVE
    List<OverviewAggregate> readOverallOverviewAggregates(long serverId, String transactionType,
            long captureTimeFrom, long captureTimeTo, int rollupLevel) throws Exception;

    // captureTimeFrom is INCLUSIVE
    List<PercentileAggregate> readOverallPercentileAggregates(long serverId, String transactionType,
            long captureTimeFrom, long captureTimeTo, int rollupLevel) throws Exception;

    // captureTimeFrom is INCLUSIVE
    List<OverviewAggregate> readTransactionOverviewAggregates(long serverId, String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo, int rollupLevel)
                    throws Exception;

    // captureTimeFrom is INCLUSIVE
    List<PercentileAggregate> readTransactionPercentileAggregates(long serverId,
            String transactionType, String transactionName, long captureTimeFrom,
            long captureTimeTo, int rollupLevel) throws Exception;

    // captureTimeFrom is non-inclusive
    void mergeInOverallProfiles(ProfileCollector mergedProfile, long serverId,
            String transactionType, long captureTimeFrom, long captureTimeTo, int rollupLevel)
                    throws Exception;

    // captureTimeFrom is non-inclusive
    void mergeInTransactionProfiles(ProfileCollector mergedProfile, long serverId,
            String transactionType, String transactionName, long captureTimeFrom,
            long captureTimeTo, int rollupLevel) throws Exception;

    // captureTimeFrom is non-inclusive
    void mergeInOverallQueries(QueryCollector mergedQueries, long serverId, String transactionType,
            long captureTimeFrom, long captureTimeTo, int rollupLevel) throws Exception;

    // captureTimeFrom is non-inclusive
    void mergeInTransactionQueries(QueryCollector mergedQueries, long serverId,
            String transactionType, String transactionName, long captureTimeFrom,
            long captureTimeTo, int rollupLevel) throws Exception;

    List<ErrorPoint> readOverallErrorPoints(long serverId, String transactionType,
            long captureTimeFrom, long captureTimeTo, int rollupLevel) throws Exception;

    List<ErrorPoint> readTransactionErrorPoints(long serverId, String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo, int rollupLevel)
                    throws Exception;

    // captureTimeFrom is non-inclusive
    boolean shouldHaveOverallQueries(long serverId, String transactionType, long captureTimeFrom,
            long captureTimeTo) throws Exception;

    // captureTimeFrom is non-inclusive
    boolean shouldHaveTransactionQueries(long serverId, String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo) throws Exception;

    // captureTimeFrom is non-inclusive
    boolean shouldHaveOverallProfile(long serverId, String transactionType, long captureTimeFrom,
            long captureTimeTo) throws Exception;

    // captureTimeFrom is non-inclusive
    boolean shouldHaveTransactionProfile(long serverId, String transactionType,
            String transactionName, long captureTimeFrom, long captureTimeTo) throws Exception;

    long getDataPointIntervalMillis(long serverId, long captureTimeFrom, long captureTimeTo);

    int getRollupLevelForView(long serverId, long captureTimeFrom, long captureTimeTo);

    void deleteAll(long serverId) throws SQLException;

    @Value.Immutable
    public interface TransactionSummaryQuery {
        long serverId();
        String transactionType();
        // from is non-inclusive
        long from();
        long to();
        TransactionSummarySortOrder sortOrder();
        int limit();
    }

    @Value.Immutable
    public interface ErrorSummaryQuery {
        long serverId();
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
