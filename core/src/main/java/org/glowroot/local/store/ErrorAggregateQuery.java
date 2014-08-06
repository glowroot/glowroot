/*
 * Copyright 2014 the original author or authors.
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
package org.glowroot.local.store;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.markers.UsedByJsonBinding;

import static org.glowroot.common.ObjectMappers.checkNotNullItemsForProperty;
import static org.glowroot.common.ObjectMappers.checkRequiredProperty;
import static org.glowroot.common.ObjectMappers.nullToEmpty;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ErrorAggregateQuery {

    private final long from;
    private final long to;
    private final List<String> includes;
    private final List<String> excludes;
    private final ErrorAggregateSortAttribute sortAttribute;
    private final SortDirection sortDirection;
    private final int limit;

    @VisibleForTesting
    ErrorAggregateQuery(long from, long to, List<String> includes, List<String> excludes,
            ErrorAggregateSortAttribute sortAttribute, SortDirection sortDirection, int limit) {
        this.from = from;
        this.to = to;
        this.includes = includes;
        this.excludes = excludes;
        this.sortAttribute = sortAttribute;
        this.sortDirection = sortDirection;
        this.limit = limit;
    }

    long getFrom() {
        return from;
    }

    long getTo() {
        return to;
    }

    List<String> getIncludes() {
        return includes;
    }

    List<String> getExcludes() {
        return excludes;
    }

    ErrorAggregateSortAttribute getSortAttribute() {
        return sortAttribute;
    }

    SortDirection getSortDirection() {
        return sortDirection;
    }

    int getLimit() {
        return limit;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("from", from)
                .add("to", to)
                .add("includes", includes)
                .add("excludes", excludes)
                .add("sortAttribute", sortAttribute)
                .add("sortDirection", sortDirection)
                .add("limit", limit)
                .toString();
    }

    @JsonCreator
    static ErrorAggregateQuery readValue(@JsonProperty("from") @Nullable Long from,
            @JsonProperty("to") @Nullable Long to,
            @JsonProperty("includes") @Nullable List</*@Nullable*/String> uncheckedIncludes,
            @JsonProperty("excludes") @Nullable List</*@Nullable*/String> uncheckedExcludes,
            @JsonProperty("sortAttribute") @Nullable ErrorAggregateSortAttribute sortAttribute,
            @JsonProperty("sortDirection") @Nullable SortDirection sortDirection,
            @JsonProperty("limit") @Nullable Integer limit)
            throws JsonMappingException {
        List<String> includes = checkNotNullItemsForProperty(uncheckedIncludes, "includes");
        List<String> excludes = checkNotNullItemsForProperty(uncheckedExcludes, "excludes");
        checkRequiredProperty(from, "from");
        checkRequiredProperty(to, "to");
        checkRequiredProperty(sortAttribute, "sortAttribute");
        checkRequiredProperty(sortDirection, "sortDirection");
        checkRequiredProperty(limit, "limit");
        return new ErrorAggregateQuery(from, to, nullToEmpty(includes), nullToEmpty(excludes),
                sortAttribute, sortDirection, limit);
    }

    @UsedByJsonBinding
    public static enum ErrorAggregateSortAttribute implements SortAttribute {

        TRANSACTION_NAME("upper(transaction_name)"),
        ERROR("upper(error_message)"),
        COUNT("count(*)");

        private final String column;

        private ErrorAggregateSortAttribute(String column) {
            this.column = column;
        }

        @Override
        public String getColumn() {
            return column;
        }
    }
}
