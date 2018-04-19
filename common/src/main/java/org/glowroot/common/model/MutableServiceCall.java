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
package org.glowroot.common.model;

import com.google.common.collect.Ordering;
import com.google.common.primitives.Doubles;

public class MutableServiceCall {

    static final Ordering<MutableServiceCall> byTotalDurationDesc =
            new Ordering<MutableServiceCall>() {
                @Override
                public int compare(MutableServiceCall left, MutableServiceCall right) {
                    return Doubles.compare(right.totalDurationNanos, left.totalDurationNanos);
                }
            };

    private final String type;
    private final String text;
    private double totalDurationNanos;
    private long executionCount;

    MutableServiceCall(String type, String text) {
        this.type = type;
        this.text = text;
    }

    public String getType() {
        return type;
    }

    public String getText() {
        return text;
    }

    public double getTotalDurationNanos() {
        return totalDurationNanos;
    }

    public long getExecutionCount() {
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
}
