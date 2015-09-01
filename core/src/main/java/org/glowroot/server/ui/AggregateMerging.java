/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.server.ui;

import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.immutables.value.Value;

import org.glowroot.collector.spi.Trace;
import org.glowroot.common.model.LazyHistogram;
import org.glowroot.common.model.MutableTimerNode;
import org.glowroot.common.util.Styles;
import org.glowroot.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.server.repo.Utils;

class AggregateMerging {

    private AggregateMerging() {}

    static TimerMergedAggregate getTimerMergedAggregate(List<OverviewAggregate> overviewAggregates)
            throws Exception {
        long transactionCount = 0;
        MutableTimerNode syntheticRootTimer = MutableTimerNode.createSyntheticRootNode();
        for (OverviewAggregate aggregate : overviewAggregates) {
            transactionCount += aggregate.transactionCount();
            syntheticRootTimer.mergeMatchedTimer(aggregate.syntheticRootTimer());
        }
        ImmutableTimerMergedAggregate.Builder timerMergedAggregate =
                ImmutableTimerMergedAggregate.builder();
        timerMergedAggregate.syntheticRootTimer(syntheticRootTimer);
        timerMergedAggregate.transactionCount(transactionCount);
        return timerMergedAggregate.build();
    }

    static PercentileMergedAggregate getPercentileMergedAggregate(
            List<PercentileAggregate> percentileAggregates, List<Double> percentiles)
                    throws Exception {
        long transactionCount = 0;
        double totalNanos = 0;
        LazyHistogram histogram = new LazyHistogram();
        for (PercentileAggregate percentileAggregate : percentileAggregates) {
            transactionCount += percentileAggregate.transactionCount();
            totalNanos += percentileAggregate.totalNanos();
            histogram.merge(percentileAggregate.histogram());
        }

        List<PercentileValue> percentileValues = Lists.newArrayList();
        for (double percentile : percentiles) {
            percentileValues.add(ImmutablePercentileValue.of(
                    Utils.getPercentileWithSuffix(percentile) + " percentile",
                    histogram.getValueAtPercentile(percentile)));
        }
        return ImmutablePercentileMergedAggregate.builder()
                .transactionCount(transactionCount)
                .totalNanos(totalNanos)
                .addAllPercentileValues(percentileValues)
                .build();
    }

    static ThreadInfoAggregate getThreadInfoAggregate(List<OverviewAggregate> overviewAggregates) {
        double totalCpuNanos = Trace.THREAD_DATA_NOT_AVAILABLE;
        double totalBlockedNanos = Trace.THREAD_DATA_NOT_AVAILABLE;
        double totalWaitedNanos = Trace.THREAD_DATA_NOT_AVAILABLE;
        double totalAllocatedBytes = Trace.THREAD_DATA_NOT_AVAILABLE;
        for (OverviewAggregate overviewAggregate : overviewAggregates) {
            totalCpuNanos =
                    notAvailableAwareAdd(totalCpuNanos, overviewAggregate.totalCpuNanos());
            totalBlockedNanos = notAvailableAwareAdd(totalBlockedNanos,
                    overviewAggregate.totalBlockedNanos());
            totalWaitedNanos =
                    notAvailableAwareAdd(totalWaitedNanos, overviewAggregate.totalWaitedNanos());
            totalAllocatedBytes = notAvailableAwareAdd(totalAllocatedBytes,
                    overviewAggregate.totalAllocatedBytes());
        }
        return ImmutableThreadInfoAggregate.builder()
                .totalCpuNanos(totalCpuNanos)
                .totalBlockedNanos(totalBlockedNanos)
                .totalWaitedNanos(totalWaitedNanos)
                .totalAllocatedBytes(totalAllocatedBytes)
                .build();
    }

    private static double notAvailableAwareAdd(double x, double y) {
        if (x == Trace.THREAD_DATA_NOT_AVAILABLE) {
            return y;
        }
        if (y == Trace.THREAD_DATA_NOT_AVAILABLE) {
            return x;
        }
        return x + y;
    }

    @Value.Immutable
    interface TimerMergedAggregate {
        long transactionCount();
        MutableTimerNode syntheticRootTimer();
    }

    @Value.Immutable
    interface PercentileMergedAggregate {
        long transactionCount();
        // aggregates use double instead of long to avoid (unlikely) 292 year nanosecond rollover
        double totalNanos();
        ImmutableList<PercentileValue> percentileValues();
    }

    @Value.Immutable
    @Styles.AllParameters
    interface PercentileValue {
        String dataSeriesName();
        long value();
    }

    @Value.Immutable
    abstract static class ThreadInfoAggregate {

        // aggregates use double instead of long to avoid (unlikely) 292 year nanosecond rollover
        abstract double totalCpuNanos(); // -1 means N/A
        abstract double totalBlockedNanos(); // -1 means N/A
        abstract double totalWaitedNanos(); // -1 means N/A
        abstract double totalAllocatedBytes(); // -1 means N/A

        boolean isEmpty() {
            return totalCpuNanos() == Trace.THREAD_DATA_NOT_AVAILABLE
                    && totalBlockedNanos() == Trace.THREAD_DATA_NOT_AVAILABLE
                    && totalWaitedNanos() == Trace.THREAD_DATA_NOT_AVAILABLE
                    && totalAllocatedBytes() == Trace.THREAD_DATA_NOT_AVAILABLE;
        }
    }
}
