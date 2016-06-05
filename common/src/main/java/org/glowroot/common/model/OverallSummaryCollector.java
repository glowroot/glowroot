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
package org.glowroot.common.model;

import org.immutables.value.Value;

public class OverallSummaryCollector {

    private double totalDurationNanos;
    private long transactionCount;

    private long lastCaptureTime;

    public long getLastCaptureTime() {
        return lastCaptureTime;
    }

    public OverallSummary getOverallSummary() {
        return ImmutableOverallSummary.builder()
                .totalDurationNanos(totalDurationNanos)
                .transactionCount(transactionCount)
                .build();
    }

    public void mergeSummary(double totalDurationNanos, long transactionCount, long captureTime) {
        this.totalDurationNanos += totalDurationNanos;
        this.transactionCount += transactionCount;
        lastCaptureTime = Math.max(lastCaptureTime, captureTime);
    }

    @Value.Immutable
    public interface OverallSummary {
        // aggregates use double instead of long to avoid (unlikely) 292 year nanosecond rollover
        double totalDurationNanos();
        long transactionCount();
    }
}
