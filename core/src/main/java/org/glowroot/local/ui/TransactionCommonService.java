/*
 * Copyright 2014-2015 the original author or authors.
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
package org.glowroot.local.ui;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.CharSource;

import org.glowroot.collector.Aggregate;
import org.glowroot.collector.AggregateCollector;
import org.glowroot.collector.AggregateIntervalCollector;
import org.glowroot.collector.ImmutableTransactionSummary;
import org.glowroot.collector.TransactionSummary;
import org.glowroot.common.ScratchBuffer;
import org.glowroot.local.store.AggregateDao;
import org.glowroot.local.store.AggregateDao.MergedAggregate;
import org.glowroot.local.store.AggregateDao.TransactionSummaryQuery;
import org.glowroot.local.store.AggregateDao.TransactionSummarySortOrder;
import org.glowroot.local.store.AggregateMerging;
import org.glowroot.local.store.AggregateProfileNode;
import org.glowroot.local.store.ImmutableTransactionSummaryQuery;
import org.glowroot.local.store.QueryResult;

import static com.google.common.base.Preconditions.checkNotNull;

class TransactionCommonService {

    private final AggregateDao aggregateDao;
    private final @Nullable AggregateCollector aggregateCollector;

    private final long fixedRollupMillis;

    TransactionCommonService(AggregateDao aggregateDao,
            @Nullable AggregateCollector aggregateCollector, long fixedRollupSeconds) {
        this.aggregateDao = aggregateDao;
        this.aggregateCollector = aggregateCollector;
        this.fixedRollupMillis = fixedRollupSeconds * 1000;
    }

    TransactionSummary readOverallSummary(String transactionType, long from, long to)
            throws SQLException {
        List<AggregateIntervalCollector> orderedIntervalCollectors =
                getOrderedIntervalCollectorsInRange(from, to);
        if (orderedIntervalCollectors.isEmpty()) {
            return aggregateDao.readOverallTransactionSummary(transactionType, from, to);
        }
        long revisedTo = getRevisedTo(to, orderedIntervalCollectors);
        TransactionSummary overallSummary =
                aggregateDao.readOverallTransactionSummary(transactionType, from, revisedTo);
        for (AggregateIntervalCollector intervalCollector : orderedIntervalCollectors) {
            TransactionSummary liveOverallSummary =
                    intervalCollector.getLiveOverallSummary(transactionType);
            if (liveOverallSummary != null) {
                overallSummary = combineTransactionSummaries(null, overallSummary,
                        liveOverallSummary);
            }
        }
        return overallSummary;
    }

    QueryResult<TransactionSummary> readTransactionSummaries(TransactionSummaryQuery query)
            throws SQLException {
        List<AggregateIntervalCollector> orderedIntervalCollectors =
                getOrderedIntervalCollectorsInRange(query.from(), query.to());
        if (orderedIntervalCollectors.isEmpty()) {
            return aggregateDao.readTransactionSummaries(query);
        }
        long revisedTo = getRevisedTo(query.to(), orderedIntervalCollectors);
        TransactionSummaryQuery revisedQuery =
                ((ImmutableTransactionSummaryQuery) query).withTo(revisedTo);
        QueryResult<TransactionSummary> queryResult =
                aggregateDao.readTransactionSummaries(revisedQuery);
        if (orderedIntervalCollectors.isEmpty()) {
            return queryResult;
        }
        return mergeInLiveTransactionSummaries(revisedQuery, queryResult,
                orderedIntervalCollectors);
    }

    long getProfileSampleCount(String transactionType, @Nullable String transactionName, long from,
            long to) throws SQLException {
        List<AggregateIntervalCollector> orderedIntervalCollectors =
                getOrderedIntervalCollectorsInRange(from, to);
        if (orderedIntervalCollectors.isEmpty()) {
            return getProfileSampleCountFromDao(transactionType, transactionName, from, to);
        }
        long revisedTo = getRevisedTo(to, orderedIntervalCollectors);
        long profileSampleCount =
                getProfileSampleCountFromDao(transactionType, transactionName, from, revisedTo);
        for (AggregateIntervalCollector intervalCollector : orderedIntervalCollectors) {
            profileSampleCount += intervalCollector.getLiveProfileSampleCount(transactionType,
                    transactionName);
        }
        return profileSampleCount;
    }

    boolean shouldHaveProfiles(String transactionType, @Nullable String transactionName, long from,
            long to) throws SQLException {
        if (transactionName == null) {
            return aggregateDao.shouldHaveOverallProfiles(transactionType, from, to);
        } else {
            return aggregateDao.shouldHaveTransactionProfiles(transactionType, transactionName,
                    from, to);
        }
    }

    boolean shouldHaveTraces(String transactionType, @Nullable String transactionName, long from,
            long to) throws SQLException {
        if (transactionName == null) {
            return aggregateDao.shouldHaveOverallTraces(transactionType, from, to);
        } else {
            return aggregateDao.shouldHaveTransactionTraces(transactionType, transactionName, from,
                    to);
        }
    }

    boolean shouldHaveErrorTraces(String transactionType, @Nullable String transactionName,
            long from, long to) throws SQLException {
        if (transactionName == null) {
            return aggregateDao.shouldHaveOverallErrorTraces(transactionType, from, to);
        } else {
            return aggregateDao.shouldHaveTransactionErrorTraces(transactionType, transactionName,
                    from, to);
        }
    }

    List<Aggregate> getAggregates(String transactionType, @Nullable String transactionName,
            long from, long to) throws Exception {
        int rollupLevel = getRollupLevel(from, to);
        List<AggregateIntervalCollector> orderedIntervalCollectors =
                getOrderedIntervalCollectorsInRange(from, to);
        long revisedTo = getRevisedTo(to, orderedIntervalCollectors);
        List<Aggregate> aggregates = getAggregatesFromDao(transactionType, transactionName, from,
                revisedTo, rollupLevel);
        if (rollupLevel == 0) {
            aggregates = Lists.newArrayList(aggregates);
            aggregates.addAll(getLiveAggregates(transactionType, transactionName,
                    orderedIntervalCollectors));
            return aggregates;
        }
        long revisedFrom = revisedTo - AggregateDao.ROLLUP_THRESHOLD_MILLIS;
        if (!aggregates.isEmpty()) {
            long lastRolledUpTime = aggregates.get(aggregates.size() - 1).captureTime();
            revisedFrom = Math.max(revisedFrom, lastRolledUpTime + 1);
        }
        List<Aggregate> orderedNonRolledUpAggregates = Lists.newArrayList();
        orderedNonRolledUpAggregates.addAll(getAggregatesFromDao(transactionType, transactionName,
                revisedFrom, revisedTo, 0));
        orderedNonRolledUpAggregates.addAll(getLiveAggregates(transactionType, transactionName,
                orderedIntervalCollectors));
        aggregates = Lists.newArrayList(aggregates);
        aggregates.addAll(rollUp(transactionType, transactionName, orderedNonRolledUpAggregates));
        return aggregates;
    }

    AggregateProfileNode getProfile(String transactionType, @Nullable String transactionName,
            long from, long to, double truncateLeafPercentage) throws Exception {
        List<CharSource> profiles = getProfiles(transactionType, transactionName, from, to);
        return AggregateMerging.getProfile(profiles, truncateLeafPercentage);
    }

    private List<AggregateIntervalCollector> getOrderedIntervalCollectorsInRange(long from,
            long to) {
        if (aggregateCollector == null) {
            return ImmutableList.of();
        }
        return aggregateCollector.getOrderedIntervalCollectorsInRange(from, to);
    }

    private long getProfileSampleCountFromDao(String transactionType,
            @Nullable String transactionName, long from, long to) throws SQLException {
        if (transactionName == null) {
            return aggregateDao.readOverallProfileSampleCount(transactionType, from, to);
        } else {
            return aggregateDao.readTransactionProfileSampleCount(transactionType, transactionName,
                    from, to);
        }
    }

    private List<Aggregate> getAggregatesFromDao(String transactionType,
            @Nullable String transactionName, long from, long to, int rollupLevel)
            throws SQLException {
        if (transactionName == null) {
            return aggregateDao.readOverallAggregates(transactionType, from, to, rollupLevel);
        } else {
            return aggregateDao.readTransactionAggregates(transactionType, transactionName, from,
                    to, rollupLevel);
        }
    }

    private List<CharSource> getProfiles(String transactionType, @Nullable String transactionName,
            long from, long to) throws Exception {
        List<AggregateIntervalCollector> orderedIntervalCollectors =
                getOrderedIntervalCollectorsInRange(from, to);
        if (orderedIntervalCollectors.isEmpty()) {
            return getProfilesFromDao(transactionType, transactionName, from, to);
        }
        long revisedTo = getRevisedTo(to, orderedIntervalCollectors);
        List<CharSource> profiles =
                getProfilesFromDao(transactionType, transactionName, from, revisedTo);
        profiles = Lists.newArrayList(profiles);
        for (AggregateIntervalCollector intervalCollector : orderedIntervalCollectors) {
            String profile =
                    intervalCollector.getLiveProfileJson(transactionType, transactionName);
            if (profile != null) {
                profiles.add(CharSource.wrap(profile));
            }
        }
        return profiles;
    }

    private List<CharSource> getProfilesFromDao(String transactionType,
            @Nullable String transactionName, long from, long to) throws SQLException {
        if (transactionName == null) {
            return aggregateDao.readOverallProfiles(transactionType, from, to);
        } else {
            return aggregateDao.readTransactionProfiles(transactionType, transactionName, from, to);
        }
    }

    private List<Aggregate> rollUp(String transactionType, @Nullable String transactionName,
            List<Aggregate> orderedNonRolledUpAggregates) throws Exception {
        List<Aggregate> rolledUpAggregates = Lists.newArrayList();
        ScratchBuffer scratchBuffer = new ScratchBuffer();
        MergedAggregate currMergedAggregate = null;
        long currRollupTime = Long.MIN_VALUE;
        for (Aggregate nonRolledUpAggregate : orderedNonRolledUpAggregates) {
            long rollupTime = (long) Math.ceil(nonRolledUpAggregate.captureTime()
                    / (double) fixedRollupMillis) * fixedRollupMillis;
            if (rollupTime != currRollupTime && currMergedAggregate != null) {
                rolledUpAggregates.add(currMergedAggregate.toAggregate(scratchBuffer));
                currMergedAggregate = new MergedAggregate(0, transactionType, transactionName);
            }
            if (currMergedAggregate == null) {
                currMergedAggregate = new MergedAggregate(0, transactionType, transactionName);
            }
            currRollupTime = rollupTime;
            // capture time is the largest of the ordered aggregate capture times
            currMergedAggregate.setCaptureTime(nonRolledUpAggregate.captureTime());
            currMergedAggregate.addTotalMicros(nonRolledUpAggregate.totalMicros());
            currMergedAggregate.addErrorCount(nonRolledUpAggregate.errorCount());
            currMergedAggregate.addTransactionCount(nonRolledUpAggregate.transactionCount());
            currMergedAggregate.addTotalCpuMicros(nonRolledUpAggregate.totalCpuMicros());
            currMergedAggregate.addTotalBlockedMicros(nonRolledUpAggregate.totalBlockedMicros());
            currMergedAggregate.addTotalWaitedMicros(nonRolledUpAggregate.totalWaitedMicros());
            currMergedAggregate.addTotalAllocatedBytes(nonRolledUpAggregate.totalAllocatedBytes());
            currMergedAggregate.addProfileSampleCount(nonRolledUpAggregate.profileSampleCount());
            currMergedAggregate.addMetrics(nonRolledUpAggregate.metrics());
            currMergedAggregate.addHistogram(nonRolledUpAggregate.histogram());
        }
        if (currMergedAggregate != null) {
            // roll up final one
            rolledUpAggregates.add(currMergedAggregate.toAggregate(scratchBuffer));
        }
        return rolledUpAggregates;
    }

    private static int getRollupLevel(long from, long to) {
        if (to - from <= AggregateDao.ROLLUP_THRESHOLD_MILLIS) {
            return 0;
        } else {
            return 1;
        }
    }

    private static long getRevisedTo(long to,
            List<AggregateIntervalCollector> orderedIntervalCollectors) {
        if (orderedIntervalCollectors.isEmpty()) {
            return to;
        } else {
            // -1 since query 'to' is inclusive
            // this way don't need to worry about de-dupping between live and stored aggregates
            return orderedIntervalCollectors.get(0).getEndTime() - 1;
        }
    }

    private static QueryResult<TransactionSummary> mergeInLiveTransactionSummaries(
            TransactionSummaryQuery query, QueryResult<TransactionSummary> queryResult,
            List<AggregateIntervalCollector> intervalCollectors) {
        List<TransactionSummary> transactionSummaries = queryResult.records();
        Map<String, TransactionSummary> transactionSummaryMap = Maps.newHashMap();
        for (TransactionSummary transactionSummary : transactionSummaries) {
            String transactionName = transactionSummary.transactionName();
            // transaction name is only null for overall summary
            checkNotNull(transactionName);
            transactionSummaryMap.put(transactionName, transactionSummary);
        }
        for (AggregateIntervalCollector intervalCollector : intervalCollectors) {
            List<TransactionSummary> liveTransactionSummaries =
                    intervalCollector.getLiveTransactionSummaries(query.transactionType());
            for (TransactionSummary liveTransactionSummary : liveTransactionSummaries) {
                String transactionName = liveTransactionSummary.transactionName();
                // transaction name is only null for overall summary
                checkNotNull(transactionName);
                TransactionSummary transactionSummary = transactionSummaryMap.get(transactionName);
                if (transactionSummary == null) {
                    transactionSummaryMap.put(transactionName, liveTransactionSummary);
                } else {
                    transactionSummaryMap.put(transactionName,
                            combineTransactionSummaries(transactionName, transactionSummary,
                                    liveTransactionSummary));
                }
            }
        }
        transactionSummaries =
                sortTransactionSummaries(transactionSummaryMap.values(), query.sortOrder());
        boolean moreAvailable = queryResult.moreAvailable();
        if (transactionSummaries.size() > query.limit()) {
            moreAvailable = true;
            transactionSummaries = transactionSummaries.subList(0, query.limit());
        }
        return new QueryResult<TransactionSummary>(transactionSummaries, moreAvailable);
    }

    private static List<Aggregate> getLiveAggregates(String transactionType,
            @Nullable String transactionName, List<AggregateIntervalCollector> intervalCollectors)
            throws IOException {
        List<Aggregate> aggregates = Lists.newArrayList();
        for (AggregateIntervalCollector intervalCollector : intervalCollectors) {
            Aggregate liveAggregate =
                    intervalCollector.getLiveAggregate(transactionType, transactionName);
            if (liveAggregate != null) {
                aggregates.add(liveAggregate);
            }
        }
        return aggregates;
    }

    private static TransactionSummary combineTransactionSummaries(@Nullable String transactionName,
            TransactionSummary summary1, TransactionSummary summary2) {
        return ImmutableTransactionSummary.builder()
                .transactionName(transactionName)
                .totalMicros(summary1.totalMicros() + summary2.totalMicros())
                .transactionCount(summary1.transactionCount() + summary2.transactionCount())
                .build();
    }

    private static List<TransactionSummary> sortTransactionSummaries(
            Iterable<TransactionSummary> transactionSummaries,
            TransactionSummarySortOrder sortOrder) {
        switch (sortOrder) {
            case TOTAL_TIME:
                return TransactionSummary.orderingByTotalTimeDesc.immutableSortedCopy(
                        transactionSummaries);
            case AVERAGE_TIME:
                return TransactionSummary.orderingByAverageTimeDesc.immutableSortedCopy(
                        transactionSummaries);
            case THROUGHPUT:
                return TransactionSummary.orderingByTransactionCountDesc.immutableSortedCopy(
                        transactionSummaries);
            default:
                throw new AssertionError("Unexpected sort order: " + sortOrder);
        }
    }
}
