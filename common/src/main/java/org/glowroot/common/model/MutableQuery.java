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

import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.Proto.OptionalInt64;

class MutableQuery {

    private final String queryText;

    private double totalDurationNanos;
    private long executionCount;

    private boolean rowNavigationAttempted;
    private long totalRows;

    MutableQuery(String queryText) {
        this.queryText = queryText;
    }

    void addToTotalDurationNanos(double totalDurationNanos) {
        this.totalDurationNanos += totalDurationNanos;
    }

    void addToExecutionCount(long executionCount) {
        this.executionCount += executionCount;
    }

    void addToTotalRows(boolean rowNavigationAttempted, long totalRows) {
        if (rowNavigationAttempted) {
            this.rowNavigationAttempted = true;
            this.totalRows += totalRows;
        }
    }

    Aggregate.Query toProto() {
        Aggregate.Query.Builder builder = Aggregate.Query.newBuilder()
                .setText(queryText)
                .setTotalDurationNanos(totalDurationNanos)
                .setExecutionCount(executionCount);
        if (rowNavigationAttempted) {
            builder.setTotalRows(OptionalInt64.newBuilder().setValue(totalRows));
        }
        return builder.build();
    }
}
