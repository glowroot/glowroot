/*
 * Copyright 2015-2018 the original author or authors.
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

import java.util.List;
import java.util.Map;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.primitives.Doubles;
import com.google.common.primitives.Longs;
import org.immutables.value.Value;

public class TransactionNameSummaryCollector {

    private static final Ordering<TransactionNameSummary> orderingByTotalTimeDesc =
            new Ordering<TransactionNameSummary>() {
                @Override
                public int compare(TransactionNameSummary left, TransactionNameSummary right) {
                    return Doubles.compare(right.totalDurationNanos(), left.totalDurationNanos());
                }
            };

    private static final Ordering<TransactionNameSummary> orderingByAverageTimeDesc =
            new Ordering<TransactionNameSummary>() {
                @Override
                public int compare(TransactionNameSummary left, TransactionNameSummary right) {
                    return Doubles.compare(right.totalDurationNanos() / right.transactionCount(),
                            left.totalDurationNanos() / left.transactionCount());
                }
            };

    private static final Ordering<TransactionNameSummary> orderingByTransactionCountDesc =
            new Ordering<TransactionNameSummary>() {
                @Override
                public int compare(TransactionNameSummary left, TransactionNameSummary right) {
                    return Longs.compare(right.transactionCount(), left.transactionCount());
                }
            };

    private static final Ordering<TransactionNameSummary> orderingByTotalCpuTimeDesc =
            new Ordering<TransactionNameSummary>() {
                @Override
                public int compare(TransactionNameSummary left, TransactionNameSummary right) {
                    return Doubles.compare(right.totalCpuNanos(), left.totalCpuNanos());
                }
            };

    private static final Ordering<TransactionNameSummary> orderingByTotalAllocatedMemoryDesc =
            new Ordering<TransactionNameSummary>() {
                @Override
                public int compare(TransactionNameSummary left, TransactionNameSummary right) {
                    return Doubles.compare(right.totalAllocatedBytes(), left.totalAllocatedBytes());
                }
            };

    private final Map<String, MutableTransactionNameSummary> transactionNameSummaries =
            Maps.newHashMap();

    private long lastCaptureTime;

    public void collect(String transactionName, double totalDurationNanos, long transactionCount,
            double totalCpuNanos, double totalAllocatedBytes, long captureTime) {
        MutableTransactionNameSummary mts = transactionNameSummaries.get(transactionName);
        if (mts == null) {
            mts = new MutableTransactionNameSummary();
            transactionNameSummaries.put(transactionName, mts);
        }
        mts.totalDurationNanos += totalDurationNanos;
        mts.transactionCount += transactionCount;
        mts.totalCpuNanos += totalCpuNanos;
        mts.totalAllocatedBytes += totalAllocatedBytes;
        lastCaptureTime = Math.max(lastCaptureTime, captureTime);
    }

    public long getLastCaptureTime() {
        return lastCaptureTime;
    }

    public Result<TransactionNameSummary> getResult(SummarySortOrder sortOrder, int limit) {
        List<TransactionNameSummary> summaries = Lists.newArrayList();
        for (Map.Entry<String, MutableTransactionNameSummary> entry : transactionNameSummaries
                .entrySet()) {
            summaries.add(ImmutableTransactionNameSummary.builder()
                    .transactionName(entry.getKey())
                    .totalDurationNanos(entry.getValue().totalDurationNanos)
                    .transactionCount(entry.getValue().transactionCount)
                    .totalCpuNanos(entry.getValue().totalCpuNanos)
                    .totalAllocatedBytes(entry.getValue().totalAllocatedBytes)
                    .build());
        }
        summaries = sortTransactionNameSummaries(summaries, sortOrder);
        if (summaries.size() > limit) {
            return new Result<TransactionNameSummary>(summaries.subList(0, limit), true);
        } else {
            return new Result<TransactionNameSummary>(summaries, false);
        }
    }

    private static List<TransactionNameSummary> sortTransactionNameSummaries(
            Iterable<TransactionNameSummary> transactionNameSummaries, SummarySortOrder sortOrder) {
        switch (sortOrder) {
        case TOTAL_TIME:
            return orderingByTotalTimeDesc.immutableSortedCopy(transactionNameSummaries);
        case AVERAGE_TIME:
            return orderingByAverageTimeDesc.immutableSortedCopy(transactionNameSummaries);
        case THROUGHPUT:
            return orderingByTransactionCountDesc.immutableSortedCopy(transactionNameSummaries);
        case TOTAL_CPU_TIME:
            return orderingByTotalCpuTimeDesc.immutableSortedCopy(transactionNameSummaries);
        case TOTAL_ALLOCATED_MEMORY:
            return orderingByTotalAllocatedMemoryDesc.immutableSortedCopy(transactionNameSummaries);
        default:
            throw new AssertionError("Unexpected sort order: " + sortOrder);
        }
    }

    public enum SummarySortOrder {
        TOTAL_TIME, AVERAGE_TIME, THROUGHPUT, TOTAL_CPU_TIME, TOTAL_ALLOCATED_MEMORY
    }

    @Value.Immutable
    public interface TransactionNameSummary {
        String transactionName();
        // aggregates use double instead of long to avoid (unlikely) 292 year nanosecond rollover
        double totalDurationNanos();
        long transactionCount();
        double totalCpuNanos();
        double totalAllocatedBytes();
    }

    private static class MutableTransactionNameSummary {
        private double totalDurationNanos;
        private long transactionCount;
        private double totalCpuNanos;
        private double totalAllocatedBytes;
    }
}
