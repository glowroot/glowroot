/*
 * Copyright 2015-2023 the original author or authors.
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
import java.util.concurrent.CompletionStage;

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
    CompletionStage<?> mergeOverallSummaryInto(String agentRollupId, SummaryQuery query,
            OverallSummaryCollector collector, CassandraProfile profile) throws Exception;

    // query.from() is non-inclusive
    // sortOrder and limit are only used by embedded H2 repository, while the central cassandra
    // repository which currently has to pull in all records anyways just delegates ordering and
    // limit to TransactionNameSummaryCollector
    CompletionStage<?> mergeTransactionNameSummariesInto(String agentRollupId, SummaryQuery query,
            SummarySortOrder sortOrder, int limit, TransactionNameSummaryCollector collector, CassandraProfile profile)
            throws Exception;

    // query.from() is non-inclusive
    CompletionStage<?> mergeOverallErrorSummaryInto(String agentRollupId, SummaryQuery query,
            OverallErrorSummaryCollector collector, CassandraProfile profile);

    // query.from() is non-inclusive
    // sortOrder and limit are only used by embedded H2 repository, while the central cassandra
    // repository which currently has to pull in all records anyways just delegates ordering and
    // limit to TransactionNameErrorSummaryCollector
    CompletionStage<?> mergeTransactionNameErrorSummariesInto(String agentRollupId, SummaryQuery query,
            ErrorSummarySortOrder sortOrder, int limit,
            TransactionNameErrorSummaryCollector collector, CassandraProfile profile);

    // query.from() is INCLUSIVE
    CompletionStage<List<OverviewAggregate>> readOverviewAggregates(String agentRollupId, AggregateQuery query, CassandraProfile profile);

    // query.from() is INCLUSIVE
    CompletionStage<List<PercentileAggregate>> readPercentileAggregates(String agentRollupId, AggregateQuery query, CassandraProfile profile);

    // query.from() is INCLUSIVE
    CompletionStage<List<ThroughputAggregate>> readThroughputAggregates(String agentRollupId, AggregateQuery query, CassandraProfile profile);

    // query.from() is non-inclusive
    CompletionStage<?> mergeQueriesInto(String agentRollupId, AggregateQuery query, QueryCollector collector, CassandraProfile profile);

    // query.from() is non-inclusive
    CompletionStage<?> mergeServiceCallsInto(String agentRollupId, AggregateQuery query,
            ServiceCallCollector collector, CassandraProfile profile);

    // query.from() is non-inclusive
    CompletionStage<?> mergeMainThreadProfilesInto(String agentRollupId, AggregateQuery query,
            ProfileCollector collector, CassandraProfile profile);

    // query.from() is non-inclusive
    CompletionStage<?> mergeAuxThreadProfilesInto(String agentRollupId, AggregateQuery query,
            ProfileCollector collector, CassandraProfile profile);

    @Nullable
    CompletionStage<String> readFullQueryText(String agentRollupId, String fullQueryTextSha1, CassandraProfile profile);

    // query.from() is non-inclusive
    CompletionStage<Boolean> hasMainThreadProfile(String agentRollupId, AggregateQuery query, CassandraProfile profile) throws Exception;

    // query.from() is non-inclusive
    CompletionStage<Boolean> hasAuxThreadProfile(String agentRollupId, AggregateQuery query, CassandraProfile profile) throws Exception;

    // query.from() is non-inclusive
    CompletionStage<Boolean> shouldHaveQueries(String agentRollupId, AggregateQuery query) throws Exception;

    // query.from() is non-inclusive
    CompletionStage<Boolean> shouldHaveServiceCalls(String agentRollupId, AggregateQuery query) throws Exception;

    // query.from() is non-inclusive
    CompletionStage<Boolean> shouldHaveMainThreadProfile(String agentRollupId, AggregateQuery query)
            throws Exception;

    // query.from() is non-inclusive
    CompletionStage<Boolean> shouldHaveAuxThreadProfile(String agentRollupId, AggregateQuery query) throws Exception;
}
