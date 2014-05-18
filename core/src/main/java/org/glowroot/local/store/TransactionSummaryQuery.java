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

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.annotations.VisibleForTesting;

import org.glowroot.markers.UsedByJsonBinding;

import static org.glowroot.common.ObjectMappers.checkRequiredProperty;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class TransactionSummaryQuery {

    private final String transactionType; // currently only "" and "bg" are supported
    private final long from;
    private final long to;
    private final TransactionSortAttribute sortAttribute;
    private final SortDirection sortDirection;
    private final int limit;

    @VisibleForTesting
    TransactionSummaryQuery(String transactionType, long from, long to,
            TransactionSortAttribute sortAttribute, SortDirection sortDirection, int limit) {
        this.transactionType = transactionType;
        this.from = from;
        this.to = to;
        this.sortAttribute = sortAttribute;
        this.sortDirection = sortDirection;
        this.limit = limit;
    }

    public String getTransactionType() {
        return transactionType;
    }

    public long getFrom() {
        return from;
    }

    public long getTo() {
        return to;
    }

    public TransactionSortAttribute getSortAttribute() {
        return sortAttribute;
    }

    public SortDirection getSortDirection() {
        return sortDirection;
    }

    public int getLimit() {
        return limit;
    }

    @JsonCreator
    static TransactionSummaryQuery readValue(
            @JsonProperty("transactionType") @Nullable String transactionType,
            @JsonProperty("from") @Nullable Long from,
            @JsonProperty("to") @Nullable Long to,
            @JsonProperty("sortAttribute") @Nullable TransactionSortAttribute sortAttribute,
            @JsonProperty("sortDirection") @Nullable SortDirection sortDirection,
            @JsonProperty("limit") @Nullable Integer limit)
            throws JsonMappingException {
        checkRequiredProperty(transactionType, "transactionType");
        checkRequiredProperty(from, "from");
        checkRequiredProperty(to, "to");
        checkRequiredProperty(sortAttribute, "sortAttribute");
        checkRequiredProperty(sortDirection, "sortDirection");
        checkRequiredProperty(limit, "limit");
        return new TransactionSummaryQuery(transactionType, from, to, sortAttribute, sortDirection,
                limit);
    }

    @UsedByJsonBinding
    public static enum TransactionSortAttribute implements SortAttribute {

        TOTAL("sum(total_micros)"),
        AVERAGE("sum(total_micros) / sum(count)"),
        COUNT("sum(count)"),
        ERROR_COUNT("sum(error_count)"),
        STORED_TRACE_COUNT("sum(stored_trace_count)");

        private final String column;

        private TransactionSortAttribute(String column) {
            this.column = column;
        }

        @Override
        public String getColumn() {
            return column;
        }
    }
}
