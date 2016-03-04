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

import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.primitives.Doubles;

import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate.Query;

public class QueryCollector {

    private final Map<String, Map<String, MutableQuery>> queries = Maps.newHashMap();
    private final int limit;
    private final int maxMultiplierWhileBuilding;

    // this is only used by UI
    private long lastCaptureTime;

    public QueryCollector(int limit, int maxMultiplierWhileBuilding) {
        this.limit = limit;
        this.maxMultiplierWhileBuilding = maxMultiplierWhileBuilding;
    }

    public void updateLastCaptureTime(long captureTime) {
        lastCaptureTime = Math.max(lastCaptureTime, captureTime);
    }

    public long getLastCaptureTime() {
        return lastCaptureTime;
    }

    public List<Aggregate.QueriesByType> toProto() {
        if (queries.isEmpty()) {
            return ImmutableList.of();
        }
        List<Aggregate.QueriesByType> queriesByType = Lists.newArrayList();
        for (Entry<String, Map<String, MutableQuery>> entry : queries.entrySet()) {
            List<Query> queries = Lists.newArrayListWithCapacity(entry.getValue().values().size());
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

    public void mergeQueries(List<Aggregate.QueriesByType> toBeMergedQueries) throws IOException {
        for (Aggregate.QueriesByType toBeMergedQueriesByType : toBeMergedQueries) {
            mergeQueries(toBeMergedQueriesByType);
        }
    }

    public void mergeQueries(Aggregate.QueriesByType toBeMergedQueries) {
        String queryType = toBeMergedQueries.getType();
        Map<String, MutableQuery> queriesForQueryType = queries.get(queryType);
        if (queriesForQueryType == null) {
            queriesForQueryType = Maps.newHashMap();
            queries.put(queryType, queriesForQueryType);
        }
        for (Query query : toBeMergedQueries.getQueryList()) {
            mergeQuery(query, queriesForQueryType);
        }
    }

    public void mergeQuery(String queryType, String queryText, long totalDurationNanos,
            long executionCount, boolean rowNavigationAttempted, long totalRows) {
        Map<String, MutableQuery> queriesForQueryType = queries.get(queryType);
        if (queriesForQueryType == null) {
            queriesForQueryType = Maps.newHashMap();
            queries.put(queryType, queriesForQueryType);
        }
        mergeQuery(queryText, totalDurationNanos, executionCount, totalRows, rowNavigationAttempted,
                queriesForQueryType);
    }

    private void mergeQuery(Aggregate.Query query, Map<String, MutableQuery> queriesForQueryType) {
        MutableQuery aggregateQuery = queriesForQueryType.get(query.getText());
        if (aggregateQuery == null) {
            if (maxMultiplierWhileBuilding != 0
                    && queriesForQueryType.size() >= limit * maxMultiplierWhileBuilding) {
                return;
            }
            aggregateQuery = new MutableQuery(query.getText());
            queriesForQueryType.put(query.getText(), aggregateQuery);
        }
        aggregateQuery.addToTotalDurationNanos(query.getTotalDurationNanos());
        aggregateQuery.addToExecutionCount(query.getExecutionCount());
        if (query.hasTotalRows()) {
            aggregateQuery.addToTotalRows(true, query.getTotalRows().getValue());
        }
    }

    private void mergeQuery(String queryText, long totalDurationNanos, long executionCount,
            long totalRows, boolean rowNavigationAttempted,
            Map<String, MutableQuery> queriesForQueryType) {
        MutableQuery aggregateQuery = queriesForQueryType.get(queryText);
        if (aggregateQuery == null) {
            if (maxMultiplierWhileBuilding != 0
                    && queriesForQueryType.size() >= limit * maxMultiplierWhileBuilding) {
                return;
            }
            aggregateQuery = new MutableQuery(queryText);
            queriesForQueryType.put(queryText, aggregateQuery);
        }
        aggregateQuery.addToTotalDurationNanos(totalDurationNanos);
        aggregateQuery.addToExecutionCount(executionCount);
        aggregateQuery.addToTotalRows(rowNavigationAttempted, totalRows);
    }

    private void order(List<Query> queries) {
        // reverse sort by total
        Collections.sort(queries, new Comparator<Query>() {
            @Override
            public int compare(Query aggregateQuery1, Query aggregateQuery2) {
                return Doubles.compare(aggregateQuery2.getTotalDurationNanos(),
                        aggregateQuery1.getTotalDurationNanos());
            }
        });
    }
}
