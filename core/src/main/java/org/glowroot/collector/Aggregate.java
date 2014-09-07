/*
 * Copyright 2013-2014 the original author or authors.
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
package org.glowroot.collector;

import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.markers.Immutable;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class Aggregate {

    private final String transactionType;
    @Nullable
    private final String transactionName;
    private final long captureTime;
    // aggregation uses microseconds to avoid (unlikely) 292 year nanosecond rollover
    private final long totalMicros;
    private final long count;
    private final long errorCount;
    private final long traceCount;
    private final String metrics;
    private final Existence profileExistence;
    @Nullable
    private final String profile;

    public Aggregate(String transactionType, @Nullable String transactionName, long captureTime,
            long totalMicros, long count, long errorCount, long traceCount, String metrics,
            Existence profileExistence, @Nullable String profile) {
        this.transactionType = transactionType;
        this.transactionName = transactionName;
        this.captureTime = captureTime;
        this.totalMicros = totalMicros;
        this.count = count;
        this.errorCount = errorCount;
        this.traceCount = traceCount;
        this.metrics = metrics;
        this.profileExistence = profileExistence;
        this.profile = profile;
    }

    public String getTransactionType() {
        return transactionType;
    }

    @Nullable
    public String getTransactionName() {
        return transactionName;
    }

    public long getCaptureTime() {
        return captureTime;
    }

    public long getTotalMicros() {
        return totalMicros;
    }

    public long getCount() {
        return count;
    }

    public long getErrorCount() {
        return errorCount;
    }

    public long getTraceCount() {
        return traceCount;
    }

    public String getMetrics() {
        return metrics;
    }

    public Existence getProfileExistence() {
        return profileExistence;
    }

    @Nullable
    public String getProfile() {
        return profile;
    }
}
