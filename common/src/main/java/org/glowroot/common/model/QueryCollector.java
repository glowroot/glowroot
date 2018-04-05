/*
 * Copyright 2015-2018 the original author or authors.
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

import com.google.common.base.MoreObjects;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.checkerframework.checker.nullness.qual.Nullable;

public class QueryCollector {

    private static final String LIMIT_EXCEEDED_BUCKET = "LIMIT EXCEEDED BUCKET";

    // first key is the query type, second key is either the full query text (if the query text is
    // relatively short) or the sha1 of the full query text (if the query text is long)
    private final Map<String, Map<String, MutableQuery>> queries = Maps.newHashMap();
    private final Map<String, MutableQuery> limitExceededBuckets = Maps.newHashMap();
    private final int limit;

    // this is only used by UI
    private long lastCaptureTime;

    public QueryCollector(int limit) {
        this.limit = limit;
    }

    public void updateLastCaptureTime(long captureTime) {
        lastCaptureTime = Math.max(lastCaptureTime, captureTime);
    }

    public long getLastCaptureTime() {
        return lastCaptureTime;
    }

    public List<MutableQuery> getSortedAndTruncatedQueries() {
        List<MutableQuery> allQueries = Lists.newArrayList();
        for (Map.Entry<String, Map<String, MutableQuery>> outerEntry : queries.entrySet()) {
            allQueries.addAll(outerEntry.getValue().values());
        }
        if (allQueries.size() <= limit) {
            allQueries.addAll(limitExceededBuckets.values());
            return MutableQuery.byTotalDurationDesc.sortedCopy(allQueries);
        }
        allQueries = MutableQuery.byTotalDurationDesc.sortedCopy(allQueries);
        List<MutableQuery> exceededQueries = allQueries.subList(limit, allQueries.size());
        allQueries = Lists.newArrayList(allQueries.subList(0, limit));
        // do not modify original limit exceeded buckets since adding exceeded queries below
        Map<String, MutableQuery> limitExceededBuckets = copyLimitExceededBuckets();
        for (MutableQuery exceededQuery : exceededQueries) {
            String queryType = exceededQuery.getType();
            MutableQuery limitExceededBucket = limitExceededBuckets.get(queryType);
            if (limitExceededBucket == null) {
                limitExceededBucket = new MutableQuery(queryType, LIMIT_EXCEEDED_BUCKET, null);
                limitExceededBuckets.put(queryType, limitExceededBucket);
            }
            limitExceededBucket.add(exceededQuery);
        }
        allQueries.addAll(limitExceededBuckets.values());
        // need to re-sort now including limit exceeded bucket
        Collections.sort(allQueries, MutableQuery.byTotalDurationDesc);
        return allQueries;
    }

    public void mergeQuery(String queryType, String truncatedText, @Nullable String fullTextSha1,
            double totalDurationNanos, long executionCount, boolean hasRows, long totalRows) {
        MutableQuery aggregateQuery;
        if (truncatedText.equals(LIMIT_EXCEEDED_BUCKET)) {
            aggregateQuery = limitExceededBuckets.get(queryType);
            if (aggregateQuery == null) {
                aggregateQuery = new MutableQuery(queryType, LIMIT_EXCEEDED_BUCKET, null);
                limitExceededBuckets.put(queryType, aggregateQuery);
            }
        } else {
            Map<String, MutableQuery> queriesForType = queries.get(queryType);
            if (queriesForType == null) {
                queriesForType = Maps.newHashMap();
                queries.put(queryType, queriesForType);
            }
            String queryKey = MoreObjects.firstNonNull(fullTextSha1, truncatedText);
            aggregateQuery = queriesForType.get(queryKey);
            if (aggregateQuery == null) {
                aggregateQuery = new MutableQuery(queryType, truncatedText, fullTextSha1);
                queriesForType.put(queryKey, aggregateQuery);
            }
        }
        aggregateQuery.addToTotalDurationNanos(totalDurationNanos);
        aggregateQuery.addToExecutionCount(executionCount);
        aggregateQuery.addToTotalRows(hasRows, totalRows);
    }

    private Map<String, MutableQuery> copyLimitExceededBuckets() {
        Map<String, MutableQuery> copies = Maps.newHashMap();
        for (Map.Entry<String, MutableQuery> entry : limitExceededBuckets.entrySet()) {
            String queryType = entry.getKey();
            MutableQuery limitExceededBucket = entry.getValue();
            MutableQuery copy = new MutableQuery(queryType, LIMIT_EXCEEDED_BUCKET, null);
            copy.add(limitExceededBucket);
            copies.put(queryType, copy);
        }
        return copies;
    }
}
