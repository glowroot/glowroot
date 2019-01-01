/*
 * Copyright 2015-2019 the original author or authors.
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
package org.glowroot.common2.repo;

import java.util.List;

import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.common.live.LiveAggregateRepository.AggregateQuery;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.live.LiveAggregateRepository.SummaryQuery;
import org.glowroot.common.live.LiveAggregateRepository.ThroughputAggregate;
import org.glowroot.common.model.OverallErrorSummaryCollector;
import org.glowroot.common.model.OverallSummaryCollector;
import org.glowroot.common.model.ProfileCollector;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.common.model.ServiceCallCollector;
import org.glowroot.common.model.TransactionNameErrorSummaryCollector;
import org.glowroot.common.model.TransactionNameErrorSummaryCollector.ErrorSummarySortOrder;
import org.glowroot.common.model.TransactionNameSummaryCollector;
import org.glowroot.common.model.TransactionNameSummaryCollector.SummarySortOrder;

public interface AggregateRepository {

    // query.from() is non-inclusive
    void mergeOverallSummaryInto(String agentRollupId, SummaryQuery query,
            OverallSummaryCollector collector) throws Exception;

    // query.from() is non-inclusive
    // sortOrder and limit are only used by embedded H2 repository, while the central cassandra
    // repository which currently has to pull in all records anyways just delegates ordering and
    // limit to TransactionNameSummaryCollector
    void mergeTransactionNameSummariesInto(String agentRollupId, SummaryQuery query,
            SummarySortOrder sortOrder, int limit, TransactionNameSummaryCollector collector)
            throws Exception;

    // query.from() is non-inclusive
    void mergeOverallErrorSummaryInto(String agentRollupId, SummaryQuery query,
            OverallErrorSummaryCollector collector) throws Exception;

    // query.from() is non-inclusive
    // sortOrder and limit are only used by embedded H2 repository, while the central cassandra
    // repository which currently has to pull in all records anyways just delegates ordering and
    // limit to TransactionNameErrorSummaryCollector
    void mergeTransactionNameErrorSummariesInto(String agentRollupId, SummaryQuery query,
            ErrorSummarySortOrder sortOrder, int limit,
            TransactionNameErrorSummaryCollector collector) throws Exception;

    // query.from() is INCLUSIVE
    List<OverviewAggregate> readOverviewAggregates(String agentRollupId, AggregateQuery query)
            throws Exception;

    // query.from() is INCLUSIVE
    List<PercentileAggregate> readPercentileAggregates(String agentRollupId, AggregateQuery query)
            throws Exception;

    // query.from() is INCLUSIVE
    List<ThroughputAggregate> readThroughputAggregates(String agentRollupId, AggregateQuery query)
            throws Exception;

    // query.from() is non-inclusive
    void mergeQueriesInto(String agentRollupId, AggregateQuery query, QueryCollector collector)
            throws Exception;

    // query.from() is non-inclusive
    void mergeServiceCallsInto(String agentRollupId, AggregateQuery query,
            ServiceCallCollector collector) throws Exception;

    // query.from() is non-inclusive
    void mergeMainThreadProfilesInto(String agentRollupId, AggregateQuery query,
            ProfileCollector collector) throws Exception;

    // query.from() is non-inclusive
    void mergeAuxThreadProfilesInto(String agentRollupId, AggregateQuery query,
            ProfileCollector collector) throws Exception;

    @Nullable
    String readFullQueryText(String agentRollupId, String fullQueryTextSha1) throws Exception;

    // query.from() is non-inclusive
    boolean hasMainThreadProfile(String agentRollupId, AggregateQuery query) throws Exception;

    // query.from() is non-inclusive
    boolean hasAuxThreadProfile(String agentRollupId, AggregateQuery query) throws Exception;

    // query.from() is non-inclusive
    boolean shouldHaveQueries(String agentRollupId, AggregateQuery query) throws Exception;

    // query.from() is non-inclusive
    boolean shouldHaveServiceCalls(String agentRollupId, AggregateQuery query) throws Exception;

    // query.from() is non-inclusive
    boolean shouldHaveMainThreadProfile(String agentRollupId, AggregateQuery query)
            throws Exception;

    // query.from() is non-inclusive
    boolean shouldHaveAuxThreadProfile(String agentRollupId, AggregateQuery query) throws Exception;
}
