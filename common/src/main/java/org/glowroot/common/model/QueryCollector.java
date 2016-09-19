/*
 * Copyright 2015-2016 the original author or authors.
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

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.collect.Maps;

public class QueryCollector {

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

    public Map<String, List<MutableQuery>> getSortedQueries() {
        Map<String, List<MutableQuery>> sortedQueries = Maps.newHashMap();
        for (Entry<String, Map<String, MutableQuery>> entry : queries.entrySet()) {
            List<MutableQuery> list =
                    MutableQuery.byTotalDurationDesc.sortedCopy(entry.getValue().values());
            if (list.size() > limitPerQueryType) {
                list = list.subList(0, limitPerQueryType);
            }
            sortedQueries.put(entry.getKey(), list);
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

    private void mergeQuery(String truncatedText, @Nullable String fullTextSha1,
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
