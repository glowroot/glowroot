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
package org.glowroot.container.aggregate;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;

import static org.glowroot.container.common.ObjectMappers.checkRequiredProperty;

public class Query {

    private final String queryType;
    private final String queryText;
    private final double totalNanos;
    private final long executionCount;
    private final long totalRows;
    private final boolean active;

    private Query(String queryType, String queryText, double totalNanos, long executionCount,
            long totalRows, boolean active) {
        this.queryType = queryType;
        this.queryText = queryText;
        this.totalNanos = totalNanos;
        this.executionCount = executionCount;
        this.totalRows = totalRows;
        this.active = active;
    }

    public String getQueryType() {
        return queryType;
    }

    public String getQueryText() {
        return queryText;
    }

    public double getTotalNanos() {
        return totalNanos;
    }

    public long getExecutionCount() {
        return executionCount;
    }

    public long getTotalRows() {
        return totalRows;
    }

    public boolean isActive() {
        return active;
    }

    @JsonCreator
    static Query readValue(
            @JsonProperty("queryType") @Nullable String queryType,
            @JsonProperty("queryText") @Nullable String queryText,
            @JsonProperty("totalNanos") @Nullable Double totalNanos,
            @JsonProperty("executionCount") @Nullable Long executionCount,
            @JsonProperty("totalRows") @Nullable Long totalRows,
            @JsonProperty("active") @Nullable Boolean active)
                    throws JsonMappingException {
        checkRequiredProperty(queryType, "queryType");
        checkRequiredProperty(queryText, "queryText");
        checkRequiredProperty(totalNanos, "totalNanos");
        checkRequiredProperty(executionCount, "executionCount");
        checkRequiredProperty(totalRows, "totalRows");
        return new Query(queryType, queryText, totalNanos, executionCount, totalRows,
                orFalse(active));
    }

    private static boolean orFalse(@Nullable Boolean value) {
        return value != null && value;
    }
}
