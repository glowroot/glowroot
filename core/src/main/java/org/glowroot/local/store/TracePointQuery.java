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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import org.checkerframework.checker.nullness.qual.Nullable;

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
    private final String transactionType;
    private final boolean errorOnly;
    private final boolean profiledOnly;
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
    private final String customAttributeName;
    @Nullable
    private final StringComparator customAttributeValueComparator;
    @Nullable
    private final String customAttributeValue;
    private final int limit;

    @VisibleForTesting
    TracePointQuery(long from, long to, long durationLow, @Nullable Long durationHigh,
            @Nullable String transactionType, boolean errorOnly, boolean profiledOnly,
            @Nullable StringComparator transactionNameComparator, @Nullable String transactionName,
            @Nullable StringComparator headlineComparator, @Nullable String headline,
            @Nullable StringComparator errorComparator, @Nullable String error,
            @Nullable StringComparator userComparator, @Nullable String user,
            @Nullable String customAttributeName,
            @Nullable StringComparator customAttributeValueComparator,
            @Nullable String customAttributeValue, int limit) {
        this.from = from;
        this.to = to;
        this.durationLow = durationLow;
        this.durationHigh = durationHigh;
        this.transactionType = transactionType;
        this.errorOnly = errorOnly;
        this.profiledOnly = profiledOnly;
        this.transactionNameComparator = transactionNameComparator;
        this.transactionName = transactionName;
        this.headlineComparator = headlineComparator;
        this.headline = headline;
        this.errorComparator = errorComparator;
        this.error = error;
        this.userComparator = userComparator;
        this.user = user;
        this.customAttributeName = customAttributeName;
        this.customAttributeValueComparator = customAttributeValueComparator;
        this.customAttributeValue = customAttributeValue;
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
    public String getTransactionType() {
        return transactionType;
    }

    public boolean isErrorOnly() {
        return errorOnly;
    }

    public boolean isProfiledOnly() {
        return profiledOnly;
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
    public String getCustomAttributeName() {
        return customAttributeName;
    }

    @Nullable
    public StringComparator getCustomAttributeValueComparator() {
        return customAttributeValueComparator;
    }

    @Nullable
    public String getCustomAttributeValue() {
        return customAttributeValue;
    }

    public int getLimit() {
        return limit;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("from", from)
                .add("to", to)
                .add("durationLow", durationLow)
                .add("durationHigh", durationHigh)
                .add("transactionType", transactionType)
                .add("errorOnly", errorOnly)
                .add("profiledOnly", profiledOnly)
                .add("transactionNameComparator", transactionNameComparator)
                .add("transactionName", transactionName)
                .add("headlineComparator", headlineComparator)
                .add("headline", headline)
                .add("errorComparator", errorComparator)
                .add("error", error)
                .add("userComparator", userComparator)
                .add("user", user)
                .add("customAttributeName", customAttributeName)
                .add("attributeValueComparator", customAttributeValueComparator)
                .add("attributeValue", customAttributeValue)
                .add("limit", limit)
                .toString();
    }

    @JsonCreator
    static TracePointQuery readValue(
            @JsonProperty("from") @Nullable Long from,
            @JsonProperty("to") @Nullable Long to,
            @JsonProperty("durationLow") @Nullable Long durationLow,
            @JsonProperty("durationHigh") @Nullable Long durationHigh,
            @JsonProperty("transactionType") @Nullable String transactionType,
            @JsonProperty("errorOnly") @Nullable Boolean errorOnly,
            @JsonProperty("profiledOnly") @Nullable Boolean profiledOnly,
            @JsonProperty("transactionNameComparator") @Nullable StringComparator transactionNameComparator,
            @JsonProperty("transactionName") @Nullable String transactionName,
            @JsonProperty("headlineComparator") @Nullable StringComparator headlineComparator,
            @JsonProperty("headline") @Nullable String headline,
            @JsonProperty("errorComparator") @Nullable StringComparator errorComparator,
            @JsonProperty("error") @Nullable String error,
            @JsonProperty("userComparator") @Nullable StringComparator userComparator,
            @JsonProperty("user") @Nullable String user,
            @JsonProperty("customAttributeName") @Nullable String customAttributeName,
            @JsonProperty("customAttributeValueComparator") @Nullable StringComparator customAttributeValueComparator,
            @JsonProperty("attributeValue") @Nullable String attributeValue,
            @JsonProperty("limit") @Nullable Integer limit)
            throws JsonMappingException {
        checkRequiredProperty(from, "from");
        checkRequiredProperty(to, "to");
        checkRequiredProperty(limit, "limit");
        return new TracePointQuery(from, to, nullToZero(durationLow), durationHigh,
                transactionType, nullToFalse(errorOnly), nullToFalse(profiledOnly),
                transactionNameComparator, transactionName, headlineComparator, headline,
                errorComparator, error, userComparator, user, customAttributeName,
                customAttributeValueComparator, attributeValue, limit);
    }
}
