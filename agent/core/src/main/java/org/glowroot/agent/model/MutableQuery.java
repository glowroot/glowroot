/*
 * Copyright 2016 the original author or authors.
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

import java.util.Map;

import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.Proto.OptionalInt64;

class MutableQuery {

    private MutableNumber totalDurationNanos;
    private long executionCount;

    private boolean hasTotalRows;
    private long totalRows;

    MutableQuery(boolean traceLevel) {
        totalDurationNanos = traceLevel ? new MutableLong() : new MutableDouble();
    }

    double getTotalDurationNanos() {
        return totalDurationNanos.getDouble();
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
            Map<String, Integer> sharedQueryTextIndexes) {
        Integer sharedQueryTextIndex = sharedQueryTextIndexes.get(queryText);
        if (sharedQueryTextIndex == null) {
            sharedQueryTextIndex = sharedQueryTextIndexes.size();
            sharedQueryTextIndexes.put(queryText, sharedQueryTextIndex);
        }
        Aggregate.Query.Builder builder = Aggregate.Query.newBuilder()
                .setSharedQueryTextIndex(sharedQueryTextIndex)
                .setTotalDurationNanos(totalDurationNanos.getDouble())
                .setExecutionCount(executionCount);
        if (hasTotalRows) {
            builder.setTotalRows(OptionalInt64.newBuilder().setValue(totalRows));
        }
        return builder.build();
    }

    private interface MutableNumber {
        void add(long value);
        long getLong();
        double getDouble();
    }

    private static class MutableLong implements MutableNumber {

        private long value;

        @Override
        public void add(long value) {
            this.value += value;
        }

        @Override
        public long getLong() {
            return value;
        }

        @Override
        public double getDouble() {
            // ok to convert long to double
            return value;
        }
    }

    private static class MutableDouble implements MutableNumber {

        private double value;

        @Override
        public void add(long value) {
            this.value += value;
        }

        @Override
        public long getLong() {
            // not ok to convert double to long
            throw new IllegalStateException();
        }

        @Override
        public double getDouble() {
            return value;
        }
    }
}
