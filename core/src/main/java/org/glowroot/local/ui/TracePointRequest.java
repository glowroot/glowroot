/*
 * Copyright 2011-2014 the original author or authors.
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
package org.glowroot.local.ui;

import checkers.nullness.quals.Nullable;
import com.google.common.base.Objects;
import dataflow.quals.Pure;

import org.glowroot.markers.UsedByJsonBinding;

/**
 * Structure used to deserialize json post data sent to /backend/trace/points.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@UsedByJsonBinding
class TracePointRequest {

    private long from;
    private long to;
    private double low; // seconds, with millisecond precision
    private double high; // seconds, with millisecond precision
    private boolean errorOnly;
    private boolean fineOnly;
    @Nullable
    private String transactionNameComparator;
    @Nullable
    private String transactionName;
    @Nullable
    private String headlineComparator;
    @Nullable
    private String headline;
    @Nullable
    private String errorComparator;
    @Nullable
    private String error;
    @Nullable
    private String userComparator;
    @Nullable
    private String user;
    @Nullable
    private Boolean background;
    private int limit;

    public long getFrom() {
        return from;
    }

    public void setFrom(long from) {
        this.from = from;
    }

    public long getTo() {
        return to;
    }

    public void setTo(long to) {
        this.to = to;
    }

    public double getLow() {
        return low;
    }

    public void setLow(double low) {
        this.low = low;
    }

    public double getHigh() {
        return high;
    }

    public void setHigh(double high) {
        this.high = high;
    }

    public boolean isErrorOnly() {
        return errorOnly;
    }

    public void setErrorOnly(boolean errorOnly) {
        this.errorOnly = errorOnly;
    }

    public boolean isFineOnly() {
        return fineOnly;
    }

    public void setFineOnly(boolean fineOnly) {
        this.fineOnly = fineOnly;
    }

    @Nullable
    public String getTransactionNameComparator() {
        return transactionNameComparator;
    }

    public void setTransactionNameComparator(@Nullable String transactionNameComparator) {
        this.transactionNameComparator = transactionNameComparator;
    }

    @Nullable
    public String getTransactionName() {
        return transactionName;
    }

    public void setTransactionName(@Nullable String transactionName) {
        this.transactionName = transactionName;
    }

    @Nullable
    public String getHeadlineComparator() {
        return headlineComparator;
    }

    public void setHeadlineComparator(@Nullable String headlineComparator) {
        this.headlineComparator = headlineComparator;
    }

    @Nullable
    public String getHeadline() {
        return headline;
    }

    public void setHeadline(@Nullable String headline) {
        this.headline = headline;
    }

    @Nullable
    public String getErrorComparator() {
        return errorComparator;
    }

    public void setErrorComparator(String errorComparator) {
        this.errorComparator = errorComparator;
    }

    @Nullable
    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    @Nullable
    public String getUserComparator() {
        return userComparator;
    }

    public void setUserComparator(@Nullable String userComparator) {
        this.userComparator = userComparator;
    }

    @Nullable
    public String getUser() {
        return user;
    }

    public void setUser(@Nullable String user) {
        this.user = user;
    }

    @Nullable
    public Boolean isBackground() {
        return background;
    }

    public void setBackground(@Nullable Boolean background) {
        this.background = background;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    @Override
    @Pure
    public String toString() {
        return Objects.toStringHelper(this)
                .add("from", from)
                .add("to", to)
                .add("low", low)
                .add("high", high)
                .add("errorOnly", errorOnly)
                .add("fineOnly", fineOnly)
                .add("headlineComparator", headlineComparator)
                .add("headline", headline)
                .add("userComparator", userComparator)
                .add("user", user)
                .add("background", background)
                .add("limit", limit)
                .toString();
    }
}
