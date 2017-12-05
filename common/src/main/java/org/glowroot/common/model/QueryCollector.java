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
package org.glowroot.common.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

public class QueryCollector {

    private static final String LIMIT_EXCEEDED_BUCKET = "LIMIT EXCEEDED BUCKET";

    // first key is query type, second key is either full query text (if query text is relatively
    // short) or sha1 of full query text (if query text is long)
    private final Map<String, Map<String, MutableQuery>> queries = Maps.newHashMap();
    private final int limitPerQueryType;

    // this is only used by UI
    private long lastCaptureTime;

    public QueryCollector(int limitPerQueryType) {
        this.limitPerQueryType = limitPerQueryType;
    }

    public void updateLastCaptureTime(long captureTime) {
        lastCaptureTime = Math.max(lastCaptureTime, captureTime);
    }

    public long getLastCaptureTime() {
        return lastCaptureTime;
    }

    public Map<String, List<MutableQuery>> getSortedAndTruncatedQueries() {
        Map<String, List<MutableQuery>> sortedQueries = Maps.newHashMap();
        for (Entry<String, Map<String, MutableQuery>> outerEntry : queries.entrySet()) {
            Map<String, MutableQuery> innerMap = outerEntry.getValue();
            if (innerMap.size() > limitPerQueryType) {
                MutableQuery limitExceededBucket = innerMap.get(LIMIT_EXCEEDED_BUCKET);
                if (limitExceededBucket == null) {
                    limitExceededBucket = new MutableQuery(LIMIT_EXCEEDED_BUCKET, null);
                } else {
                    // make copy to not modify original
                    innerMap = Maps.newHashMap(innerMap);
                    // remove temporarily so it is not included in initial sort/truncation
                    innerMap.remove(LIMIT_EXCEEDED_BUCKET);
                }
                List<MutableQuery> queries =
                        MutableQuery.byTotalDurationDesc.sortedCopy(innerMap.values());
                List<MutableQuery> exceededQueries =
                        queries.subList(limitPerQueryType, queries.size());
                queries = Lists.newArrayList(queries.subList(0, limitPerQueryType));
                for (MutableQuery exceededQuery : exceededQueries) {
                    limitExceededBucket.addTo(exceededQuery);
                }
                queries.add(limitExceededBucket);
                // need to re-sort now including limit exceeded bucket
                Collections.sort(queries, MutableQuery.byTotalDurationDesc);
                sortedQueries.put(outerEntry.getKey(), queries);
            } else {
                sortedQueries.put(outerEntry.getKey(),
                        MutableQuery.byTotalDurationDesc.sortedCopy(innerMap.values()));
            }
        }
        return sortedQueries;
    }

    public void mergeQuery(String queryType, String truncatedText, @Nullable String fullTextSha1,
            double totalDurationNanos, long executionCount, boolean hasRows, long totalRows) {
        Map<String, MutableQuery> queriesForType = queries.get(queryType);
        if (queriesForType == null) {
            queriesForType = Maps.newHashMap();
            queries.put(queryType, queriesForType);
        }
        mergeQuery(truncatedText, fullTextSha1, totalDurationNanos, executionCount,
                hasRows, totalRows, queriesForType);
    }

    private static void mergeQuery(String truncatedText, @Nullable String fullTextSha1,
            double totalDurationNanos, long executionCount, boolean hasRows, long totalRows,
            Map<String, MutableQuery> queriesForType) {
        String queryKey = MoreObjects.firstNonNull(fullTextSha1, truncatedText);
        MutableQuery aggregateQuery = queriesForType.get(queryKey);
        if (aggregateQuery == null) {
            aggregateQuery = new MutableQuery(truncatedText, fullTextSha1);
            queriesForType.put(queryKey, aggregateQuery);
        }
        aggregateQuery.addToTotalDurationNanos(totalDurationNanos);
        aggregateQuery.addToExecutionCount(executionCount);
        aggregateQuery.addToTotalRows(hasRows, totalRows);
    }
}
