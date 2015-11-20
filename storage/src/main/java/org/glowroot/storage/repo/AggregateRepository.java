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

import javax.annotation.Nullable;

import org.immutables.value.Value;

import org.glowroot.common.live.LiveAggregateRepository.OverallErrorSummary;
import org.glowroot.common.live.LiveAggregateRepository.OverallSummary;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.live.LiveAggregateRepository.ThroughputAggregate;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.common.util.Styles;
import org.glowroot.wire.api.model.AggregateOuterClass.AggregatesByType;

public interface AggregateRepository {

    void store(String serverId, long captureTime, List<AggregatesByType> aggregatesByType)
            throws Exception;

    // query.from() is non-inclusive
    OverallSummary readOverallSummary(OverallQuery query) throws Exception;

    // query.from() is non-inclusive
    // sortOrder and limit are only used by fat agent H2 collector, while the central collector
    // which currently has to pull in all records anyways, just delegates ordering and limit to
    // TransactionSummaryCollector
    void mergeInTransactionSummaries(TransactionSummaryCollector mergedTransactionSummaries,
            OverallQuery query, SummarySortOrder sortOrder, int limit) throws Exception;

    // query.from() is non-inclusive
    OverallErrorSummary readOverallErrorSummary(OverallQuery query) throws Exception;

    // query.from() is non-inclusive
    // sortOrder and limit are only used by fat agent H2 collector, while the central collector
    // which currently has to pull in all records anyways, just delegates ordering and limit to
    // TransactionErrorSummaryCollector
    void mergeInTransactionErrorSummaries(
            TransactionErrorSummaryCollector mergedTransactionErrorSummaries, OverallQuery query,
            ErrorSummarySortOrder sortOrder, int limit) throws Exception;

    // query.from() is INCLUSIVE
    List<OverviewAggregate> readOverviewAggregates(TransactionQuery query) throws Exception;

    // query.from() is INCLUSIVE
    List<PercentileAggregate> readPercentileAggregates(TransactionQuery query) throws Exception;

    // query.from() is INCLUSIVE
    List<ThroughputAggregate> readThroughputAggregates(TransactionQuery query) throws Exception;

    // query.from() is non-inclusive
    void mergeInProfiles(ProfileCollector mergedProfile, TransactionQuery query) throws Exception;

    // query.from() is non-inclusive
    void mergeInQueries(QueryCollector mergedQueries, TransactionQuery query) throws Exception;

    // query.from() is INCLUSIVE
    List<ErrorPoint> readErrorPoints(TransactionQuery query) throws Exception;

    // query.from() is non-inclusive
    boolean shouldHaveProfile(TransactionQuery query) throws Exception;

    // query.from() is non-inclusive
    boolean shouldHaveQueries(TransactionQuery query) throws Exception;

    void deleteAll(String serverRollup) throws Exception;

    @Value.Immutable
    public interface OverallQuery {
        String serverRollup();
        String transactionType();
        long from();
        long to();
        int rollupLevel();
    }

    @Value.Immutable
    public interface TransactionQuery {
        String serverRollup();
        String transactionType();
        @Nullable
        String transactionName();
        long from();
        long to();
        int rollupLevel();
    }

    @Value.Immutable
    @Styles.AllParameters
    public interface ErrorPoint {
        long captureTime();
        long errorCount();
        long transactionCount();
    }

    public enum SummarySortOrder {
        TOTAL_TIME, AVERAGE_TIME, THROUGHPUT
    }

    public enum ErrorSummarySortOrder {
        ERROR_COUNT, ERROR_RATE
    }
}
