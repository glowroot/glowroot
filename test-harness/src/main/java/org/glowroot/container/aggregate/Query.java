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
import com.google.common.base.MoreObjects;

import static org.glowroot.container.common.ObjectMappers.checkRequiredProperty;

public class Query {

    private final String queryType;
    private final String queryText;
    private final long totalMicros;
    private final long executionCount;
    private final long totalRows;
    private final boolean active;

    private Query(String queryType, String queryText, long totalMicros, long executionCount,
            long totalRows, boolean active) {
        this.queryType = queryType;
        this.queryText = queryText;
        this.totalMicros = totalMicros;
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

    public long getTotalMicros() {
        return totalMicros;
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

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("queryType", queryType)
                .add("queryText", queryText)
                .add("totalMicros", totalMicros)
                .add("executionCount", executionCount)
                .add("totalRows", totalRows)
                .add("active", active)
                .toString();
    }

    @JsonCreator
    static Query readValue(
            @JsonProperty("queryType") @Nullable String queryType,
            @JsonProperty("queryText") @Nullable String queryText,
            @JsonProperty("totalMicros") @Nullable Long totalMicros,
            @JsonProperty("executionCount") @Nullable Long executionCount,
            @JsonProperty("totalRows") @Nullable Long totalRows,
            @JsonProperty("active") @Nullable Boolean active)
                    throws JsonMappingException {
        checkRequiredProperty(queryType, "queryType");
        checkRequiredProperty(queryText, "queryText");
        checkRequiredProperty(totalMicros, "totalMicros");
        checkRequiredProperty(executionCount, "executionCount");
        checkRequiredProperty(totalRows, "totalRows");
        return new Query(queryType, queryText, totalMicros, executionCount, totalRows,
                orFalse(active));
    }

    private static boolean orFalse(@Nullable Boolean value) {
        return value != null && value;
    }
}
