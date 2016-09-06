/*
 * Copyright 2015-2016 the original author or authors.
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

    private final String truncatedQueryText;
    private final @Nullable String fullQueryTextSha1;

    private double totalDurationNanos;
    private long executionCount;

    private boolean hasTotalRows;
    private long totalRows;

    MutableQuery(String truncatedQueryText, @Nullable String fullQueryTextSha1) {
        this.truncatedQueryText = truncatedQueryText;
        this.fullQueryTextSha1 = fullQueryTextSha1;
    }

    public String getTruncatedQueryText() {
        return truncatedQueryText;
    }

    public @Nullable String getFullQueryTextSha1() {
        return fullQueryTextSha1;
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
}
