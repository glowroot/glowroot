/*
 * Copyright 2015-2016 the original author or authors.
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

import org.glowroot.common.live.LiveAggregateRepository.ErrorSummarySortOrder;
import org.glowroot.common.live.LiveAggregateRepository.OverallQuery;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.live.LiveAggregateRepository.SummarySortOrder;
import org.glowroot.common.live.LiveAggregateRepository.ThroughputAggregate;
import org.glowroot.common.live.LiveAggregateRepository.TransactionQuery;
import org.glowroot.common.model.OverallErrorSummaryCollector;
import org.glowroot.common.model.OverallSummaryCollector;
import org.glowroot.common.model.ProfileCollector;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.common.model.ServiceCallCollector;
import org.glowroot.common.model.TransactionErrorSummaryCollector;
import org.glowroot.common.model.TransactionSummaryCollector;
import org.glowroot.wire.api.model.AggregateOuterClass.AggregatesByType;

public interface AggregateRepository {

    void store(String agentId, long captureTime, List<AggregatesByType> aggregatesByType)
            throws Exception;

    // query.from() is non-inclusive
    void mergeInOverallSummary(OverallSummaryCollector collector, OverallQuery query)
            throws Exception;

    // query.from() is non-inclusive
    // sortOrder and limit are only used by fat agent H2 repository, while the glowroot server
    // repository which currently has to pull in all records anyways, just delegates ordering and
    // limit to TransactionSummaryCollector
    void mergeInTransactionSummaries(TransactionSummaryCollector collector, OverallQuery query,
            SummarySortOrder sortOrder, int limit) throws Exception;

    // query.from() is non-inclusive
    void mergeInOverallErrorSummary(OverallErrorSummaryCollector collector, OverallQuery query)
            throws Exception;

    // query.from() is non-inclusive
    // sortOrder and limit are only used by fat agent H2 repository, while the glowroot server
    // repository which currently has to pull in all records anyways, just delegates ordering and
    // limit to TransactionErrorSummaryCollector
    void mergeInTransactionErrorSummaries(TransactionErrorSummaryCollector collector,
            OverallQuery query, ErrorSummarySortOrder sortOrder, int limit) throws Exception;

    // query.from() is INCLUSIVE
    List<OverviewAggregate> readOverviewAggregates(TransactionQuery query) throws Exception;

    // query.from() is INCLUSIVE
    List<PercentileAggregate> readPercentileAggregates(TransactionQuery query) throws Exception;

    // query.from() is INCLUSIVE
    List<ThroughputAggregate> readThroughputAggregates(TransactionQuery query) throws Exception;

    // query.from() is non-inclusive
    void mergeInQueries(QueryCollector collector, TransactionQuery query) throws Exception;

    // query.from() is non-inclusive
    void mergeInServiceCalls(ServiceCallCollector collector, TransactionQuery query)
            throws Exception;

    // query.from() is non-inclusive
    void mergeInMainThreadProfiles(ProfileCollector collector, TransactionQuery query)
            throws Exception;

    // query.from() is non-inclusive
    void mergeInAuxThreadProfiles(ProfileCollector collector, TransactionQuery query)
            throws Exception;

    // query.from() is non-inclusive
    boolean hasAuxThreadProfile(TransactionQuery query) throws Exception;

    // query.from() is non-inclusive
    boolean shouldHaveQueries(TransactionQuery query) throws Exception;

    // query.from() is non-inclusive
    boolean shouldHaveServiceCalls(TransactionQuery query) throws Exception;

    // query.from() is non-inclusive
    boolean shouldHaveMainThreadProfile(TransactionQuery query) throws Exception;

    // query.from() is non-inclusive
    boolean shouldHaveAuxThreadProfile(TransactionQuery query) throws Exception;

    void deleteAll(String agentRollup) throws Exception;
}
