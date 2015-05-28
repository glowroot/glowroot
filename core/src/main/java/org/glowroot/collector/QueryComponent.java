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
package org.glowroot.collector;

import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.primitives.Longs;

import org.glowroot.common.ObjectMappers;
import org.glowroot.transaction.model.QueryData;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

public class QueryComponent {

    private final Map<String, Map<String, AggregateQueryData>> queries = Maps.newHashMap();
    private final int maxAggregateQueriesPerQueryType;
    private final boolean applyLimitWhileBuilding;

    public QueryComponent(int maxAggregateQueriesPerQueryType, boolean applyLimitWhileBuilding) {
        this.maxAggregateQueriesPerQueryType = maxAggregateQueriesPerQueryType;
        this.applyLimitWhileBuilding = applyLimitWhileBuilding;
    }

    public Map<String, Map<String, AggregateQueryData>> getMergedQueries() {
        if (applyLimitWhileBuilding) {
            return queries;
        }
        Map<String, Map<String, AggregateQueryData>> limitAppliedQueries = Maps.newHashMap();
        for (Entry<String, Map<String, AggregateQueryData>> entry : queries.entrySet()) {
            String queryType = entry.getKey();
            Map<String, AggregateQueryData> queriesForQueryType = entry.getValue();
            if (queriesForQueryType.size() <= maxAggregateQueriesPerQueryType) {
                limitAppliedQueries.put(queryType, queriesForQueryType);
            } else {
                limitAppliedQueries.put(queryType, truncate(queriesForQueryType));
            }
        }
        return limitAppliedQueries;
    }

    // not using static ObjectMapper because ObjectMapper caches keys, and in this particular case
    // the keys are (often very large) sql queries and have seen it retain 26mb worth of memory
    public void mergeQueries(String queriesContent, ObjectMapper tempMapper) throws IOException {
        Map<String, Map<String, AggregateQueryData>> toBeMergedQueries =
                ObjectMappers.readRequiredValue(tempMapper, queriesContent,
                        new TypeReference<Map<String, Map<String, AggregateQueryData>>>() {});
        for (Entry<String, Map<String, AggregateQueryData>> entry : toBeMergedQueries.entrySet()) {
            String queryType = entry.getKey();
            Map<String, AggregateQueryData> queriesForQueryType = queries.get(queryType);
            if (queriesForQueryType == null) {
                queriesForQueryType = Maps.newHashMap();
                queries.put(queryType, queriesForQueryType);
            }
            for (Entry<String, AggregateQueryData> query : entry.getValue().entrySet()) {
                AggregateQueryData queryData = query.getValue();
                mergeQueryData(query.getKey(), queryData.getTotalMicros(),
                        queryData.getExecutionCount(), queryData.getTotalRows(),
                        queriesForQueryType);
            }
        }
    }

    void mergeQueries(String queryType, Map<String, QueryData> toBeMergedQueries) {
        Map<String, AggregateQueryData> queriesForQueryType = queries.get(queryType);
        if (queriesForQueryType == null) {
            queriesForQueryType = Maps.newHashMap();
            queries.put(queryType, queriesForQueryType);
        }
        for (Entry<String, QueryData> toBeMergedQuery : toBeMergedQueries.entrySet()) {
            String queryText = toBeMergedQuery.getKey();
            QueryData queryData = toBeMergedQuery.getValue();
            mergeQueryData(queryText, NANOSECONDS.toMicros(queryData.getTotalTime()),
                    queryData.getExecutionCount(), queryData.getTotalRows(),
                    queriesForQueryType);
        }
    }

    private void mergeQueryData(String queryText, long totalMicros, long executionCount,
            long totalRows, Map<String, AggregateQueryData> queriesForQueryType) {
        AggregateQueryData aggregateQueryData = queriesForQueryType.get(queryText);
        if (aggregateQueryData == null) {
            if (applyLimitWhileBuilding
                    && queriesForQueryType.size() >= maxAggregateQueriesPerQueryType) {
                return;
            }
            aggregateQueryData = new AggregateQueryData();
            queriesForQueryType.put(queryText, aggregateQueryData);
        }
        aggregateQueryData.totalMicros += totalMicros;
        aggregateQueryData.executionCount += executionCount;
        aggregateQueryData.totalRows += totalRows;
    }

    private Map<String, AggregateQueryData> truncate(
            Map<String, AggregateQueryData> queriesForQueryType) {
        List<Entry<String, AggregateQueryData>> list =
                Lists.newArrayList(queriesForQueryType.entrySet());
        // reverse sort by total micros
        Collections.sort(list, new Comparator<Entry<String, AggregateQueryData>>() {
            @Override
            public int compare(Entry<String, AggregateQueryData> entry1,
                    Entry<String, AggregateQueryData> entry2) {
                return Longs.compare(entry2.getValue().getTotalMicros(),
                        entry1.getValue().getTotalMicros());
            }
        });
        Map<String, AggregateQueryData> truncatedMap = Maps.newHashMap();
        for (int i = 0; i < maxAggregateQueriesPerQueryType; i++) {
            Entry<String, AggregateQueryData> entry = list.get(i);
            truncatedMap.put(entry.getKey(), entry.getValue());
        }
        return truncatedMap;
    }

    public static class AggregateQueryData {

        private long totalMicros;
        private long executionCount;
        private long totalRows;

        public long getTotalMicros() {
            return totalMicros;
        }

        public long getExecutionCount() {
            return executionCount;
        }

        public long getTotalRows() {
            return totalRows;
        }
    }
}
