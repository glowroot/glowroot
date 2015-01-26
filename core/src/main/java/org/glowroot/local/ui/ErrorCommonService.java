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
import org.glowroot.collector.ImmutableErrorSummary;
import org.glowroot.local.store.AggregateDao;
import org.glowroot.local.store.AggregateDao.ErrorSummarySortOrder;
import org.glowroot.local.store.ImmutableErrorSummaryQuery;
import org.glowroot.local.store.QueryResult;

import static com.google.common.base.Preconditions.checkNotNull;

class ErrorCommonService {

    private final AggregateDao aggregateDao;
    private final @Nullable AggregateCollector aggregateCollector;

    ErrorCommonService(AggregateDao aggregateDao,
            @Nullable AggregateCollector aggregateCollector) {
        this.aggregateDao = aggregateDao;
        this.aggregateCollector = aggregateCollector;
    }

    ErrorSummary readOverallErrorSummary(String transactionType, long from, long to)
            throws SQLException {
        List<AggregateIntervalCollector> intervalCollectors =
                getIntervalCollectorsInRange(from, to);
        long possiblyRevisedTo = to;
        if (!intervalCollectors.isEmpty()) {
            // -1 since query 'to' is inclusive
            // this way don't need to worry about de-dupping with stored aggregates
            possiblyRevisedTo = getMinEndTime(intervalCollectors) - 1;
        }
        ErrorSummary overallSummary = aggregateDao.readOverallErrorSummary(
                transactionType, from, possiblyRevisedTo);
        for (AggregateIntervalCollector intervalCollector : intervalCollectors) {
            ErrorSummary liveOverallSummary =
                    intervalCollector.getLiveOverallErrorSummary(transactionType);
            if (liveOverallSummary != null) {
                overallSummary = combineErrorSummaries(null, overallSummary, liveOverallSummary);
            }
        }
        return overallSummary;
    }

    QueryResult<ErrorSummary> readTransactionErrorSummaries(String transactionType, long from,
            long to, ErrorSummarySortOrder sortOrder, int limit) throws SQLException {
        List<AggregateIntervalCollector> intervalCollectors =
                getIntervalCollectorsInRange(from, to);
        long possiblyRevisedTo = to;
        if (!intervalCollectors.isEmpty()) {
            // -1 since query 'to' is inclusive
            // this way don't need to worry about de-dupping with stored aggregates
            possiblyRevisedTo = getMinEndTime(intervalCollectors) - 1;
        }
        ImmutableErrorSummaryQuery query = ImmutableErrorSummaryQuery.builder()
                .transactionType(transactionType)
                .from(from)
                .to(possiblyRevisedTo)
                .sortOrder(sortOrder)
                .limit(limit)
                .build();
        QueryResult<ErrorSummary> queryResult =
                aggregateDao.readTransactionErrorSummaries(query);
        if (intervalCollectors.isEmpty()) {
            return queryResult;
        }
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
                    intervalCollector.getLiveTransactionErrorSummaries(transactionType);
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
        errorSummaries = sortErrorSummaries(errorSummaryMap.values(), sortOrder);
        boolean moreAvailable = queryResult.moreAvailable();
        if (errorSummaries.size() > limit) {
            moreAvailable = true;
            errorSummaries = errorSummaries.subList(0, limit);
        }
        return new QueryResult<ErrorSummary>(errorSummaries, moreAvailable);
    }

    List<ErrorPoint> readErrorPoints(String transactionType, @Nullable String transactionName,
            long from, long to) throws Exception {
        List<AggregateIntervalCollector> intervalCollectors =
                getIntervalCollectorsInRange(from, to);
        long possiblyRevisedTo = to;
        if (!intervalCollectors.isEmpty()) {
            // -1 since query 'to' is inclusive
            // this way don't need to worry about de-dupping with stored aggregates
            possiblyRevisedTo = getMinEndTime(intervalCollectors) - 1;
        }
        List<ErrorPoint> errorPoints;
        if (transactionName == null) {
            errorPoints = aggregateDao.readOverallErrorPoints(transactionType, from,
                    possiblyRevisedTo);
        } else {
            errorPoints = aggregateDao.readTransactionErrorPoints(transactionType, transactionName,
                    from, possiblyRevisedTo);
        }
        if (!intervalCollectors.isEmpty()) {
            errorPoints = Lists.newArrayList(errorPoints);
            for (AggregateIntervalCollector intervalCollector : intervalCollectors) {
                ErrorPoint liveErrorPoint =
                        intervalCollector.getLiveErrorPoint(transactionType, transactionName);
                if (liveErrorPoint != null) {
                    errorPoints.add(liveErrorPoint);
                }
            }
        }
        return errorPoints;
    }

    private List<AggregateIntervalCollector> getIntervalCollectorsInRange(long from, long to) {
        if (aggregateCollector == null) {
            return ImmutableList.of();
        }
        return aggregateCollector.getIntervalCollectorsInRange(from, to);
    }

    private static long getMinEndTime(List<AggregateIntervalCollector> intervalCollectors) {
        long min = Long.MAX_VALUE;
        for (AggregateIntervalCollector intervalCollector : intervalCollectors) {
            min = Math.min(intervalCollector.getEndTime(), min);
        }
        return min;
    }

    private static ErrorSummary combineErrorSummaries(
            @Nullable String transactionName, ErrorSummary summary1, ErrorSummary summary2) {
        return ImmutableErrorSummary.builder()
                .transactionName(transactionName)
                .errorCount(summary1.errorCount() + summary2.errorCount())
                .transactionCount(summary1.transactionCount() + summary2.transactionCount())
                .build();
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
