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

import org.glowroot.common.live.LiveAggregateRepository.OverallQuery;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.live.LiveAggregateRepository.ThroughputAggregate;
import org.glowroot.common.live.LiveAggregateRepository.TransactionQuery;
import org.glowroot.common.model.OverallErrorSummaryCollector;
import org.glowroot.common.model.OverallSummaryCollector;
import org.glowroot.common.model.ProfileCollector;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.common.model.ServiceCallCollector;
import org.glowroot.common.model.TransactionErrorSummaryCollector;
import org.glowroot.common.model.TransactionErrorSummaryCollector.ErrorSummarySortOrder;
import org.glowroot.common.model.TransactionSummaryCollector;
import org.glowroot.common.model.TransactionSummaryCollector.SummarySortOrder;
import org.glowroot.wire.api.model.AggregateOuterClass.AggregatesByType;

public interface AggregateRepository {

    void store(String agentId, long captureTime, List<AggregatesByType> aggregatesByType)
            throws Exception;

    // query.from() is non-inclusive
    void mergeInOverallSummary(String agentRollup, OverallQuery query,
            OverallSummaryCollector collector) throws Exception;

    // query.from() is non-inclusive
    // sortOrder and limit are only used by fat agent H2 repository, while the glowroot server
    // repository which currently has to pull in all records anyways, just delegates ordering and
    // limit to TransactionSummaryCollector
    void mergeInTransactionSummaries(String agentRollup, OverallQuery query,
            SummarySortOrder sortOrder, int limit, TransactionSummaryCollector collector)
            throws Exception;

    // query.from() is non-inclusive
    void mergeInOverallErrorSummary(String agentRollup, OverallQuery query,
            OverallErrorSummaryCollector collector) throws Exception;

    // query.from() is non-inclusive
    // sortOrder and limit are only used by fat agent H2 repository, while the glowroot server
    // repository which currently has to pull in all records anyways, just delegates ordering and
    // limit to TransactionErrorSummaryCollector
    void mergeInTransactionErrorSummaries(String agentRollup, OverallQuery query,
            ErrorSummarySortOrder sortOrder, int limit, TransactionErrorSummaryCollector collector)
            throws Exception;

    // query.from() is INCLUSIVE
    List<OverviewAggregate> readOverviewAggregates(String agentRollup, TransactionQuery query)
            throws Exception;

    // query.from() is INCLUSIVE
    List<PercentileAggregate> readPercentileAggregates(String agentRollup, TransactionQuery query)
            throws Exception;

    // query.from() is INCLUSIVE
    List<ThroughputAggregate> readThroughputAggregates(String agentRollup, TransactionQuery query)
            throws Exception;

    // query.from() is non-inclusive
    void mergeInQueries(String agentRollup, TransactionQuery query, QueryCollector collector)
            throws Exception;

    // query.from() is non-inclusive
    void mergeInServiceCalls(String agentRollup, TransactionQuery query,
            ServiceCallCollector collector) throws Exception;

    // query.from() is non-inclusive
    void mergeInMainThreadProfiles(String agentRollup, TransactionQuery query,
            ProfileCollector collector) throws Exception;

    // query.from() is non-inclusive
    void mergeInAuxThreadProfiles(String agentRollup, TransactionQuery query,
            ProfileCollector collector) throws Exception;

    // query.from() is non-inclusive
    boolean hasAuxThreadProfile(String agentRollup, TransactionQuery query) throws Exception;

    // query.from() is non-inclusive
    boolean shouldHaveQueries(String agentRollup, TransactionQuery query) throws Exception;

    // query.from() is non-inclusive
    boolean shouldHaveServiceCalls(String agentRollup, TransactionQuery query) throws Exception;

    // query.from() is non-inclusive
    boolean shouldHaveMainThreadProfile(String agentRollup, TransactionQuery query)
            throws Exception;

    // query.from() is non-inclusive
    boolean shouldHaveAuxThreadProfile(String agentRollup, TransactionQuery query) throws Exception;
}
