/*
 * Copyright 2016-2017 the original author or authors.
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
package org.glowroot.agent.model;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.hash.Hashing;
import com.google.common.primitives.Doubles;

import org.glowroot.common.config.StorageConfig;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;

public class QueryCollector {

    private static final String LIMIT_EXCEEDED_BUCKET = "LIMIT EXCEEDED BUCKET";

    // first key is query type, second key is query text
    private final Map<String, Map<String, MutableQuery>> queries = Maps.newHashMap();
    private final Map<String, MutableQuery> limitExceededBuckets = Maps.newHashMap();
    private final int limitPerQueryType;
    private final int maxMultiplierWhileBuilding;

    public QueryCollector(int limitPerQueryType, int maxMultiplierWhileBuilding) {
        this.limitPerQueryType = limitPerQueryType;
        this.maxMultiplierWhileBuilding = maxMultiplierWhileBuilding;
    }

    public List<Aggregate.QueriesByType> toAggregateProto(
            SharedQueryTextCollector sharedQueryTextCollector) {
        if (queries.isEmpty()) {
            return ImmutableList.of();
        }
        List<Aggregate.QueriesByType> proto = Lists.newArrayList();
        for (Entry<String, Map<String, MutableQuery>> outerEntry : queries.entrySet()) {
            Map<String, MutableQuery> innerMap = outerEntry.getValue();
            // + 1 is for possible limit exceeded bucket
            List<Aggregate.Query> queries =
                    Lists.newArrayListWithCapacity(innerMap.values().size() + 1);
            for (Entry<String, MutableQuery> innerEntry : innerMap.entrySet()) {
                queries.add(innerEntry.getValue().toAggregateProto(innerEntry.getKey(),
                        sharedQueryTextCollector));
            }
            if (queries.size() > limitPerQueryType) {
                sort(queries);
                List<Aggregate.Query> exceededQueries =
                        queries.subList(limitPerQueryType, queries.size());
                queries = Lists.newArrayList(queries.subList(0, limitPerQueryType));
                MutableQuery limitExceededBucket = limitExceededBuckets.get(outerEntry.getKey());
                if (limitExceededBucket == null) {
                    limitExceededBucket = new MutableQuery();
                }
                for (Aggregate.Query exceededQuery : exceededQueries) {
                    limitExceededBucket
                            .addToTotalDurationNanos((long) exceededQuery.getTotalDurationNanos());
                    limitExceededBucket.addToExecutionCount(exceededQuery.getExecutionCount());
                    limitExceededBucket.addToTotalRows(exceededQuery.hasTotalRows(),
                            exceededQuery.getTotalRows().getValue());
                }
                queries.add(limitExceededBucket.toAggregateProto(LIMIT_EXCEEDED_BUCKET,
                        sharedQueryTextCollector));
                // need to re-sort now including the limit exceeded bucket
                sort(queries);
            }
            proto.add(Aggregate.QueriesByType.newBuilder()
                    .setType(outerEntry.getKey())
                    .addAllQuery(queries)
                    .build());
        }
        return proto;
    }

    public void mergeQuery(String queryType, String queryText, long totalDurationNanos,
            long executionCount, boolean hasTotalRows, long totalRows) {
        Map<String, MutableQuery> queriesForType = queries.get(queryType);
        if (queriesForType == null) {
            queriesForType = Maps.newHashMap();
            queries.put(queryType, queriesForType);
        }
        mergeQuery(queryType, queryText, totalDurationNanos, executionCount, totalRows,
                hasTotalRows, queriesForType);
    }

    public void mergeQueriesInto(org.glowroot.common.model.QueryCollector collector) {
        for (Entry<String, Map<String, MutableQuery>> outerEntry : queries.entrySet()) {
            for (Entry<String, MutableQuery> entry : outerEntry.getValue().entrySet()) {
                String fullQueryText = entry.getKey();
                String truncatedQueryText;
                String fullQueryTextSha1;
                if (fullQueryText.length() > StorageConfig.AGGREGATE_QUERY_TEXT_TRUNCATE) {
                    truncatedQueryText =
                            fullQueryText.substring(0, StorageConfig.AGGREGATE_QUERY_TEXT_TRUNCATE);
                    fullQueryTextSha1 =
                            Hashing.sha1().hashString(fullQueryText, Charsets.UTF_8).toString();
                } else {
                    truncatedQueryText = fullQueryText;
                    fullQueryTextSha1 = null;
                }
                MutableQuery query = entry.getValue();
                collector.mergeQuery(outerEntry.getKey(), truncatedQueryText, fullQueryTextSha1,
                        query.getTotalDurationNanos(), query.getExecutionCount(),
                        query.hasTotalRows(), query.getTotalRows());
            }
        }
    }

    public @Nullable String getFullQueryText(String fullQueryTextSha1) {
        for (Entry<String, Map<String, MutableQuery>> entry : queries.entrySet()) {
            for (String fullQueryText : entry.getValue().keySet()) {
                if (fullQueryText.length() <= StorageConfig.AGGREGATE_QUERY_TEXT_TRUNCATE) {
                    continue;
                }
                String sha1 = Hashing.sha1().hashString(fullQueryText, Charsets.UTF_8).toString();
                if (fullQueryTextSha1.equals(sha1)) {
                    return fullQueryText;
                }
            }
        }
        return null;
    }

    private void mergeQuery(String queryType, String queryText, long totalDurationNanos,
            long executionCount, long totalRows, boolean hasTotalRows,
            Map<String, MutableQuery> queriesForType) {
        MutableQuery aggregateQuery = queriesForType.get(queryText);
        if (aggregateQuery == null) {
            if (queriesForType.size() < limitPerQueryType * maxMultiplierWhileBuilding) {
                aggregateQuery = new MutableQuery();
                queriesForType.put(queryText, aggregateQuery);
            } else {
                aggregateQuery = limitExceededBuckets.get(queryType);
                if (aggregateQuery == null) {
                    aggregateQuery = new MutableQuery();
                    limitExceededBuckets.put(queryType, aggregateQuery);
                }
            }
        }
        aggregateQuery.addToTotalDurationNanos(totalDurationNanos);
        aggregateQuery.addToExecutionCount(executionCount);
        aggregateQuery.addToTotalRows(hasTotalRows, totalRows);
    }

    private static void sort(List<Aggregate.Query> queries) {
        // reverse sort by total
        Collections.sort(queries, new Comparator<Aggregate.Query>() {
            @Override
            public int compare(Aggregate.Query left, Aggregate.Query right) {
                return Doubles.compare(right.getTotalDurationNanos(), left.getTotalDurationNanos());
            }
        });
    }

    public static class SharedQueryTextCollector {

        private final Map<String, Integer> sharedQueryTextIndexes = Maps.newHashMap();

        private List<String> latestSharedQueryTexts = Lists.newArrayList();

        public List<String> getAndClearLastestSharedQueryTexts() {
            List<String> latestSharedQueryTexts = this.latestSharedQueryTexts;
            this.latestSharedQueryTexts = Lists.newArrayList();
            return latestSharedQueryTexts;
        }

        int getIndex(String queryText) {
            Integer sharedQueryTextIndex = sharedQueryTextIndexes.get(queryText);
            if (sharedQueryTextIndex == null) {
                sharedQueryTextIndex = sharedQueryTextIndexes.size();
                sharedQueryTextIndexes.put(queryText, sharedQueryTextIndex);
                latestSharedQueryTexts.add(queryText);
            }
            return sharedQueryTextIndex;
        }
    }
}
