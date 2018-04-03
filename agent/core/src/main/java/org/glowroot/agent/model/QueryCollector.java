/*
 * Copyright 2016-2018 the original author or authors.
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

import javax.annotation.Nullable;

import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.hash.Hashing;
import com.google.common.primitives.Doubles;

import org.glowroot.common.Constants;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;

public class QueryCollector {

    private static final String LIMIT_EXCEEDED_BUCKET = "LIMIT EXCEEDED BUCKET";

    // first key is query type, second key is query text
    private final Map<String, Map<String, MutableQuery>> queries = Maps.newHashMap();
    private final Map<String, MutableQuery> limitExceededBuckets = Maps.newHashMap();
    private final int limit;
    private final int hardLimitMultiplierWhileBuilding;

    private int queryCount;

    public QueryCollector(int limit, int hardLimitMultiplierWhileBuilding) {
        this.limit = limit;
        this.hardLimitMultiplierWhileBuilding = hardLimitMultiplierWhileBuilding;
    }

    public List<Aggregate.Query> toAggregateProto(
            SharedQueryTextCollection sharedQueryTextCollection, boolean includeActive)
            throws Exception {
        // " + queries.size()" is to cover the maximum number of limit exceeded buckets
        List<Aggregate.Query> allQueries =
                Lists.newArrayListWithCapacity(Math.min(queryCount, limit) + queries.size());
        for (Map.Entry<String, Map<String, MutableQuery>> outerEntry : queries.entrySet()) {
            for (Map.Entry<String, MutableQuery> innerEntry : outerEntry.getValue().entrySet()) {
                allQueries.add(innerEntry.getValue().toAggregateProto(outerEntry.getKey(),
                        innerEntry.getKey(), sharedQueryTextCollection, includeActive));
            }
        }
        if (allQueries.size() <= limit) {
            // there could be limit exceeded buckets if hardLimitMultiplierWhileBuilding is 1
            for (Map.Entry<String, MutableQuery> entry : limitExceededBuckets.entrySet()) {
                allQueries.add(entry.getValue().toAggregateProto(entry.getKey(),
                        LIMIT_EXCEEDED_BUCKET, sharedQueryTextCollection, includeActive));
            }
            sort(allQueries);
            return allQueries;
        }
        sort(allQueries);
        List<Aggregate.Query> exceededQueries = allQueries.subList(limit, allQueries.size());
        allQueries = Lists.newArrayList(allQueries.subList(0, limit));
        // do not modify original limit exceeded buckets since adding exceeded queries below
        Map<String, MutableQuery> limitExceededBuckets = copyLimitExceededBuckets();
        for (Aggregate.Query exceededQuery : exceededQueries) {
            String queryType = exceededQuery.getType();
            MutableQuery limitExceededBucket = limitExceededBuckets.get(queryType);
            if (limitExceededBucket == null) {
                limitExceededBucket = new MutableQuery();
                limitExceededBuckets.put(queryType, limitExceededBucket);
            }
            limitExceededBucket.add(exceededQuery);
        }
        for (Map.Entry<String, MutableQuery> entry : limitExceededBuckets.entrySet()) {
            allQueries.add(entry.getValue().toAggregateProto(entry.getKey(), LIMIT_EXCEEDED_BUCKET,
                    sharedQueryTextCollection, includeActive));
        }
        // need to re-sort now including limit exceeded bucket
        sort(allQueries);
        return allQueries;
    }

    public void mergeQuery(String queryType, String queryText, double totalDurationNanos,
            long executionCount, boolean hasTotalRows, long totalRows, boolean active) {
        Map<String, MutableQuery> queriesForType = queries.get(queryType);
        if (queriesForType == null) {
            queriesForType = Maps.newHashMap();
            queries.put(queryType, queriesForType);
        }
        MutableQuery aggregateQuery = queriesForType.get(queryText);
        if (aggregateQuery == null) {
            if (queryCount < limit * hardLimitMultiplierWhileBuilding) {
                aggregateQuery = new MutableQuery();
                queriesForType.put(queryText, aggregateQuery);
                queryCount++;
            } else {
                aggregateQuery = getOrCreateLimitExceededBucket(queryType);
            }
        }
        aggregateQuery.addToTotalDurationNanos(totalDurationNanos);
        aggregateQuery.addToExecutionCount(executionCount);
        aggregateQuery.addToTotalRows(hasTotalRows, totalRows);
        aggregateQuery.setActive(active);
    }

    public void mergeQueriesInto(QueryCollector collector) {
        for (Map.Entry<String, Map<String, MutableQuery>> outerEntry : queries.entrySet()) {
            for (Map.Entry<String, MutableQuery> entry : outerEntry.getValue().entrySet()) {
                MutableQuery query = entry.getValue();
                collector.mergeQuery(outerEntry.getKey(), entry.getKey(),
                        query.getTotalDurationNanos(), query.getExecutionCount(),
                        query.hasTotalRows(), query.getTotalRows(), query.isActive());
            }
        }
        for (Map.Entry<String, MutableQuery> limitExceededBucket : limitExceededBuckets
                .entrySet()) {
            collector.mergeLimitExceededBucket(limitExceededBucket.getKey(),
                    limitExceededBucket.getValue());
        }
    }

    public void mergeQueriesInto(org.glowroot.common.model.QueryCollector collector) {
        for (Map.Entry<String, Map<String, MutableQuery>> outerEntry : queries.entrySet()) {
            for (Map.Entry<String, MutableQuery> entry : outerEntry.getValue().entrySet()) {
                String fullQueryText = entry.getKey();
                String truncatedQueryText;
                String fullQueryTextSha1;
                if (fullQueryText.length() > Constants.AGGREGATE_QUERY_TEXT_TRUNCATE) {
                    truncatedQueryText =
                            fullQueryText.substring(0, Constants.AGGREGATE_QUERY_TEXT_TRUNCATE);
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
        for (Map.Entry<String, MutableQuery> limitExceededBucket : limitExceededBuckets
                .entrySet()) {
            MutableQuery query = limitExceededBucket.getValue();
            collector.mergeQuery(limitExceededBucket.getKey(), LIMIT_EXCEEDED_BUCKET, null,
                    query.getTotalDurationNanos(), query.getExecutionCount(), query.hasTotalRows(),
                    query.getTotalRows());
        }
    }

    public @Nullable String getFullQueryText(String fullQueryTextSha1) {
        for (Map.Entry<String, Map<String, MutableQuery>> entry : queries.entrySet()) {
            for (String fullQueryText : entry.getValue().keySet()) {
                if (fullQueryText.length() <= Constants.AGGREGATE_QUERY_TEXT_TRUNCATE) {
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

    private void mergeLimitExceededBucket(String queryType, MutableQuery limitExceededBucket) {
        MutableQuery query = getOrCreateLimitExceededBucket(queryType);
        query.add(limitExceededBucket);
    }

    private MutableQuery getOrCreateLimitExceededBucket(String queryType) {
        MutableQuery query = limitExceededBuckets.get(queryType);
        if (query == null) {
            query = new MutableQuery();
            limitExceededBuckets.put(queryType, query);
        }
        return query;
    }

    private Map<String, MutableQuery> copyLimitExceededBuckets() {
        Map<String, MutableQuery> copies = Maps.newHashMap();
        for (Map.Entry<String, MutableQuery> entry : limitExceededBuckets.entrySet()) {
            String queryType = entry.getKey();
            MutableQuery limitExceededBucket = entry.getValue();
            MutableQuery copy = new MutableQuery();
            copy.add(limitExceededBucket);
            copies.put(queryType, copy);
        }
        return copies;
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
}
