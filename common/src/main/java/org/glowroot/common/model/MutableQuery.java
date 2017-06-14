/*
 * Copyright 2015-2017 the original author or authors.
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
package org.glowroot.common.model;

import javax.annotation.Nullable;

import com.google.common.collect.Ordering;
import com.google.common.primitives.Doubles;

public class MutableQuery {

    static final Ordering<MutableQuery> byTotalDurationDesc = new Ordering<MutableQuery>() {
        @Override
        public int compare(MutableQuery left, MutableQuery right) {
            return Doubles.compare(right.getTotalDurationNanos(), left.getTotalDurationNanos());
        }
    };

    private final String truncatedText;
    private final @Nullable String fullTextSha1;

    private double totalDurationNanos;
    private long executionCount;

    private boolean hasTotalRows;
    private long totalRows;

    MutableQuery(String truncatedText, @Nullable String fullTextSha1) {
        this.truncatedText = truncatedText;
        this.fullTextSha1 = fullTextSha1;
    }

    public String getTruncatedText() {
        return truncatedText;
    }

    public @Nullable String getFullTextSha1() {
        return fullTextSha1;
    }

    public double getTotalDurationNanos() {
        return totalDurationNanos;
    }

    public long getExecutionCount() {
        return executionCount;
    }

    public boolean hasTotalRows() {
        return hasTotalRows;
    }

    public long getTotalRows() {
        return totalRows;
    }

    void addToTotalDurationNanos(double totalDurationNanos) {
        this.totalDurationNanos += totalDurationNanos;
    }

    void addToExecutionCount(long executionCount) {
        this.executionCount += executionCount;
    }

    void addToTotalRows(boolean hasTotalRows, long totalRows) {
        if (hasTotalRows) {
            this.hasTotalRows = true;
            this.totalRows += totalRows;
        }
    }

    void addTo(MutableQuery query) {
        addToTotalDurationNanos(query.totalDurationNanos);
        addToExecutionCount(query.executionCount);
        addToTotalRows(query.hasTotalRows, query.totalRows);
    }
}
