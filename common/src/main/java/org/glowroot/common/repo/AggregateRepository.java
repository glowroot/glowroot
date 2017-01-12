/*
 * Copyright 2015-2017 the original author or authors.
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

import java.util.List;

import javax.annotation.Nullable;

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

public interface AggregateRepository {

    // query.from() is non-inclusive
    void mergeOverallSummaryInto(String agentRollupId, OverallQuery query,
            OverallSummaryCollector collector) throws Exception;

    // query.from() is non-inclusive
    // sortOrder and limit are only used by embedded H2 repository, while the central cassandra
    // repository which currently has to pull in all records anyways just delegates ordering and
    // limit to TransactionSummaryCollector
    void mergeTransactionSummariesInto(String agentRollupId, OverallQuery query,
            SummarySortOrder sortOrder, int limit, TransactionSummaryCollector collector)
            throws Exception;

    // query.from() is non-inclusive
    void mergeOverallErrorSummaryInto(String agentRollupId, OverallQuery query,
            OverallErrorSummaryCollector collector) throws Exception;

    // query.from() is non-inclusive
    // sortOrder and limit are only used by embedded H2 repository, while the central cassandra
    // repository which currently has to pull in all records anyways just delegates ordering and
    // limit to TransactionErrorSummaryCollector
    void mergeTransactionErrorSummariesInto(String agentRollupId, OverallQuery query,
            ErrorSummarySortOrder sortOrder, int limit, TransactionErrorSummaryCollector collector)
            throws Exception;

    // query.from() is INCLUSIVE
    List<OverviewAggregate> readOverviewAggregates(String agentRollupId, TransactionQuery query)
            throws Exception;

    // query.from() is INCLUSIVE
    List<PercentileAggregate> readPercentileAggregates(String agentRollupId, TransactionQuery query)
            throws Exception;

    // query.from() is INCLUSIVE
    List<ThroughputAggregate> readThroughputAggregates(String agentRollupId, TransactionQuery query)
            throws Exception;

    @Nullable
    String readFullQueryText(String agentRollupId, String fullQueryTextSha1) throws Exception;

    // query.from() is non-inclusive
    void mergeQueriesInto(String agentRollupId, TransactionQuery query, QueryCollector collector)
            throws Exception;

    // query.from() is non-inclusive
    void mergeServiceCallsInto(String agentRollupId, TransactionQuery query,
            ServiceCallCollector collector) throws Exception;

    // query.from() is non-inclusive
    void mergeMainThreadProfilesInto(String agentRollupId, TransactionQuery query,
            ProfileCollector collector) throws Exception;

    // query.from() is non-inclusive
    void mergeAuxThreadProfilesInto(String agentRollupId, TransactionQuery query,
            ProfileCollector collector) throws Exception;

    // query.from() is non-inclusive
    boolean hasMainThreadProfile(String agentRollupId, TransactionQuery query) throws Exception;

    // query.from() is non-inclusive
    boolean hasAuxThreadProfile(String agentRollupId, TransactionQuery query) throws Exception;

    // query.from() is non-inclusive
    boolean shouldHaveQueries(String agentRollupId, TransactionQuery query) throws Exception;

    // query.from() is non-inclusive
    boolean shouldHaveServiceCalls(String agentRollupId, TransactionQuery query) throws Exception;

    // query.from() is non-inclusive
    boolean shouldHaveMainThreadProfile(String agentRollupId, TransactionQuery query)
            throws Exception;

    // query.from() is non-inclusive
    boolean shouldHaveAuxThreadProfile(String agentRollupId, TransactionQuery query)
            throws Exception;
}
