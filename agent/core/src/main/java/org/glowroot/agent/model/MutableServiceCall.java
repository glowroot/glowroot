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

class MutableServiceCall {

    private double totalDurationNanos;
    private long executionCount;

    double getTotalDurationNanos() {
        return totalDurationNanos;
    }

    long getExecutionCount() {
        return executionCount;
    }

    void addToTotalDurationNanos(double totalDurationNanos) {
        this.totalDurationNanos += totalDurationNanos;
    }

    void addToExecutionCount(long executionCount) {
        this.executionCount += executionCount;
    }

    void add(MutableServiceCall serviceCall) {
        addToTotalDurationNanos(serviceCall.totalDurationNanos);
        addToExecutionCount(serviceCall.executionCount);
    }

    void add(Aggregate.ServiceCall serviceCall) {
        addToTotalDurationNanos(serviceCall.getTotalDurationNanos());
        addToExecutionCount(serviceCall.getExecutionCount());
    }

    Aggregate.ServiceCall toAggregateProto(String serviceCallType, String serviceCallText) {
        Aggregate.ServiceCall.Builder builder = Aggregate.ServiceCall.newBuilder()
                .setType(serviceCallType)
                .setText(serviceCallText)
                .setTotalDurationNanos(totalDurationNanos)
                .setExecutionCount(executionCount);
        return builder.build();
    }
}
