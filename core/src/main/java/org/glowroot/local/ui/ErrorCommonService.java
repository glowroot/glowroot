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

import org.glowroot.collector.AggregateCollector;
import org.glowroot.collector.AggregateIntervalCollector;
import org.glowroot.collector.ErrorPoint;
import org.glowroot.collector.ErrorSummary;
import org.glowroot.local.store.AggregateDao;
import org.glowroot.local.store.AggregateDao.ErrorSummarySortOrder;
import org.glowroot.local.store.ErrorSummaryQuery;
import org.glowroot.local.store.QueryResult;

import static com.google.common.base.Preconditions.checkNotNull;

class ErrorCommonService {

    private final AggregateDao aggregateDao;
    private final @Nullable AggregateCollector aggregateCollector;

    private final long fixedRollup1Millis;
    private final long fixedRollup2Millis;

    ErrorCommonService(AggregateDao aggregateDao, @Nullable AggregateCollector aggregateCollector,
            long fixedRollup1Seconds, long fixedRollup2Seconds) {
        this.aggregateDao = aggregateDao;
        this.aggregateCollector = aggregateCollector;
        this.fixedRollup1Millis = fixedRollup1Seconds * 1000;
        this.fixedRollup2Millis = fixedRollup2Seconds * 1000;
    }

    ErrorSummary readOverallErrorSummary(String transactionType, long from, long to)
            throws SQLException {
        List<AggregateIntervalCollector> orderedIntervalCollectors =
                getOrderedIntervalCollectorsInRange(from, to);
        if (orderedIntervalCollectors.isEmpty()) {
            return aggregateDao.readOverallErrorSummary(transactionType, from, to);
        }
        long revisedTo = getRevisedTo(to, orderedIntervalCollectors);
        ErrorSummary overallSummary =
                aggregateDao.readOverallErrorSummary(transactionType, from, revisedTo);
        for (AggregateIntervalCollector intervalCollector : orderedIntervalCollectors) {
            ErrorSummary liveOverallSummary =
                    intervalCollector.getLiveOverallErrorSummary(transactionType);
            if (liveOverallSummary != null) {
                overallSummary = combineErrorSummaries(null, overallSummary, liveOverallSummary);
            }
        }
        return overallSummary;
    }

    QueryResult<ErrorSummary> readTransactionErrorSummaries(ErrorSummaryQuery query)
            throws SQLException {
        List<AggregateIntervalCollector> orderedIntervalCollectors =
                getOrderedIntervalCollectorsInRange(query.from(), query.to());
        if (orderedIntervalCollectors.isEmpty()) {
            return aggregateDao.readTransactionErrorSummaries(query);
        }
        long revisedTo = getRevisedTo(query.to(), orderedIntervalCollectors);
        ErrorSummaryQuery revisedQuery = query.withTo(revisedTo);
        QueryResult<ErrorSummary> queryResult =
                aggregateDao.readTransactionErrorSummaries(revisedQuery);
        if (orderedIntervalCollectors.isEmpty()) {
            return queryResult;
        }
        return mergeInLiveTransactionErrorSummaries(revisedQuery, queryResult,
                orderedIntervalCollectors);
    }

    List<ErrorPoint> readErrorPoints(String transactionType, @Nullable String transactionName,
            long from, long to, long liveCaptureTime) throws Exception {
        int rollupLevel = aggregateDao.getRollupLevelForView(from, to);
        List<AggregateIntervalCollector> orderedIntervalCollectors =
                getOrderedIntervalCollectorsInRange(from, to);
        long revisedTo = getRevisedTo(to, orderedIntervalCollectors);
        List<ErrorPoint> errorPoints = readErrorPointsFromDao(transactionType, transactionName,
                from, revisedTo, rollupLevel);
        if (rollupLevel == 0) {
            errorPoints = Lists.newArrayList(errorPoints);
            errorPoints.addAll(getLiveErrorPoints(transactionType, transactionName,
                    orderedIntervalCollectors, liveCaptureTime));
            return errorPoints;
        }
        long nonRolledUpFrom = from;
        if (!errorPoints.isEmpty()) {
            long lastRolledUpTime = errorPoints.get(errorPoints.size() - 1).captureTime();
            nonRolledUpFrom = Math.max(nonRolledUpFrom, lastRolledUpTime + 1);
        }
        List<ErrorPoint> orderedNonRolledUpErrorPoints = Lists.newArrayList();
        orderedNonRolledUpErrorPoints.addAll(readErrorPointsFromDao(transactionType,
                transactionName, nonRolledUpFrom, revisedTo, 0));
        orderedNonRolledUpErrorPoints.addAll(getLiveErrorPoints(transactionType, transactionName,
                orderedIntervalCollectors, liveCaptureTime));
        errorPoints = Lists.newArrayList(errorPoints);
        errorPoints.addAll(rollUp(orderedNonRolledUpErrorPoints, liveCaptureTime, rollupLevel));
        return errorPoints;
    }

    private List<AggregateIntervalCollector> getOrderedIntervalCollectorsInRange(long from,
            long to) {
        if (aggregateCollector == null) {
            return ImmutableList.of();
        }
        return aggregateCollector.getOrderedIntervalCollectorsInRange(from, to);
    }

    private List<ErrorPoint> rollUp(List<ErrorPoint> orderedNonRolledUpErrorPoints,
            long liveCaptureTime, int rollupLevel) {
        long fixedRollupMillis;
        if (rollupLevel == 1) {
            fixedRollupMillis = fixedRollup1Millis;
        } else {
            fixedRollupMillis = fixedRollup2Millis;
        }
        List<ErrorPoint> rolledUpErrorPoints = Lists.newArrayList();
        long currRollupTime = Long.MIN_VALUE;
        long currErrorCount = 0;
        long currTransactionCount = 0;
        for (ErrorPoint errorPoint : orderedNonRolledUpErrorPoints) {
            long rollupTime = (long) Math.ceil(errorPoint.captureTime()
                    / (double) fixedRollupMillis) * fixedRollupMillis;
            if (rollupTime != currRollupTime && currTransactionCount != 0) {
                rolledUpErrorPoints.add(ErrorPoint.of(Math.min(currRollupTime, liveCaptureTime),
                        currErrorCount, currTransactionCount));
                currErrorCount = 0;
                currTransactionCount = 0;
            }
            currRollupTime = rollupTime;
            currErrorCount += errorPoint.errorCount();
            currTransactionCount += errorPoint.transactionCount();
        }
        if (currTransactionCount != 0) {
            rolledUpErrorPoints.add(ErrorPoint.of(Math.min(currRollupTime, liveCaptureTime),
                    currErrorCount, currTransactionCount));
        }
        return rolledUpErrorPoints;
    }

    private List<ErrorPoint> readErrorPointsFromDao(String transactionType,
            @Nullable String transactionName, long from, long to, int rollupLevel)
                    throws SQLException {
        if (transactionName == null) {
            return aggregateDao.readOverallErrorPoints(transactionType, from, to, rollupLevel);
        } else {
            return aggregateDao.readTransactionErrorPoints(transactionType, transactionName, from,
                    to, rollupLevel);
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

    private static List<ErrorPoint> getLiveErrorPoints(String transactionType,
            @Nullable String transactionName, List<AggregateIntervalCollector> intervalCollectors,
            long liveCaptureTime) throws IOException {
        List<ErrorPoint> errorPoints = Lists.newArrayList();
        for (AggregateIntervalCollector intervalCollector : intervalCollectors) {
            ErrorPoint liveErrorPoint = intervalCollector.getLiveErrorPoint(transactionType,
                    transactionName, liveCaptureTime);
            if (liveErrorPoint != null) {
                errorPoints.add(liveErrorPoint);
            }
        }
        return errorPoints;
    }

    private static ErrorSummary combineErrorSummaries(
            @Nullable String transactionName, ErrorSummary summary1, ErrorSummary summary2) {
        return ErrorSummary.builder()
                .transactionName(transactionName)
                .errorCount(summary1.errorCount() + summary2.errorCount())
                .transactionCount(summary1.transactionCount() + summary2.transactionCount())
                .build();
    }

    private static QueryResult<ErrorSummary> mergeInLiveTransactionErrorSummaries(
            ErrorSummaryQuery query, QueryResult<ErrorSummary> queryResult,
            List<AggregateIntervalCollector> intervalCollectors) {
        List<ErrorSummary> errorSummaries = queryResult.records();
        Map<String, ErrorSummary> errorSummaryMap = Maps.newHashMap();
        for (ErrorSummary errorSummary : errorSummaries) {
            String transactionName = errorSummary.transactionName();
            // transaction name is only null for overall summary
            checkNotNull(transactionName);
            errorSummaryMap.put(transactionName, errorSummary);
        }
        for (AggregateIntervalCollector intervalCollector : intervalCollectors) {
            List<ErrorSummary> liveErrorSummaries =
                    intervalCollector.getLiveTransactionErrorSummaries(query.transactionType());
            for (ErrorSummary liveErrorSummary : liveErrorSummaries) {
                String transactionName = liveErrorSummary.transactionName();
                // transaction name is only null for overall summary
                checkNotNull(transactionName);
                ErrorSummary errorSummary = errorSummaryMap.get(transactionName);
                if (errorSummary == null) {
                    errorSummaryMap.put(transactionName, liveErrorSummary);
                } else {
                    errorSummaryMap.put(transactionName,
                            combineErrorSummaries(transactionName, errorSummary, liveErrorSummary));
                }
            }
        }
        List<ErrorSummary> mergedErrorSummaries = Lists.newArrayList();
        for (ErrorSummary errorSummary : errorSummaryMap.values()) {
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
        return new QueryResult<ErrorSummary>(mergedErrorSummaries, moreAvailable);
    }

    private static List<ErrorSummary> sortErrorSummaries(Iterable<ErrorSummary> errorSummaries,
            ErrorSummarySortOrder sortOrder) {
        switch (sortOrder) {
            case ERROR_COUNT:
                return ErrorSummary.orderingByErrorCountDesc.immutableSortedCopy(
                        errorSummaries);
            case ERROR_RATE:
                return ErrorSummary.orderingByErrorRateDesc.immutableSortedCopy(
                        errorSummaries);
            default:
                throw new AssertionError("Unexpected sort order: " + sortOrder);
        }
    }
}
