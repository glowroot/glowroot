/*
 * Copyright 2017 the original author or authors.
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

import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.collect.Lists;
import org.immutables.value.Value;

import org.glowroot.central.repo.AgentRollupIds;
import org.glowroot.central.repo.AggregateDao;
import org.glowroot.central.repo.AggregateDaoImpl;
import org.glowroot.common.live.ImmutableOverallQuery;
import org.glowroot.common.live.ImmutableTransactionQuery;
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
    public void store(String agentId, long captureTime,
            List<OldAggregatesByType> aggregatesByTypeList,
            List<Aggregate.SharedQueryText> initialSharedQueryTexts) throws Exception {
        if (captureTime <= v09LastCaptureTime
                && agentRollupIdsWithV09Data.contains(agentId)) {
            delegate.store(V09Support.convertToV09(agentId),
                    V09Support.getAgentRollupIdsV09(agentId), agentId,
                    AgentRollupIds.getAgentRollupIds(agentId), captureTime, aggregatesByTypeList,
                    initialSharedQueryTexts);
        } else {
            delegate.store(agentId, captureTime, aggregatesByTypeList, initialSharedQueryTexts);
        }
    }

    // query.from() is non-inclusive
    @Override
    public void mergeOverallSummaryInto(String agentRollupId, OverallQuery query,
            OverallSummaryCollector collector) throws Exception {
        splitMergeIfNeeded(agentRollupId, query,
                (id, q) -> delegate.mergeOverallSummaryInto(id, q, collector));
    }

    // query.from() is non-inclusive
    @Override
    public void mergeTransactionSummariesInto(String agentRollupId, OverallQuery query,
            SummarySortOrder sortOrder, int limit, TransactionSummaryCollector collector)
            throws Exception {
        splitMergeIfNeeded(agentRollupId, query, (id, q) -> delegate
                .mergeTransactionSummariesInto(id, q, sortOrder, limit, collector));
    }

    // query.from() is non-inclusive
    @Override
    public void mergeOverallErrorSummaryInto(String agentRollupId, OverallQuery query,
            OverallErrorSummaryCollector collector) throws Exception {
        splitMergeIfNeeded(agentRollupId, query,
                (id, q) -> delegate.mergeOverallErrorSummaryInto(id, q, collector));
    }

    // query.from() is non-inclusive
    @Override
    public void mergeTransactionErrorSummariesInto(String agentRollupId, OverallQuery query,
            ErrorSummarySortOrder sortOrder, int limit, TransactionErrorSummaryCollector collector)
            throws Exception {
        splitMergeIfNeeded(agentRollupId, query, (id, q) -> delegate
                .mergeTransactionErrorSummariesInto(id, q, sortOrder, limit, collector));
    }

    // query.from() is INCLUSIVE
    @Override
    public List<OverviewAggregate> readOverviewAggregates(String agentRollupId,
            TransactionQuery query) throws Exception {
        return splitListIfNeeded(agentRollupId, query,
                (id, q) -> delegate.readOverviewAggregates(id, q));
    }

    // query.from() is INCLUSIVE
    @Override
    public List<PercentileAggregate> readPercentileAggregates(String agentRollupId,
            TransactionQuery query) throws Exception {
        return splitListIfNeeded(agentRollupId, query,
                (id, q) -> delegate.readPercentileAggregates(id, q));
    }

    // query.from() is INCLUSIVE
    @Override
    public List<ThroughputAggregate> readThroughputAggregates(String agentRollupId,
            TransactionQuery query) throws Exception {
        return splitListIfNeeded(agentRollupId, query,
                (id, q) -> delegate.readThroughputAggregates(id, q));
    }

    @Override
    public @Nullable String readFullQueryText(String agentRollupId, String fullQueryTextSha1)
            throws Exception {
        String value = delegate.readFullQueryText(agentRollupId, fullQueryTextSha1);
        if (value == null && clock.currentTimeMillis() < v09FqtLastExpirationTime
                && agentRollupIdsWithV09Data.contains(agentRollupId)) {
            value = delegate.readFullQueryText(V09Support.convertToV09(agentRollupId),
                    fullQueryTextSha1);
        }
        return value;
    }

    // query.from() is non-inclusive
    @Override
    public void mergeQueriesInto(String agentRollupId, TransactionQuery query,
            QueryCollector collector) throws Exception {
        splitMergeIfNeeded(agentRollupId, query,
                (id, q) -> delegate.mergeQueriesInto(id, q, collector));
    }

    // query.from() is non-inclusive
    @Override
    public void mergeServiceCallsInto(String agentRollupId, TransactionQuery query,
            ServiceCallCollector collector) throws Exception {
        splitMergeIfNeeded(agentRollupId, query,
                (id, q) -> delegate.mergeServiceCallsInto(id, q, collector));
    }

    // query.from() is non-inclusive
    @Override
    public void mergeMainThreadProfilesInto(String agentRollupId, TransactionQuery query,
            ProfileCollector collector) throws Exception {
        splitMergeIfNeeded(agentRollupId, query,
                (id, q) -> delegate.mergeMainThreadProfilesInto(id, q, collector));
    }

    // query.from() is non-inclusive
    @Override
    public void mergeAuxThreadProfilesInto(String agentRollupId, TransactionQuery query,
            ProfileCollector collector) throws Exception {
        splitMergeIfNeeded(agentRollupId, query,
                (id, q) -> delegate.mergeAuxThreadProfilesInto(id, q, collector));
    }

    // query.from() is non-inclusive
    @Override
    public boolean hasMainThreadProfile(String agentRollupId, TransactionQuery query)
            throws Exception {
        return splitCheckIfNeeded(agentRollupId, query,
                (id, q) -> delegate.hasMainThreadProfile(id, q));
    }

    // query.from() is non-inclusive
    @Override
    public boolean hasAuxThreadProfile(String agentRollupId, TransactionQuery query)
            throws Exception {
        return splitCheckIfNeeded(agentRollupId, query,
                (id, q) -> delegate.hasAuxThreadProfile(id, q));
    }

    // query.from() is non-inclusive
    @Override
    public boolean shouldHaveQueries(String agentRollupId, TransactionQuery query)
            throws Exception {
        return splitCheckIfNeeded(agentRollupId, query,
                (id, q) -> delegate.shouldHaveQueries(id, q));
    }

    // query.from() is non-inclusive
    @Override
    public boolean shouldHaveServiceCalls(String agentRollupId, TransactionQuery query)
            throws Exception {
        return splitCheckIfNeeded(agentRollupId, query,
                (id, q) -> delegate.shouldHaveServiceCalls(id, q));
    }

    // query.from() is non-inclusive
    @Override
    public boolean shouldHaveMainThreadProfile(String agentRollupId, TransactionQuery query)
            throws Exception {
        return splitCheckIfNeeded(agentRollupId, query,
                (id, q) -> delegate.shouldHaveMainThreadProfile(id, q));
    }

    // query.from() is non-inclusive
    @Override
    public boolean shouldHaveAuxThreadProfile(String agentRollupId, TransactionQuery query)
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

    private void splitMergeIfNeeded(String agentRollupId, OverallQuery query,
            DelegateMergeAction<OverallQuery> action) throws Exception {
        OverallQueryPlan plan = getPlan(agentRollupId, query);
        OverallQuery queryV09 = plan.queryV09();
        if (queryV09 != null) {
            action.merge(V09Support.convertToV09(agentRollupId), queryV09);
        }
        OverallQuery queryPostV09 = plan.queryPostV09();
        if (queryPostV09 != null) {
            action.merge(agentRollupId, queryPostV09);
        }
    }

    private <T> List<T> splitListIfNeeded(String agentRollupId, TransactionQuery query,
            DelegateListAction<T> action) throws Exception {
        TransactionQueryPlan plan = getPlan(agentRollupId, query);
        TransactionQuery queryV09 = plan.queryV09();
        TransactionQuery queryPostV09 = plan.queryPostV09();
        if (queryV09 == null) {
            checkNotNull(queryPostV09);
            return action.list(agentRollupId, queryPostV09);
        } else if (queryPostV09 == null) {
            checkNotNull(queryV09);
            return action.list(V09Support.convertToV09(agentRollupId), queryV09);
        } else {
            List<T> list = Lists.newArrayList();
            list.addAll(action.list(V09Support.convertToV09(agentRollupId), queryV09));
            list.addAll(action.list(agentRollupId, queryPostV09));
            return list;
        }
    }

    private void splitMergeIfNeeded(String agentRollupId, TransactionQuery query,
            DelegateMergeAction<TransactionQuery> action) throws Exception {
        TransactionQueryPlan plan = getPlan(agentRollupId, query);
        TransactionQuery queryV09 = plan.queryV09();
        if (queryV09 != null) {
            action.merge(V09Support.convertToV09(agentRollupId), queryV09);
        }
        TransactionQuery queryPostV09 = plan.queryPostV09();
        if (queryPostV09 != null) {
            action.merge(agentRollupId, queryPostV09);
        }
    }

    private boolean splitCheckIfNeeded(String agentRollupId, TransactionQuery query,
            DelegateBooleanAction action) throws Exception {
        TransactionQueryPlan plan = getPlan(agentRollupId, query);
        TransactionQuery queryV09 = plan.queryV09();
        if (queryV09 != null && action.check(V09Support.convertToV09(agentRollupId), queryV09)) {
            return true;
        }
        TransactionQuery queryPostV09 = plan.queryPostV09();
        return queryPostV09 != null && action.check(agentRollupId, queryPostV09);
    }

    private OverallQueryPlan getPlan(String agentRollupId, OverallQuery query) {
        if (query.from() <= v09LastCaptureTime
                && agentRollupIdsWithV09Data.contains(agentRollupId)) {
            if (query.to() <= v09LastCaptureTime) {
                return ImmutableOverallQueryPlan.builder()
                        .queryV09(query)
                        .build();
            } else {
                return ImmutableOverallQueryPlan.builder()
                        .queryV09(ImmutableOverallQuery.copyOf(query)
                                .withTo(v09LastCaptureTime))
                        .queryPostV09(ImmutableOverallQuery.copyOf(query)
                                .withFrom(v09LastCaptureTime + 1))
                        .build();
            }
        } else {
            return ImmutableOverallQueryPlan.builder()
                    .queryPostV09(query)
                    .build();
        }
    }

    private TransactionQueryPlan getPlan(String agentRollupId, TransactionQuery query) {
        if (query.from() <= v09LastCaptureTime
                && agentRollupIdsWithV09Data.contains(agentRollupId)) {
            if (query.to() <= v09LastCaptureTime) {
                return ImmutableTransactionQueryPlan.builder()
                        .queryV09(query)
                        .build();
            } else {
                return ImmutableTransactionQueryPlan.builder()
                        .queryV09(ImmutableTransactionQuery.copyOf(query)
                                .withTo(v09LastCaptureTime))
                        .queryPostV09(ImmutableTransactionQuery.copyOf(query)
                                .withFrom(v09LastCaptureTime + 1))
                        .build();
            }
        } else {
            return ImmutableTransactionQueryPlan.builder()
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
    interface OverallQueryPlan {
        @Nullable
        OverallQuery queryV09();
        @Nullable
        OverallQuery queryPostV09();
    }

    @Value.Immutable
    interface TransactionQueryPlan {
        @Nullable
        TransactionQuery queryV09();
        @Nullable
        TransactionQuery queryPostV09();
    }

    private interface DelegateMergeAction<Q> {
        void merge(String agentRollupId, Q query) throws Exception;
    }

    private interface DelegateListAction<T> {
        List<T> list(String agentRollupId, TransactionQuery query) throws Exception;
    }

    private interface DelegateBooleanAction {
        boolean check(String agentRollupId, TransactionQuery query) throws Exception;
    }
}
