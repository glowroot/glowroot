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

import java.util.List;
import java.util.Map;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.primitives.Doubles;
import com.google.common.primitives.Longs;
import org.immutables.value.Value;

public class TransactionSummaryCollector {

    private static final Ordering<TransactionSummary> orderingByTotalTimeDesc =
            new Ordering<TransactionSummary>() {
                @Override
                public int compare(TransactionSummary left, TransactionSummary right) {
                    return Doubles.compare(right.totalDurationNanos(), left.totalDurationNanos());
                }
            };

    private static final Ordering<TransactionSummary> orderingByAverageTimeDesc =
            new Ordering<TransactionSummary>() {
                @Override
                public int compare(TransactionSummary left, TransactionSummary right) {
                    return Doubles.compare(right.totalDurationNanos() / right.transactionCount(),
                            left.totalDurationNanos() / left.transactionCount());
                }
            };

    private static final Ordering<TransactionSummary> orderingByTransactionCountDesc =
            new Ordering<TransactionSummary>() {
                @Override
                public int compare(TransactionSummary left, TransactionSummary right) {
                    return Longs.compare(right.transactionCount(), left.transactionCount());
                }
            };

    private final Map<String, MutableTransactionSummary> transactionSummaries = Maps.newHashMap();

    private long lastCaptureTime;

    public void collect(String transactionName, double totalDurationNanos, long transactionCount,
            long captureTime) {
        MutableTransactionSummary mts = transactionSummaries.get(transactionName);
        if (mts == null) {
            mts = new MutableTransactionSummary();
            transactionSummaries.put(transactionName, mts);
        }
        mts.totalDurationNanos += totalDurationNanos;
        mts.transactionCount += transactionCount;
        lastCaptureTime = Math.max(lastCaptureTime, captureTime);
    }

    public long getLastCaptureTime() {
        return lastCaptureTime;
    }

    public Result<TransactionSummary> getResult(SummarySortOrder sortOrder, int limit) {
        List<TransactionSummary> summaries = Lists.newArrayList();
        for (Map.Entry<String, MutableTransactionSummary> entry : transactionSummaries.entrySet()) {
            summaries.add(ImmutableTransactionSummary.builder()
                    .transactionName(entry.getKey())
                    .totalDurationNanos(entry.getValue().totalDurationNanos)
                    .transactionCount(entry.getValue().transactionCount)
                    .build());
        }
        summaries = sortTransactionSummaries(summaries, sortOrder);
        if (summaries.size() > limit) {
            return new Result<TransactionSummary>(summaries.subList(0, limit), true);
        } else {
            return new Result<TransactionSummary>(summaries, false);
        }
    }

    private static List<TransactionSummary> sortTransactionSummaries(
            Iterable<TransactionSummary> transactionSummaries, SummarySortOrder sortOrder) {
        switch (sortOrder) {
            case TOTAL_TIME:
                return orderingByTotalTimeDesc.immutableSortedCopy(transactionSummaries);
            case AVERAGE_TIME:
                return orderingByAverageTimeDesc.immutableSortedCopy(transactionSummaries);
            case THROUGHPUT:
                return orderingByTransactionCountDesc.immutableSortedCopy(transactionSummaries);
            default:
                throw new AssertionError("Unexpected sort order: " + sortOrder);
        }
    }

    public enum SummarySortOrder {
        TOTAL_TIME, AVERAGE_TIME, THROUGHPUT
    }

    @Value.Immutable
    public interface TransactionSummary {
        String transactionName();
        // aggregates use double instead of long to avoid (unlikely) 292 year nanosecond rollover
        double totalDurationNanos();
        long transactionCount();
    }

    private static class MutableTransactionSummary {
        private double totalDurationNanos;
        private long transactionCount;
    }
}
