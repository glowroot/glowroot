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

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.primitives.Longs;

import org.glowroot.common.ObjectMappers;
import org.glowroot.markers.UsedByJsonBinding;
import org.glowroot.transaction.model.QueryData;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.glowroot.common.ObjectMappers.checkRequiredProperty;

public class QueryComponent {

    private static final ObjectMapper mapper = ObjectMappers.create();

    private final Map<String, Map<String, AggregateQuery>> queries = Maps.newHashMap();
    private final int hardLimitMultiplierWhileBuilding;
    private final int maxMultiplierWhileBuilding;

    public QueryComponent(int hardLimitMultiplierWhileBuilding, int maxMultiplierWhileBuilding) {
        this.hardLimitMultiplierWhileBuilding = hardLimitMultiplierWhileBuilding;
        this.maxMultiplierWhileBuilding = maxMultiplierWhileBuilding;
    }

    public Map<String, List<AggregateQuery>> getOrderedAndTruncatedQueries() {
        Map<String, List<AggregateQuery>> mergedQueries = Maps.newHashMap();
        for (Entry<String, Map<String, AggregateQuery>> entry : queries.entrySet()) {
            List<AggregateQuery> aggregateQueries = Lists.newArrayList(entry.getValue().values());
            order(aggregateQueries);
            if (aggregateQueries.size() > hardLimitMultiplierWhileBuilding) {
                aggregateQueries = aggregateQueries.subList(0, hardLimitMultiplierWhileBuilding);
            }
            mergedQueries.put(entry.getKey(), aggregateQueries);
        }
        return mergedQueries;
    }

    // not using static ObjectMapper because ObjectMapper caches keys, and in this particular case
    // the keys are (often very large) sql queries and have seen it retain 26mb worth of memory
    public void mergeQueries(String queriesContent) throws IOException {
        Map<String, List<AggregateQuery>> toBeMergedQueries =
                ObjectMappers.readRequiredValue(mapper, queriesContent,
                        new TypeReference<Map<String, List<AggregateQuery>>>() {});
        for (Entry<String, List<AggregateQuery>> entry : toBeMergedQueries.entrySet()) {
            String queryType = entry.getKey();
            Map<String, AggregateQuery> queriesForQueryType = queries.get(queryType);
            if (queriesForQueryType == null) {
                queriesForQueryType = Maps.newHashMap();
                queries.put(queryType, queriesForQueryType);
            }
            for (AggregateQuery query : entry.getValue()) {
                mergeQuery(query.getQueryText(), query.getTotalMicros(),
                        query.getExecutionCount(), query.getTotalRows(), queriesForQueryType);
            }
        }
    }

    void mergeQuery(QueryData queryData) {
        Map<String, AggregateQuery> queriesForQueryType = queries.get(queryData.getQueryType());
        if (queriesForQueryType == null) {
            queriesForQueryType = Maps.newHashMap();
            queries.put(queryData.getQueryType(), queriesForQueryType);
        }
        mergeQuery(queryData.getQueryText(), NANOSECONDS.toMicros(queryData.getTotalTime()),
                queryData.getExecutionCount(), queryData.getTotalRows(), queriesForQueryType);
    }

    private void mergeQuery(String queryText, long totalMicros, long executionCount,
            long totalRows, Map<String, AggregateQuery> queriesForQueryType) {
        AggregateQuery aggregateQuery = queriesForQueryType.get(queryText);
        if (aggregateQuery == null) {
            if (maxMultiplierWhileBuilding != 0
                    && queriesForQueryType.size() >= hardLimitMultiplierWhileBuilding
                            * maxMultiplierWhileBuilding) {
                return;
            }
            aggregateQuery = new AggregateQuery(queryText);
            queriesForQueryType.put(queryText, aggregateQuery);
        }
        aggregateQuery.totalMicros += totalMicros;
        aggregateQuery.executionCount += executionCount;
        aggregateQuery.totalRows += totalRows;
    }

    private void order(List<AggregateQuery> aggregateQueries) {
        // reverse sort by total micros
        Collections.sort(aggregateQueries, new Comparator<AggregateQuery>() {
            @Override
            public int compare(AggregateQuery aggregateQuery1, AggregateQuery aggregateQuery2) {
                return Longs.compare(aggregateQuery2.getTotalMicros(),
                        aggregateQuery1.getTotalMicros());
            }
        });
    }

    @UsedByJsonBinding
    public static class AggregateQuery {

        private final String queryText;
        private long totalMicros;
        private long executionCount;
        private long totalRows;

        private AggregateQuery(String queryText) {
            this.queryText = queryText;
        }

        public String getQueryText() {
            return queryText;
        }

        public long getTotalMicros() {
            return totalMicros;
        }

        public long getExecutionCount() {
            return executionCount;
        }

        public long getTotalRows() {
            return totalRows;
        }

        @JsonCreator
        static AggregateQuery readValue(
                @JsonProperty("queryText") @Nullable String queryText,
                @JsonProperty("totalMicros") @Nullable Long totalMicros,
                @JsonProperty("executionCount") @Nullable Long executionCount,
                @JsonProperty("totalRows") @Nullable Long totalRows) throws JsonMappingException {
            checkRequiredProperty(queryText, "queryText");
            checkRequiredProperty(totalMicros, "totalMicros");
            checkRequiredProperty(executionCount, "executionCount");
            checkRequiredProperty(totalRows, "totalRows");
            AggregateQuery aggregateQuery = new AggregateQuery(queryText);
            aggregateQuery.totalMicros = totalMicros;
            aggregateQuery.executionCount = executionCount;
            aggregateQuery.totalRows = totalRows;
            return aggregateQuery;
        }
    }
}
