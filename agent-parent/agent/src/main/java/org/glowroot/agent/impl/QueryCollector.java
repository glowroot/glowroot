/*
 * Copyright 2016 the original author or authors.
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
package org.glowroot.agent.impl;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.hash.Hashing;
import com.google.common.primitives.Doubles;

import org.glowroot.storage.config.StorageConfig;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;

public class QueryCollector {

    // first key is query type, second key is shared query text index (which uniquely identifies
    // the query text inside a given AggregateIntervalCollector)
    private final Map<String, Map<Integer, MutableQuery>> queries = Maps.newHashMap();
    private final int limit;
    private final int maxMultiplierWhileBuilding;

    // this is only used by UI
    private long lastCaptureTime;

    QueryCollector(int limit, int maxMultiplierWhileBuilding) {
        this.limit = limit;
        this.maxMultiplierWhileBuilding = maxMultiplierWhileBuilding;
    }

    void updateLastCaptureTime(long captureTime) {
        lastCaptureTime = Math.max(lastCaptureTime, captureTime);
    }

    long getLastCaptureTime() {
        return lastCaptureTime;
    }

    List<Aggregate.QueriesByType> toProto() {
        if (queries.isEmpty()) {
            return ImmutableList.of();
        }
        List<Aggregate.QueriesByType> queriesByType = Lists.newArrayList();
        for (Entry<String, Map<Integer, MutableQuery>> entry : queries.entrySet()) {
            List<Aggregate.Query> queries =
                    Lists.newArrayListWithCapacity(entry.getValue().values().size());
            for (MutableQuery query : entry.getValue().values()) {
                queries.add(query.toProto());
            }
            if (queries.size() > limit) {
                order(queries);
                queries = queries.subList(0, limit);
            }
            queriesByType.add(Aggregate.QueriesByType.newBuilder()
                    .setType(entry.getKey())
                    .addAllQuery(queries)
                    .build());
        }
        return queriesByType;
    }

    public void mergeQuery(String queryType, int sharedQueryTextIndex, double totalDurationNanos,
            long executionCount, boolean hasTotalRows, long totalRows) {
        Map<Integer, MutableQuery> queriesForType = queries.get(queryType);
        if (queriesForType == null) {
            queriesForType = Maps.newHashMap();
            queries.put(queryType, queriesForType);
        }
        mergeQuery(sharedQueryTextIndex, totalDurationNanos, executionCount, totalRows,
                hasTotalRows, queriesForType);
    }

    void mergeQueriesInto(org.glowroot.common.model.QueryCollector collector,
            List<String> sharedQueryTexts) {
        for (Entry<String, Map<Integer, MutableQuery>> entry : queries.entrySet()) {
            for (MutableQuery query : entry.getValue().values()) {
                String fullQueryText = sharedQueryTexts.get(query.getSharedQueryTextIndex());
                String truncatedQueryText;
                String fullQueryTextSha1;
                if (fullQueryText.length() > StorageConfig.QUERY_TEXT_TRUNCATE) {
                    truncatedQueryText =
                            fullQueryText.substring(0, StorageConfig.QUERY_TEXT_TRUNCATE);
                    fullQueryTextSha1 =
                            Hashing.sha1().hashString(fullQueryText, Charsets.UTF_8).toString();
                } else {
                    truncatedQueryText = fullQueryText;
                    fullQueryTextSha1 = null;
                }
                collector.mergeQuery(entry.getKey(), truncatedQueryText, fullQueryTextSha1,
                        query.getTotalDurationNanos(), query.getExecutionCount(),
                        query.hasTotalRows(), query.getTotalRows());
            }
        }
    }

    private void mergeQuery(int sharedQueryTextIndex, double totalDurationNanos,
            long executionCount, long totalRows, boolean hasTotalRows,
            Map<Integer, MutableQuery> queriesForType) {
        MutableQuery aggregateQuery = queriesForType.get(sharedQueryTextIndex);
        if (aggregateQuery == null) {
            if (maxMultiplierWhileBuilding != 0
                    && queriesForType.size() >= limit * maxMultiplierWhileBuilding) {
                return;
            }
            aggregateQuery = new MutableQuery(sharedQueryTextIndex);
            queriesForType.put(sharedQueryTextIndex, aggregateQuery);
        }
        aggregateQuery.addToTotalDurationNanos(totalDurationNanos);
        aggregateQuery.addToExecutionCount(executionCount);
        aggregateQuery.addToTotalRows(hasTotalRows, totalRows);
    }

    private static void order(List<Aggregate.Query> queries) {
        // reverse sort by total
        Collections.sort(queries, new Comparator<Aggregate.Query>() {
            @Override
            public int compare(Aggregate.Query left, Aggregate.Query right) {
                return Doubles.compare(right.getTotalDurationNanos(), left.getTotalDurationNanos());
            }
        });
    }
}
