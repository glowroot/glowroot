/*
 * Copyright 2016-2018 the original author or authors.
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
package org.glowroot.agent.model;

import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.Proto.OptionalInt64;

class MutableQuery {

    private double totalDurationNanos;
    private long executionCount;

    private boolean hasTotalRows;
    private long totalRows;

    private boolean active;

    double getTotalDurationNanos() {
        return totalDurationNanos;
    }

    long getExecutionCount() {
        return executionCount;
    }

    boolean hasTotalRows() {
        return hasTotalRows;
    }

    long getTotalRows() {
        return totalRows;
    }

    boolean isActive() {
        return active;
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

    void setActive(boolean active) {
        this.active = active;
    }

    void add(MutableQuery query) {
        addToTotalDurationNanos(query.totalDurationNanos);
        addToExecutionCount(query.executionCount);
        addToTotalRows(query.hasTotalRows, query.totalRows);
        if (query.active) {
            setActive(true);
        }
    }

    void add(Aggregate.Query query) {
        addToTotalDurationNanos(query.getTotalDurationNanos());
        addToExecutionCount(query.getExecutionCount());
        addToTotalRows(query.hasTotalRows(), query.getTotalRows().getValue());
        if (query.getActive()) {
            setActive(true);
        }
    }

    Aggregate.Query toAggregateProto(String queryType, String queryText,
            SharedQueryTextCollection sharedQueryTextCollection, boolean includeActive)
            throws Exception {
        int sharedQueryTextIndex = sharedQueryTextCollection.getSharedQueryTextIndex(queryText);
        Aggregate.Query.Builder builder = Aggregate.Query.newBuilder()
                .setType(queryType)
                .setSharedQueryTextIndex(sharedQueryTextIndex)
                .setTotalDurationNanos(totalDurationNanos)
                .setExecutionCount(executionCount);
        if (hasTotalRows) {
            builder.setTotalRows(OptionalInt64.newBuilder().setValue(totalRows));
        }
        if (includeActive) {
            builder.setActive(active);
        }
        return builder.build();
    }
}
