/*
 * Copyright 2016-2017 the original author or authors.
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

import org.glowroot.agent.model.QueryCollector.SharedQueryTextCollector;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.Proto.OptionalInt64;

class MutableQuery {

    private MutableDouble totalDurationNanos;
    private long executionCount;

    private boolean hasTotalRows;
    private long totalRows;

    MutableQuery() {
        totalDurationNanos = new MutableDouble();
    }

    double getTotalDurationNanos() {
        return totalDurationNanos.getValue();
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

    void addToTotalDurationNanos(long totalDurationNanos) {
        this.totalDurationNanos.add(totalDurationNanos);
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

    Aggregate.Query toAggregateProto(String queryText,
            SharedQueryTextCollector sharedQueryTextCollector) {
        int sharedQueryTextIndex = sharedQueryTextCollector.getIndex(queryText);
        Aggregate.Query.Builder builder = Aggregate.Query.newBuilder()
                .setSharedQueryTextIndex(sharedQueryTextIndex)
                .setTotalDurationNanos(totalDurationNanos.getValue())
                .setExecutionCount(executionCount);
        if (hasTotalRows) {
            builder.setTotalRows(OptionalInt64.newBuilder().setValue(totalRows));
        }
        return builder.build();
    }

    private static class MutableDouble {

        private double value;

        public void add(long value) {
            this.value += value;
        }

        public double getValue() {
            return value;
        }
    }
}
