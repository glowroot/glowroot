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
package org.glowroot.ui;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.primitives.Doubles;
import com.google.common.primitives.Longs;

import org.glowroot.common.live.ImmutableErrorPoint;
import org.glowroot.common.live.ImmutableOverallErrorSummary;
import org.glowroot.common.live.ImmutableTransactionErrorSummary;
import org.glowroot.common.live.LiveAggregateRepository;
import org.glowroot.common.live.LiveAggregateRepository.ErrorPoint;
import org.glowroot.common.live.LiveAggregateRepository.LiveResult;
import org.glowroot.common.live.LiveAggregateRepository.OverallErrorSummary;
import org.glowroot.common.live.LiveAggregateRepository.TransactionErrorSummary;
import org.glowroot.storage.repo.AggregateRepository;
import org.glowroot.storage.repo.AggregateRepository.ErrorSummaryQuery;
import org.glowroot.storage.repo.ConfigRepository.RollupConfig;
import org.glowroot.storage.repo.ImmutableErrorSummaryQuery;
import org.glowroot.storage.repo.Result;
import org.glowroot.storage.repo.Utils;

import static com.google.common.base.Preconditions.checkNotNull;

class ErrorCommonService {

    @VisibleForTesting
    static final Ordering<TransactionErrorSummary> orderingByErrorCountDesc =
            new Ordering<TransactionErrorSummary>() {
                @Override
                public int compare(TransactionErrorSummary left, TransactionErrorSummary right) {
                    return Longs.compare(right.errorCount(), left.errorCount());
                }
            };

    @VisibleForTesting
    static final Ordering<TransactionErrorSummary> orderingByErrorRateDesc =
            new Ordering<TransactionErrorSummary>() {
                @Override
                public int compare(TransactionErrorSummary left, TransactionErrorSummary right) {
                    return Doubles.compare(right.errorCount() / (double) right.transactionCount(),
                            left.errorCount() / (double) left.transactionCount());
                }
            };

    private final AggregateRepository aggregateRepository;
    private final LiveAggregateRepository liveAggregateRepository;
    private final ImmutableList<RollupConfig> rollupConfigs;

    ErrorCommonService(AggregateRepository aggregateRepository,
            LiveAggregateRepository liveAggregateRepository, List<RollupConfig> rollupConfigs) {
        this.aggregateRepository = aggregateRepository;
        this.liveAggregateRepository = liveAggregateRepository;
        this.rollupConfigs = ImmutableList.copyOf(rollupConfigs);
    }

    // from is non-inclusive
    OverallErrorSummary readOverallErrorSummary(String serverGroup, String transactionType,
            long from, long to) throws Exception {
        int rollupLevel = aggregateRepository.getRollupLevelForView(serverGroup, from, to);
        LiveResult<OverallErrorSummary> liveResult = liveAggregateRepository
                .getLiveOverallErrorSummary(serverGroup, transactionType, from, to);
        if (liveResult == null) {
            return aggregateRepository.readOverallErrorSummary(serverGroup, transactionType, from,
                    to, rollupLevel);
        }
        // -1 since query 'to' is inclusive
        // this way don't need to worry about de-dupping between live and stored aggregates
        long revisedTo = liveResult.initialCaptureTime() - 1;
        OverallErrorSummary overallSummary =
                aggregateRepository.readOverallErrorSummary(serverGroup,
                        transactionType, from, revisedTo, rollupLevel);
        for (OverallErrorSummary liveOverallErrorSummary : liveResult.get()) {
            overallSummary = combineOverallErrorSummaries(overallSummary, liveOverallErrorSummary);
        }
        return overallSummary;
    }

    // query.from() is non-inclusive
    Result<TransactionErrorSummary> readTransactionErrorSummaries(ErrorSummaryQuery query)
            throws Exception {
        int rollupLevel = aggregateRepository.getRollupLevelForView(query.serverGroup(),
                query.from(), query.to());
        LiveResult<List<TransactionErrorSummary>> liveResult =
                liveAggregateRepository.getLiveTransactionErrorSummaries(query.serverGroup(),
                        query.transactionType(), query.from(), query.to());
        if (liveResult == null) {
            return aggregateRepository.readTransactionErrorSummaries(query, rollupLevel);
        }
        // -1 since query 'to' is inclusive
        // this way don't need to worry about de-dupping between live and stored aggregates
        long revisedTo = liveResult.initialCaptureTime() - 1;
        ErrorSummaryQuery revisedQuery =
                ImmutableErrorSummaryQuery.builder().copyFrom(query).to(revisedTo).build();
        Result<TransactionErrorSummary> queryResult =
                aggregateRepository.readTransactionErrorSummaries(revisedQuery, rollupLevel);
        return mergeInLiveTransactionErrorSummaries(revisedQuery, queryResult, liveResult.get());
    }

    List<ErrorPoint> readErrorPoints(String serverGroup, String transactionType,
            @Nullable String transactionName, long from, long to, long liveCaptureTime)
                    throws Exception {
        int rollupLevel = aggregateRepository.getRollupLevelForView(serverGroup, from, to);
        LiveResult<ErrorPoint> liveResult = liveAggregateRepository.getLiveErrorPoints(serverGroup,
                transactionType, transactionName, from, to, liveCaptureTime);
        // -1 since query 'to' is inclusive
        // this way don't need to worry about de-dupping between live and stored aggregates
        long revisedTo = liveResult == null ? to : liveResult.initialCaptureTime() - 1;
        List<ErrorPoint> errorPoints = readErrorPointsFromDao(serverGroup, transactionType,
                transactionName, from, revisedTo, rollupLevel);
        if (rollupLevel == 0) {
            errorPoints = Lists.newArrayList(errorPoints);
            if (liveResult != null) {
                errorPoints.addAll(liveResult.get());
            }
            return errorPoints;
        }
        long nonRolledUpFrom = from;
        if (!errorPoints.isEmpty()) {
            long lastRolledUpTime = errorPoints.get(errorPoints.size() - 1).captureTime();
            nonRolledUpFrom = Math.max(nonRolledUpFrom, lastRolledUpTime + 1);
        }
        List<ErrorPoint> orderedNonRolledUpErrorPoints = Lists.newArrayList();
        orderedNonRolledUpErrorPoints.addAll(readErrorPointsFromDao(serverGroup, transactionType,
                transactionName, nonRolledUpFrom, revisedTo, 0));
        if (liveResult != null) {
            orderedNonRolledUpErrorPoints.addAll(liveResult.get());
        }
        errorPoints = Lists.newArrayList(errorPoints);
        errorPoints.addAll(rollUp(orderedNonRolledUpErrorPoints, liveCaptureTime, rollupLevel));
        return errorPoints;
    }

    private List<ErrorPoint> rollUp(List<ErrorPoint> orderedNonRolledUpErrorPoints,
            long liveCaptureTime, int rollupLevel) {
        long fixedIntervalMillis = rollupConfigs.get(rollupLevel).intervalMillis();
        List<ErrorPoint> rolledUpErrorPoints = Lists.newArrayList();
        long currRollupTime = Long.MIN_VALUE;
        long currErrorCount = 0;
        long currTransactionCount = 0;
        for (ErrorPoint errorPoint : orderedNonRolledUpErrorPoints) {
            long rollupTime =
                    Utils.getNextRollupTime(errorPoint.captureTime(), fixedIntervalMillis);
            if (rollupTime != currRollupTime && currTransactionCount != 0) {
                rolledUpErrorPoints
                        .add(ImmutableErrorPoint.of(Math.min(currRollupTime, liveCaptureTime),
                                currErrorCount, currTransactionCount));
                currErrorCount = 0;
                currTransactionCount = 0;
            }
            currRollupTime = rollupTime;
            currErrorCount += errorPoint.errorCount();
            currTransactionCount += errorPoint.transactionCount();
        }
        if (currTransactionCount != 0) {
            rolledUpErrorPoints
                    .add(ImmutableErrorPoint.of(Math.min(currRollupTime, liveCaptureTime),
                            currErrorCount, currTransactionCount));
        }
        return rolledUpErrorPoints;
    }

    private List<ErrorPoint> readErrorPointsFromDao(String serverGroup, String transactionType,
            @Nullable String transactionName, long from, long to, int rollupLevel)
                    throws Exception {
        if (transactionName == null) {
            return aggregateRepository.readOverallErrorPoints(serverGroup, transactionType, from,
                    to, rollupLevel);
        } else {
            return aggregateRepository.readTransactionErrorPoints(serverGroup, transactionType,
                    transactionName, from, to, rollupLevel);
        }
    }

    private static OverallErrorSummary combineOverallErrorSummaries(OverallErrorSummary summary1,
            OverallErrorSummary summary2) {
        return ImmutableOverallErrorSummary.builder()
                .errorCount(summary1.errorCount() + summary2.errorCount())
                .transactionCount(summary1.transactionCount() + summary2.transactionCount())
                .build();
    }

    private static TransactionErrorSummary combineTransactionErrorSummaries(String transactionName,
            TransactionErrorSummary summary1, TransactionErrorSummary summary2) {
        return ImmutableTransactionErrorSummary.builder()
                .transactionName(transactionName)
                .errorCount(summary1.errorCount() + summary2.errorCount())
                .transactionCount(summary1.transactionCount() + summary2.transactionCount())
                .build();
    }

    private static Result<TransactionErrorSummary> mergeInLiveTransactionErrorSummaries(
            ErrorSummaryQuery query, Result<TransactionErrorSummary> queryResult,
            List<List<TransactionErrorSummary>> liveErrorSummaries) {
        List<TransactionErrorSummary> errorSummaries = queryResult.records();
        Map<String, TransactionErrorSummary> errorSummaryMap = Maps.newHashMap();
        for (TransactionErrorSummary errorSummary : errorSummaries) {
            String transactionName = errorSummary.transactionName();
            // transaction name is only null for overall summary
            checkNotNull(transactionName);
            errorSummaryMap.put(transactionName, errorSummary);
        }
        for (List<TransactionErrorSummary> mid : liveErrorSummaries) {
            for (TransactionErrorSummary liveErrorSummary : mid) {
                String transactionName = liveErrorSummary.transactionName();
                // transaction name is only null for overall summary
                checkNotNull(transactionName);
                TransactionErrorSummary errorSummary = errorSummaryMap.get(transactionName);
                if (errorSummary == null) {
                    errorSummaryMap.put(transactionName, liveErrorSummary);
                } else {
                    errorSummaryMap.put(transactionName, combineTransactionErrorSummaries(
                            transactionName, errorSummary, liveErrorSummary));
                }
            }
        }
        List<TransactionErrorSummary> mergedErrorSummaries = Lists.newArrayList();
        for (TransactionErrorSummary errorSummary : errorSummaryMap.values()) {
            if (errorSummary.errorCount() > 0) {
                mergedErrorSummaries.add(errorSummary);
            }
        }
        mergedErrorSummaries = sortErrorSummaries(mergedErrorSummaries, query.sortOrder());
        boolean moreAvailable = queryResult.moreAvailable();
        if (mergedErrorSummaries.size() > query.limit()) {
            moreAvailable = true;
            mergedErrorSummaries = mergedErrorSummaries.subList(0, query.limit());
        }
        return new Result<TransactionErrorSummary>(mergedErrorSummaries, moreAvailable);
    }

    private static List<TransactionErrorSummary> sortErrorSummaries(
            Iterable<TransactionErrorSummary> errorSummaries,
            AggregateRepository.ErrorSummarySortOrder sortOrder) {
        switch (sortOrder) {
            case ERROR_COUNT:
                return orderingByErrorCountDesc.immutableSortedCopy(errorSummaries);
            case ERROR_RATE:
                return orderingByErrorRateDesc.immutableSortedCopy(errorSummaries);
            default:
                throw new AssertionError("Unexpected sort order: " + sortOrder);
        }
    }
}
