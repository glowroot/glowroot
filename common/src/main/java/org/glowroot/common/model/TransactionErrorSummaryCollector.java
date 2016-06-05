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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.primitives.Doubles;
import com.google.common.primitives.Longs;
import org.immutables.value.Value;

public class TransactionErrorSummaryCollector {

    @VisibleForTesting
    static final Ordering<TransactionErrorSummary> orderingByErrorCountDesc =
            new Ordering<TransactionErrorSummary>() {
                @Override
                public int compare(TransactionErrorSummary left, TransactionErrorSummary right) {
                    return Longs.compare(right.errorCount(), left.errorCount());
                }
            };

    @VisibleForTesting
    static final Ordering<TransactionErrorSummary> orderingByErrorRateDesc =
            new Ordering<TransactionErrorSummary>() {
                @Override
                public int compare(TransactionErrorSummary left, TransactionErrorSummary right) {
                    return Doubles.compare(right.errorCount() / (double) right.transactionCount(),
                            left.errorCount() / (double) left.transactionCount());
                }
            };

    private final Map<String, MutableTransactionErrorSummary> transactionErrorSummaries =
            Maps.newHashMap();

    private long lastCaptureTime;

    public void collect(String transactionName, long errorCount, long transactionCount,
            long captureTime) {
        MutableTransactionErrorSummary mtes = transactionErrorSummaries.get(transactionName);
        if (mtes == null) {
            mtes = new MutableTransactionErrorSummary();
            transactionErrorSummaries.put(transactionName, mtes);
        }
        mtes.errorCount += errorCount;
        mtes.transactionCount += transactionCount;
        lastCaptureTime = Math.max(lastCaptureTime, captureTime);
    }

    public long getLastCaptureTime() {
        return lastCaptureTime;
    }

    public Result<TransactionErrorSummary> getResult(ErrorSummarySortOrder sortOrder, int limit) {
        List<TransactionErrorSummary> summaries = Lists.newArrayList();
        for (Map.Entry<String, MutableTransactionErrorSummary> entry : transactionErrorSummaries
                .entrySet()) {
            summaries.add(ImmutableTransactionErrorSummary.builder()
                    .transactionName(entry.getKey())
                    .errorCount(entry.getValue().errorCount)
                    .transactionCount(entry.getValue().transactionCount)
                    .build());
        }
        summaries = sortTransactionErrorSummaries(summaries, sortOrder);
        if (summaries.size() > limit) {
            return new Result<TransactionErrorSummary>(summaries.subList(0, limit), true);
        } else {
            return new Result<TransactionErrorSummary>(summaries, false);
        }
    }

    private static List<TransactionErrorSummary> sortTransactionErrorSummaries(
            Iterable<TransactionErrorSummary> errorSummaries,
            ErrorSummarySortOrder sortOrder) {
        switch (sortOrder) {
            case ERROR_COUNT:
                return orderingByErrorCountDesc.immutableSortedCopy(errorSummaries);
            case ERROR_RATE:
                return orderingByErrorRateDesc.immutableSortedCopy(errorSummaries);
            default:
                throw new AssertionError("Unexpected sort order: " + sortOrder);
        }
    }

    private static class MutableTransactionErrorSummary {
        private long errorCount;
        private long transactionCount;
    }

    public enum ErrorSummarySortOrder {
        ERROR_COUNT, ERROR_RATE
    }

    @Value.Immutable
    public interface TransactionErrorSummary {
        String transactionName();
        long errorCount();
        long transactionCount();
    }
}
