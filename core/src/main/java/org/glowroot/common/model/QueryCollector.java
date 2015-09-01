/*
 * Copyright 2015 the original author or authors.
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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.primitives.Doubles;

import org.glowroot.collector.spi.Query;

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

    public Map<String, List<MutableQuery>> getOrderedAndTruncatedQueries() {
        if (queries.isEmpty()) {
            return ImmutableMap.of();
        }
        Map<String, List<MutableQuery>> mergedQueries = Maps.newHashMap();
        for (Entry<String, Map<String, MutableQuery>> entry : queries.entrySet()) {
            List<MutableQuery> aggregateQueries = Lists.newArrayList(entry.getValue().values());
            order(aggregateQueries);
            if (aggregateQueries.size() > limit) {
                aggregateQueries = aggregateQueries.subList(0, limit);
            }
            mergedQueries.put(entry.getKey(), aggregateQueries);
        }
        return mergedQueries;
    }

    // not using static ObjectMapper because ObjectMapper caches keys, and in this particular case
    // the keys are (often very large) sql queries and have seen it retain 26mb worth of memory
    public void mergeQueries(Map<String, List<MutableQuery>> toBeMergedQueries) throws IOException {
        for (Entry<String, List<MutableQuery>> entry : toBeMergedQueries.entrySet()) {
            String queryType = entry.getKey();
            Map<String, MutableQuery> queriesForQueryType = queries.get(queryType);
            if (queriesForQueryType == null) {
                queriesForQueryType = Maps.newHashMap();
                queries.put(queryType, queriesForQueryType);
            }
            for (MutableQuery query : entry.getValue()) {
                mergeQuery(query, queriesForQueryType);
            }
        }
    }

    public void mergeQuery(String queryType, Query query) {
        Map<String, MutableQuery> queriesForQueryType = queries.get(queryType);
        if (queriesForQueryType == null) {
            queriesForQueryType = Maps.newHashMap();
            queries.put(queryType, queriesForQueryType);
        }
        mergeQuery(query, queriesForQueryType);
    }

    public Map<String, List<MutableQuery>> copy() {
        Map<String, List<MutableQuery>> copy = Maps.newHashMapWithExpectedSize(queries.size());
        for (Entry<String, Map<String, MutableQuery>> entry : queries.entrySet()) {
            Map<String, MutableQuery> values = entry.getValue();
            List<MutableQuery> list = Lists.newArrayListWithCapacity(values.size());
            for (MutableQuery item : values.values()) {
                list.add(item.copy());
            }
            copy.put(entry.getKey(), list);
        }
        return copy;
    }

    private void mergeQuery(Query query, Map<String, MutableQuery> queriesForQueryType) {
        MutableQuery aggregateQuery = queriesForQueryType.get(query.queryText());
        if (aggregateQuery == null) {
            if (maxMultiplierWhileBuilding != 0
                    && queriesForQueryType.size() >= limit * maxMultiplierWhileBuilding) {
                return;
            }
            aggregateQuery = new MutableQuery(query.queryText());
            queriesForQueryType.put(query.queryText(), aggregateQuery);
        }
        aggregateQuery.addToTotalNanos(query.totalNanos());
        aggregateQuery.addToExecutionCount(query.executionCount());
        aggregateQuery.addToTotalRows(query.totalRows());
    }

    private void order(List<MutableQuery> aggregateQueries) {
        // reverse sort by total
        Collections.sort(aggregateQueries, new Comparator<MutableQuery>() {
            @Override
            public int compare(MutableQuery aggregateQuery1, MutableQuery aggregateQuery2) {
                return Doubles.compare(aggregateQuery2.totalNanos(), aggregateQuery1.totalNanos());
            }
        });
    }
}
