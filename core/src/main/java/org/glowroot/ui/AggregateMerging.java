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
package org.glowroot.ui;

import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.immutables.value.Value;

import org.glowroot.agent.model.ThreadInfoComponent.ThreadInfoData;
import org.glowroot.common.repo.AggregateRepository.OverviewAggregate;
import org.glowroot.common.repo.AggregateRepository.PercentileAggregate;
import org.glowroot.common.repo.LazyHistogram;
import org.glowroot.common.repo.MutableTimerNode;
import org.glowroot.common.repo.Utils;
import org.glowroot.common.util.Styles;

public class AggregateMerging {

    private AggregateMerging() {}

    public static TimerMergedAggregate getTimerMergedAggregate(
            List<OverviewAggregate> overviewAggregates) throws Exception {
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

    public static PercentileMergedAggregate getPercentileMergedAggregate(
            List<PercentileAggregate> percentileAggregates, List<Double> percentiles)
                    throws Exception {
        long transactionCount = 0;
        long totalMicros = 0;
        LazyHistogram histogram = new LazyHistogram();
        for (PercentileAggregate percentileAggregate : percentileAggregates) {
            transactionCount += percentileAggregate.transactionCount();
            totalMicros += percentileAggregate.totalMicros();
            histogram.merge(percentileAggregate.histogram());
        }

        List<PercentileValue> percentileValues = Lists.newArrayList();
        for (double percentile : percentiles) {
            percentileValues.add(ImmutablePercentileValue.of(
                    Utils.getPercentileWithSuffix(percentile) + " percentile",
                    histogram.getValueAtPercentile(percentile)));
        }
        return ImmutablePercentileMergedAggregate.builder()
                .totalMicros(totalMicros)
                .transactionCount(transactionCount)
                .addAllPercentileValues(percentileValues)
                .build();
    }

    public static ThreadInfoAggregate getThreadInfoAggregate(
            List<OverviewAggregate> overviewAggregates) {
        long totalCpuMicros = ThreadInfoData.NOT_AVAILABLE;
        long totalBlockedMicros = ThreadInfoData.NOT_AVAILABLE;
        long totalWaitedMicros = ThreadInfoData.NOT_AVAILABLE;
        long totalAllocatedKBytes = ThreadInfoData.NOT_AVAILABLE;
        for (OverviewAggregate overviewAggregate : overviewAggregates) {
            totalCpuMicros =
                    notAvailableAwareAdd(totalCpuMicros, overviewAggregate.totalCpuMicros());
            totalBlockedMicros = notAvailableAwareAdd(totalBlockedMicros,
                    overviewAggregate.totalBlockedMicros());
            totalWaitedMicros =
                    notAvailableAwareAdd(totalWaitedMicros, overviewAggregate.totalWaitedMicros());
            totalAllocatedKBytes = notAvailableAwareAdd(totalAllocatedKBytes,
                    overviewAggregate.totalAllocatedKBytes());
        }
        return ImmutableThreadInfoAggregate.builder()
                .totalCpuMicros(totalCpuMicros)
                .totalBlockedMicros(totalBlockedMicros)
                .totalWaitedMicros(totalWaitedMicros)
                .totalAllocatedKBytes(totalAllocatedKBytes)
                .build();
    }

    private static long notAvailableAwareAdd(long x, long y) {
        if (x == ThreadInfoData.NOT_AVAILABLE) {
            return y;
        }
        if (y == ThreadInfoData.NOT_AVAILABLE) {
            return x;
        }
        return x + y;
    }

    @Value.Immutable
    public interface TimerMergedAggregate {
        MutableTimerNode syntheticRootTimer();
        long transactionCount();
    }

    @Value.Immutable
    public interface PercentileMergedAggregate {
        long totalMicros();
        long transactionCount();
        ImmutableList<PercentileValue> percentileValues();
    }

    @Value.Immutable
    @Styles.AllParameters
    public interface PercentileValue {
        String dataSeriesName();
        long value();
    }

    @Value.Immutable
    public abstract static class ThreadInfoAggregate {

        abstract long totalCpuMicros(); // -1 means N/A
        abstract long totalBlockedMicros(); // -1 means N/A
        abstract long totalWaitedMicros(); // -1 means N/A
        abstract long totalAllocatedKBytes(); // -1 means N/A

        public boolean isEmpty() {
            return totalCpuMicros() == ThreadInfoData.NOT_AVAILABLE
                    && totalBlockedMicros() == ThreadInfoData.NOT_AVAILABLE
                    && totalWaitedMicros() == ThreadInfoData.NOT_AVAILABLE
                    && totalAllocatedKBytes() == ThreadInfoData.NOT_AVAILABLE;
        }
    }
}
