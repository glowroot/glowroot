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
package org.glowroot.common.repo;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;

import org.glowroot.collector.spi.Query;
import org.glowroot.markers.UsedByJsonBinding;

import static org.glowroot.common.util.ObjectMappers.checkRequiredProperty;

@UsedByJsonBinding
public class MutableQuery implements Query {

    private final String queryText;
    private double totalNanos;
    private long executionCount;
    private long totalRows;

    MutableQuery(String queryText) {
        this.queryText = queryText;
    }

    @Override
    @JsonProperty("queryText")
    public String queryText() {
        return queryText;
    }

    @Override
    @JsonProperty("totalNanos")
    public double totalNanos() {
        return totalNanos;
    }

    @Override
    @JsonProperty("executionCount")
    public long executionCount() {
        return executionCount;
    }

    @Override
    @JsonProperty("totalRows")
    public long totalRows() {
        return totalRows;
    }

    MutableQuery copy() {
        MutableQuery copy = new MutableQuery(queryText);
        copy.totalNanos = totalNanos;
        copy.executionCount = executionCount;
        copy.totalRows = totalRows;
        return copy;
    }

    void addToTotalNanos(double totalNanos) {
        this.totalNanos += totalNanos;
    }

    void addToExecutionCount(long executionCount) {
        this.executionCount += executionCount;
    }

    void addToTotalRows(long totalRows) {
        this.totalRows += totalRows;
    }

    @JsonCreator
    static MutableQuery readValue(@JsonProperty("queryText") @Nullable String queryText,
            @JsonProperty("totalNanos") @Nullable Double totalNanos,
            @JsonProperty("executionCount") @Nullable Long executionCount,
            @JsonProperty("totalRows") @Nullable Long totalRows) throws JsonMappingException {
        checkRequiredProperty(queryText, "queryText");
        checkRequiredProperty(totalNanos, "totalNanos");
        checkRequiredProperty(executionCount, "executionCount");
        checkRequiredProperty(totalRows, "totalRows");
        MutableQuery aggregateQuery = new MutableQuery(queryText);
        aggregateQuery.totalNanos = totalNanos;
        aggregateQuery.executionCount = executionCount;
        aggregateQuery.totalRows = totalRows;
        return aggregateQuery;
    }
}
