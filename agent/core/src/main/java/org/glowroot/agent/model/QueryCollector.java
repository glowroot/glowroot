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
import com.google.common.collect.Ordering;
import com.google.common.hash.Hashing;
import com.google.common.primitives.Doubles;
import org.immutables.value.Value;

import org.glowroot.common.config.StorageConfig;
import org.glowroot.common.util.Styles;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;

public class QueryCollector {

    private static final Ordering<Entry<String, MutableQuery>> bySmallestTotalDuration =
            new Ordering<Entry<String, MutableQuery>>() {
                @Override
                public int compare(Entry<String, MutableQuery> left,
                        Entry<String, MutableQuery> right) {
                    return Doubles.compare(left.getValue().getTotalDurationNanos(),
                            right.getValue().getTotalDurationNanos());
                }
            };

    private static final int REMOVE_SMALLEST_N = 10;

    // first key is query type, second key is query text
    private final Map<String, Map<String, MutableQuery>> queries = Maps.newHashMap();
    private final int limit;
    private final int maxMultiplierWhileBuilding;

    private final Map<String, MinQuery> minQueryPerType = Maps.newHashMap();

    public QueryCollector(int limit, int maxMultiplierWhileBuilding) {
        this.limit = limit;
        this.maxMultiplierWhileBuilding = maxMultiplierWhileBuilding;
    }

    public List<Aggregate.QueriesByType> toAggregateProto(
            SharedQueryTextCollector sharedQueryTextCollector) {
        if (queries.isEmpty()) {
            return ImmutableList.of();
        }
        List<Aggregate.QueriesByType> proto = Lists.newArrayList();
        for (Entry<String, Map<String, MutableQuery>> outerEntry : queries.entrySet()) {
            List<Aggregate.Query> queries =
                    Lists.newArrayListWithCapacity(outerEntry.getValue().values().size());
            for (Entry<String, MutableQuery> entry : outerEntry.getValue().entrySet()) {
                queries.add(entry.getValue().toAggregateProto(entry.getKey(),
                        sharedQueryTextCollector));
            }
            if (queries.size() > limit) {
                orderAggregateQueries(queries);
                queries = queries.subList(0, limit);
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
        boolean truncateAndRecalculateMinQuery = false;
        if (aggregateQuery == null) {
            if (maxMultiplierWhileBuilding != 0
                    && queriesForType.size() >= limit * maxMultiplierWhileBuilding) {
                MinQuery minQuery = minQueryPerType.get(queryType);
                if (minQuery != null && totalDurationNanos < minQuery.totalDurationNanos()) {
                    return;
                }
                truncateAndRecalculateMinQuery = true;
            }
            aggregateQuery = new MutableQuery();
            queriesForType.put(queryText, aggregateQuery);
        }
        aggregateQuery.addToTotalDurationNanos(totalDurationNanos);
        aggregateQuery.addToExecutionCount(executionCount);
        aggregateQuery.addToTotalRows(hasTotalRows, totalRows);
        if (truncateAndRecalculateMinQuery) {
            // TODO report checker framework issue that occurs without this suppression
            @SuppressWarnings("assignment.type.incompatible")
            List<Entry<String, MutableQuery>> sortedEntries =
                    bySmallestTotalDuration.sortedCopy(queriesForType.entrySet());
            // remove smallest N (instead of just smallest 1) to avoid re-sort again so quickly
            for (int i = 0; i < REMOVE_SMALLEST_N; i++) {
                queriesForType.remove(sortedEntries.get(i).getKey());
            }
            MutableQuery lastQuery = sortedEntries.get(REMOVE_SMALLEST_N).getValue();
            minQueryPerType.put(queryType,
                    ImmutableMinQuery.of(lastQuery, lastQuery.getTotalDurationNanos()));
        }
    }

    private static void orderAggregateQueries(List<Aggregate.Query> queries) {
        // reverse sort by total
        Collections.sort(queries, new Comparator<Aggregate.Query>() {
            @Override
            public int compare(Aggregate.Query left, Aggregate.Query right) {
                return Doubles.compare(right.getTotalDurationNanos(), left.getTotalDurationNanos());
            }
        });
    }

    @Value.Immutable
    @Styles.AllParameters
    interface MinQuery {
        MutableQuery query();
        double totalDurationNanos();
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
