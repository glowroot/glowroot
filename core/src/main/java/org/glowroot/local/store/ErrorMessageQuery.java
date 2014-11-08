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
import com.google.common.base.MoreObjects;
import org.checkerframework.checker.nullness.qual.Nullable;

import static org.glowroot.common.ObjectMappers.checkNotNullItemsForProperty;
import static org.glowroot.common.ObjectMappers.checkRequiredProperty;
import static org.glowroot.common.ObjectMappers.nullToEmpty;

public class ErrorMessageQuery {

    @Nullable
    private final String transactionType;
    @Nullable
    private final String transactionName;
    private final long from;
    private final long to;
    private final List<String> includes;
    private final List<String> excludes;
    private final int limit;

    @VisibleForTesting
    ErrorMessageQuery(@Nullable String transactionType, @Nullable String transactionName,
            long from, long to, List<String> includes, List<String> excludes, int limit) {
        this.transactionType = transactionType;
        this.transactionName = transactionName;
        this.from = from;
        this.to = to;
        this.includes = includes;
        this.excludes = excludes;
        this.limit = limit;
    }

    @Nullable
    public String getTransactionType() {
        return transactionType;
    }

    @Nullable
    public String getTransactionName() {
        return transactionName;
    }

    public long getFrom() {
        return from;
    }

    public long getTo() {
        return to;
    }

    public List<String> getIncludes() {
        return includes;
    }

    public List<String> getExcludes() {
        return excludes;
    }

    public int getLimit() {
        return limit;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("from", from)
                .add("to", to)
                .add("includes", includes)
                .add("excludes", excludes)
                .add("limit", limit)
                .toString();
    }

    @JsonCreator
    static ErrorMessageQuery readValue(
            @JsonProperty("transactionType") @Nullable String transactionType,
            @JsonProperty("transactionName") @Nullable String transactionName,
            @JsonProperty("from") @Nullable Long from,
            @JsonProperty("to") @Nullable Long to,
            @JsonProperty("includes") @Nullable List</*@Nullable*/String> uncheckedIncludes,
            @JsonProperty("excludes") @Nullable List</*@Nullable*/String> uncheckedExcludes,
            @JsonProperty("limit") @Nullable Integer limit)
            throws JsonMappingException {
        List<String> includes = checkNotNullItemsForProperty(uncheckedIncludes, "includes");
        List<String> excludes = checkNotNullItemsForProperty(uncheckedExcludes, "excludes");
        checkRequiredProperty(from, "from");
        checkRequiredProperty(to, "to");
        checkRequiredProperty(limit, "limit");
        return new ErrorMessageQuery(transactionType, transactionName, from, to,
                nullToEmpty(includes), nullToEmpty(excludes), limit);
    }
}
