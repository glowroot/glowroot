/*
 * Copyright 2013-2014 the original author or authors.
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
import com.google.common.base.Objects;

import static org.glowroot.common.ObjectMappers.checkRequiredProperty;
import static org.glowroot.common.ObjectMappers.nullToFalse;
import static org.glowroot.common.ObjectMappers.nullToZero;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class TracePointQuery {

    private final long from;
    private final long to;
    private final Long durationLow; // nanoseconds
    @Nullable
    private final Long durationHigh; // nanoseconds
    @Nullable
    private final Boolean background;
    private final boolean errorOnly;
    private final boolean fineOnly;
    @Nullable
    private final StringComparator transactionNameComparator;
    @Nullable
    private final String transactionName;
    @Nullable
    private final StringComparator headlineComparator;
    @Nullable
    private final String headline;
    @Nullable
    private final StringComparator errorComparator;
    @Nullable
    private final String error;
    @Nullable
    private final StringComparator userComparator;
    @Nullable
    private final String user;
    @Nullable
    private final String attributeName;
    @Nullable
    private final StringComparator attributeValueComparator;
    @Nullable
    private final String attributeValue;
    private final int limit;

    @VisibleForTesting
    TracePointQuery(long from, long to, long durationLow, @Nullable Long durationHigh,
            @Nullable Boolean background, boolean errorOnly, boolean fineOnly,
            @Nullable StringComparator transactionNameComparator, @Nullable String transactionName,
            @Nullable StringComparator headlineComparator, @Nullable String headline,
            @Nullable StringComparator errorComparator, @Nullable String error,
            @Nullable StringComparator userComparator, @Nullable String user,
            @Nullable String attributeName, @Nullable StringComparator attributeValueComparator,
            @Nullable String attributeValue, int limit) {
        this.from = from;
        this.to = to;
        this.durationLow = durationLow;
        this.durationHigh = durationHigh;
        this.background = background;
        this.errorOnly = errorOnly;
        this.fineOnly = fineOnly;
        this.transactionNameComparator = transactionNameComparator;
        this.transactionName = transactionName;
        this.headlineComparator = headlineComparator;
        this.headline = headline;
        this.errorComparator = errorComparator;
        this.error = error;
        this.userComparator = userComparator;
        this.user = user;
        this.attributeName = attributeName;
        this.attributeValueComparator = attributeValueComparator;
        this.attributeValue = attributeValue;
        this.limit = limit;
    }

    public long getFrom() {
        return from;
    }

    public long getTo() {
        return to;
    }

    public long getDurationLow() {
        return durationLow;
    }

    @Nullable
    public Long getDurationHigh() {
        return durationHigh;
    }

    @Nullable
    public Boolean getBackground() {
        return background;
    }

    public boolean isErrorOnly() {
        return errorOnly;
    }

    public boolean isFineOnly() {
        return fineOnly;
    }

    @Nullable
    public StringComparator getTransactionNameComparator() {
        return transactionNameComparator;
    }

    @Nullable
    public String getTransactionName() {
        return transactionName;
    }

    @Nullable
    public StringComparator getHeadlineComparator() {
        return headlineComparator;
    }

    @Nullable
    public String getHeadline() {
        return headline;
    }

    @Nullable
    public StringComparator getErrorComparator() {
        return errorComparator;
    }

    @Nullable
    public String getError() {
        return error;
    }

    @Nullable
    public StringComparator getUserComparator() {
        return userComparator;
    }

    @Nullable
    public String getUser() {
        return user;
    }

    @Nullable
    public String getAttributeName() {
        return attributeName;
    }

    @Nullable
    public StringComparator getAttributeValueComparator() {
        return attributeValueComparator;
    }

    @Nullable
    public String getAttributeValue() {
        return attributeValue;
    }

    public int getLimit() {
        return limit;
    }

    /*@Pure*/
    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("from", from)
                .add("to", to)
                .add("durationLow", durationLow)
                .add("durationHigh", durationHigh)
                .add("background", background)
                .add("errorOnly", errorOnly)
                .add("fineOnly", fineOnly)
                .add("transactionNameComparator", transactionNameComparator)
                .add("transactionName", transactionName)
                .add("headlineComparator", headlineComparator)
                .add("headline", headline)
                .add("errorComparator", errorComparator)
                .add("error", error)
                .add("userComparator", userComparator)
                .add("user", user)
                .add("attributeName", attributeName)
                .add("attributeValueComparator", attributeValueComparator)
                .add("attributeValue", attributeValue)
                .add("limit", limit)
                .toString();
    }

    @JsonCreator
    static TracePointQuery readValue(
            @JsonProperty("from") @Nullable Long from,
            @JsonProperty("to") @Nullable Long to,
            @JsonProperty("durationLow") @Nullable Long durationLow,
            @JsonProperty("durationHigh") @Nullable Long durationHigh,
            @JsonProperty("background") @Nullable Boolean background,
            @JsonProperty("errorOnly") @Nullable Boolean errorOnly,
            @JsonProperty("fineOnly") @Nullable Boolean fineOnly,
            @JsonProperty("transactionNameComparator") @Nullable StringComparator transactionNameComparator,
            @JsonProperty("transactionName") @Nullable String transactionName,
            @JsonProperty("headlineComparator") @Nullable StringComparator headlineComparator,
            @JsonProperty("headline") @Nullable String headline,
            @JsonProperty("errorComparator") @Nullable StringComparator errorComparator,
            @JsonProperty("error") @Nullable String error,
            @JsonProperty("userComparator") @Nullable StringComparator userComparator,
            @JsonProperty("user") @Nullable String user,
            @JsonProperty("attributeName") @Nullable String attributeName,
            @JsonProperty("attributeValueComparator") @Nullable StringComparator attributeValueComparator,
            @JsonProperty("attributeValue") @Nullable String attributeValue,
            @JsonProperty("limit") @Nullable Integer limit)
            throws JsonMappingException {
        checkRequiredProperty(from, "from");
        checkRequiredProperty(to, "to");
        checkRequiredProperty(limit, "limit");
        return new TracePointQuery(from, to, nullToZero(durationLow), durationHigh, background,
                nullToFalse(errorOnly), nullToFalse(fineOnly), transactionNameComparator,
                transactionName, headlineComparator, headline, errorComparator, error,
                userComparator, user, attributeName, attributeValueComparator, attributeValue,
                limit);
    }
}
