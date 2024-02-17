/*
 * Copyright 2017-2023 the original author or authors.
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
package org.glowroot.central.v09support;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.glowroot.common2.repo.CassandraProfile;
import org.immutables.value.Value;

import org.glowroot.central.repo.AgentRollupIds;
import org.glowroot.central.repo.AggregateDao;
import org.glowroot.central.repo.AggregateDaoImpl;
import org.glowroot.common.live.ImmutableAggregateQuery;
import org.glowroot.common.live.ImmutableSummaryQuery;
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
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.OldAggregatesByType;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.DAYS;

public class AggregateDaoWithV09Support implements AggregateDao {

    private final Set<String> agentRollupIdsWithV09Data;
    private final long v09LastCaptureTime;
    private final long v09FqtLastExpirationTime;
    private final Clock clock;
    private final AggregateDaoImpl delegate;

    public AggregateDaoWithV09Support(Set<String> agentRollupIdsWithV09Data,
            long v09LastCaptureTime, long v09FqtLastExpirationTime, Clock clock,
            AggregateDaoImpl delegate) {
        this.agentRollupIdsWithV09Data = agentRollupIdsWithV09Data;
        this.v09LastCaptureTime = v09LastCaptureTime;
        this.v09FqtLastExpirationTime = v09FqtLastExpirationTime;
        this.clock = clock;
        this.delegate = delegate;
    }

    @Override
    public CompletableFuture<?> store(String agentId, long captureTime,
                                   List<OldAggregatesByType> aggregatesByTypeList,
                                   List<Aggregate.SharedQueryText> initialSharedQueryTexts) {
        if (captureTime <= v09LastCaptureTime
                && agentRollupIdsWithV09Data.contains(agentId)) {
            return delegate.store(V09Support.convertToV09(agentId),
                    V09Support.getAgentRollupIdsV09(agentId), agentId,
                    AgentRollupIds.getAgentRollupIds(agentId), captureTime, aggregatesByTypeList,
                    initialSharedQueryTexts);
        } else {
            return delegate.store(agentId, captureTime, aggregatesByTypeList, initialSharedQueryTexts);
        }
    }

    // query.from() is non-inclusive
    @Override
    public void mergeOverallSummaryInto(String agentRollupId, SummaryQuery query,
            OverallSummaryCollector collector, CassandraProfile profile) throws Exception {
        splitMergeIfNeeded(agentRollupId, query,
                (id, q) -> delegate.mergeOverallSummaryInto(id, q, collector, profile));
    }

    // query.from() is non-inclusive
    @Override
    public void mergeTransactionNameSummariesInto(String agentRollupId, SummaryQuery query,
                                                  SummarySortOrder sortOrder, int limit, TransactionNameSummaryCollector collector, CassandraProfile profile)
            throws Exception {
        splitMergeIfNeeded(agentRollupId, query, (id, q) -> delegate
                .mergeTransactionNameSummariesInto(id, q, sortOrder, limit, collector, profile));
    }

    // query.from() is non-inclusive
    @Override
    public void mergeOverallErrorSummaryInto(String agentRollupId, SummaryQuery query,
            OverallErrorSummaryCollector collector, CassandraProfile profile) throws Exception {
        splitMergeIfNeeded(agentRollupId, query,
                (id, q) -> delegate.mergeOverallErrorSummaryInto(id, q, collector, profile));
    }

    // query.from() is non-inclusive
    @Override
    public void mergeTransactionNameErrorSummariesInto(String agentRollupId, SummaryQuery query,
            ErrorSummarySortOrder sortOrder, int limit,
            TransactionNameErrorSummaryCollector collector, CassandraProfile profile) throws Exception {
        splitMergeIfNeeded(agentRollupId, query, (id, q) -> delegate
                .mergeTransactionNameErrorSummariesInto(id, q, sortOrder, limit, collector, profile));
    }

    // query.from() is INCLUSIVE
    @Override
    public List<OverviewAggregate> readOverviewAggregates(String agentRollupId,
            AggregateQuery query, CassandraProfile profile) throws Exception {
        return splitListIfNeeded(agentRollupId, query,
                (id, q) -> delegate.readOverviewAggregates(id, q, profile));
    }

    // query.from() is INCLUSIVE
    @Override
    public List<PercentileAggregate> readPercentileAggregates(String agentRollupId,
            AggregateQuery query, CassandraProfile profile) throws Exception {
        return splitListIfNeeded(agentRollupId, query,
                (id, q) -> delegate.readPercentileAggregates(id, q, profile));
    }

    // query.from() is INCLUSIVE
    @Override
    public List<ThroughputAggregate> readThroughputAggregates(String agentRollupId,
            AggregateQuery query, CassandraProfile profile) throws Exception {
        return splitListIfNeeded(agentRollupId, query,
                (id, q) -> delegate.readThroughputAggregates(id, q, profile));
    }

    // query.from() is non-inclusive
    @Override
    public void mergeQueriesInto(String agentRollupId, AggregateQuery query,
            QueryCollector collector, CassandraProfile profile) throws Exception {
        splitMergeIfNeeded(agentRollupId, query,
                (id, q) -> delegate.mergeQueriesInto(id, q, collector, profile));
    }

    // query.from() is non-inclusive
    @Override
    public void mergeServiceCallsInto(String agentRollupId, AggregateQuery query,
            ServiceCallCollector collector, CassandraProfile profile) throws Exception {
        splitMergeIfNeeded(agentRollupId, query,
                (id, q) -> delegate.mergeServiceCallsInto(id, q, collector, profile));
    }

    // query.from() is non-inclusive
    @Override
    public void mergeMainThreadProfilesInto(String agentRollupId, AggregateQuery query,
            ProfileCollector collector, CassandraProfile profile) throws Exception {
        splitMergeIfNeeded(agentRollupId, query,
                (id, q) -> delegate.mergeMainThreadProfilesInto(id, q, collector, profile));
    }

    // query.from() is non-inclusive
    @Override
    public void mergeAuxThreadProfilesInto(String agentRollupId, AggregateQuery query,
            ProfileCollector collector, CassandraProfile profile) throws Exception {
        splitMergeIfNeeded(agentRollupId, query,
                (id, q) -> delegate.mergeAuxThreadProfilesInto(id, q, collector, profile));
    }

    @Override
    public @Nullable String readFullQueryText(String agentRollupId, String fullQueryTextSha1, CassandraProfile profile)
            throws Exception {
        String value = delegate.readFullQueryText(agentRollupId, fullQueryTextSha1, profile);
        if (value == null && clock.currentTimeMillis() < v09FqtLastExpirationTime
                && agentRollupIdsWithV09Data.contains(agentRollupId)) {
            value = delegate.readFullQueryText(V09Support.convertToV09(agentRollupId),
                    fullQueryTextSha1, profile);
        }
        return value;
    }

    // query.from() is non-inclusive
    @Override
    public boolean hasMainThreadProfile(String agentRollupId, AggregateQuery query, CassandraProfile profile)
            throws Exception {
        return splitCheckIfNeeded(agentRollupId, query,
                (id, q) -> delegate.hasMainThreadProfile(id, q, profile));
    }

    // query.from() is non-inclusive
    @Override
    public boolean hasAuxThreadProfile(String agentRollupId, AggregateQuery query, CassandraProfile profile)
            throws Exception {
        return splitCheckIfNeeded(agentRollupId, query,
                (id, q) -> delegate.hasAuxThreadProfile(id, q, profile));
    }

    // query.from() is non-inclusive
    @Override
    public boolean shouldHaveQueries(String agentRollupId, AggregateQuery query)
            throws Exception {
        return splitCheckIfNeeded(agentRollupId, query,
                (id, q) -> delegate.shouldHaveQueries(id, q));
    }

    // query.from() is non-inclusive
    @Override
    public boolean shouldHaveServiceCalls(String agentRollupId, AggregateQuery query)
            throws Exception {
        return splitCheckIfNeeded(agentRollupId, query,
                (id, q) -> delegate.shouldHaveServiceCalls(id, q));
    }

    // query.from() is non-inclusive
    @Override
    public boolean shouldHaveMainThreadProfile(String agentRollupId, AggregateQuery query)
            throws Exception {
        return splitCheckIfNeeded(agentRollupId, query,
                (id, q) -> delegate.shouldHaveMainThreadProfile(id, q));
    }

    // query.from() is non-inclusive
    @Override
    public boolean shouldHaveAuxThreadProfile(String agentRollupId, AggregateQuery query)
            throws Exception {
        return splitCheckIfNeeded(agentRollupId, query,
                (id, q) -> delegate.shouldHaveAuxThreadProfile(id, q));
    }

    @Override
    public void rollup(String agentRollupId) throws Exception {
        delegate.rollup(agentRollupId);
        if (agentRollupIdsWithV09Data.contains(agentRollupId)
                && clock.currentTimeMillis() < v09LastCaptureTime + DAYS.toMillis(30)) {
            delegate.rollup(V09Support.convertToV09(agentRollupId), agentRollupId,
                    V09Support.getParentV09(agentRollupId), V09Support.isLeaf(agentRollupId));
        }
    }

    private void splitMergeIfNeeded(String agentRollupId, SummaryQuery query,
            DelegateMergeAction<SummaryQuery> action) throws Exception {
        SummaryQueryPlan plan = getPlan(agentRollupId, query);
        SummaryQuery queryV09 = plan.queryV09();
        if (queryV09 != null) {
            action.merge(V09Support.convertToV09(agentRollupId), queryV09);
        }
        SummaryQuery queryPostV09 = plan.queryPostV09();
        if (queryPostV09 != null) {
            action.merge(agentRollupId, queryPostV09);
        }
    }

    private <T> List<T> splitListIfNeeded(String agentRollupId, AggregateQuery query,
            DelegateListAction<T> action) throws Exception {
        AggregateQueryPlan plan = getPlan(agentRollupId, query);
        AggregateQuery queryV09 = plan.queryV09();
        AggregateQuery queryPostV09 = plan.queryPostV09();
        if (queryV09 == null) {
            checkNotNull(queryPostV09);
            return action.list(agentRollupId, queryPostV09);
        } else if (queryPostV09 == null) {
            checkNotNull(queryV09);
            return action.list(V09Support.convertToV09(agentRollupId), queryV09);
        } else {
            List<T> list = new ArrayList<>();
            list.addAll(action.list(V09Support.convertToV09(agentRollupId), queryV09));
            list.addAll(action.list(agentRollupId, queryPostV09));
            return list;
        }
    }

    private void splitMergeIfNeeded(String agentRollupId, AggregateQuery query,
            DelegateMergeAction<AggregateQuery> action) throws Exception {
        AggregateQueryPlan plan = getPlan(agentRollupId, query);
        AggregateQuery queryV09 = plan.queryV09();
        if (queryV09 != null) {
            action.merge(V09Support.convertToV09(agentRollupId), queryV09);
        }
        AggregateQuery queryPostV09 = plan.queryPostV09();
        if (queryPostV09 != null) {
            action.merge(agentRollupId, queryPostV09);
        }
    }

    private boolean splitCheckIfNeeded(String agentRollupId, AggregateQuery query,
            DelegateBooleanAction action) throws Exception {
        AggregateQueryPlan plan = getPlan(agentRollupId, query);
        AggregateQuery queryV09 = plan.queryV09();
        if (queryV09 != null && action.check(V09Support.convertToV09(agentRollupId), queryV09)) {
            return true;
        }
        AggregateQuery queryPostV09 = plan.queryPostV09();
        return queryPostV09 != null && action.check(agentRollupId, queryPostV09);
    }

    private SummaryQueryPlan getPlan(String agentRollupId, SummaryQuery query) {
        if (query.from() <= v09LastCaptureTime
                && agentRollupIdsWithV09Data.contains(agentRollupId)) {
            if (query.to() <= v09LastCaptureTime) {
                return ImmutableSummaryQueryPlan.builder()
                        .queryV09(query)
                        .build();
            } else {
                return ImmutableSummaryQueryPlan.builder()
                        .queryV09(ImmutableSummaryQuery.copyOf(query)
                                .withTo(v09LastCaptureTime))
                        .queryPostV09(ImmutableSummaryQuery.copyOf(query)
                                .withFrom(v09LastCaptureTime + 1))
                        .build();
            }
        } else {
            return ImmutableSummaryQueryPlan.builder()
                    .queryPostV09(query)
                    .build();
        }
    }

    private AggregateQueryPlan getPlan(String agentRollupId, AggregateQuery query) {
        if (query.from() <= v09LastCaptureTime
                && agentRollupIdsWithV09Data.contains(agentRollupId)) {
            if (query.to() <= v09LastCaptureTime) {
                return ImmutableAggregateQueryPlan.builder()
                        .queryV09(query)
                        .build();
            } else {
                return ImmutableAggregateQueryPlan.builder()
                        .queryV09(ImmutableAggregateQuery.copyOf(query)
                                .withTo(v09LastCaptureTime))
                        .queryPostV09(ImmutableAggregateQuery.copyOf(query)
                                .withFrom(v09LastCaptureTime + 1))
                        .build();
            }
        } else {
            return ImmutableAggregateQueryPlan.builder()
                    .queryPostV09(query)
                    .build();
        }
    }

    @Override
    @OnlyUsedByTests
    public void truncateAll() throws Exception {
        delegate.truncateAll();
    }

    @Value.Immutable
    interface SummaryQueryPlan {
        @Nullable
        SummaryQuery queryV09();
        @Nullable
        SummaryQuery queryPostV09();
    }

    @Value.Immutable
    interface AggregateQueryPlan {
        @Nullable
        AggregateQuery queryV09();
        @Nullable
        AggregateQuery queryPostV09();
    }

    private interface DelegateMergeAction<Q> {
        void merge(String agentRollupId, Q query) throws Exception;
    }

    private interface DelegateListAction<T> {
        List<T> list(String agentRollupId, AggregateQuery query) throws Exception;
    }

    private interface DelegateBooleanAction {
        boolean check(String agentRollupId, AggregateQuery query) throws Exception;
    }
}
