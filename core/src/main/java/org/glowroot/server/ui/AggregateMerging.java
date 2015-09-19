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

import org.glowroot.collector.spi.model.AggregateOuterClass.Aggregate;
import org.glowroot.collector.spi.model.AggregateOuterClass.Aggregate.Timer;
import org.glowroot.common.model.LazyHistogram;
import org.glowroot.common.util.NotAvailableAware;
import org.glowroot.common.util.Styles;
import org.glowroot.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.server.repo.MutableTimer;
import org.glowroot.server.repo.Utils;

class AggregateMerging {

    private AggregateMerging() {}

    static TimerMergedAggregate getTimerMergedAggregate(List<OverviewAggregate> overviewAggregates)
            throws Exception {
        long transactionCount = 0;
        List<MutableTimer> rootTimers = Lists.newArrayList();
        for (OverviewAggregate aggregate : overviewAggregates) {
            transactionCount += aggregate.transactionCount();
            mergeRootTimers(aggregate.rootTimers(), rootTimers);
        }
        ImmutableTimerMergedAggregate.Builder timerMergedAggregate =
                ImmutableTimerMergedAggregate.builder();
        timerMergedAggregate.rootTimers(rootTimers);
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
        double totalCpuNanos = NotAvailableAware.NA;
        double totalBlockedNanos = NotAvailableAware.NA;
        double totalWaitedNanos = NotAvailableAware.NA;
        double totalAllocatedBytes = NotAvailableAware.NA;
        for (OverviewAggregate overviewAggregate : overviewAggregates) {
            totalCpuNanos = NotAvailableAware.add(totalCpuNanos, overviewAggregate.totalCpuNanos());
            totalBlockedNanos =
                    NotAvailableAware.add(totalBlockedNanos, overviewAggregate.totalBlockedNanos());
            totalWaitedNanos =
                    NotAvailableAware.add(totalWaitedNanos, overviewAggregate.totalWaitedNanos());
            totalAllocatedBytes = NotAvailableAware.add(totalAllocatedBytes,
                    overviewAggregate.totalAllocatedBytes());
        }
        return ImmutableThreadInfoAggregate.builder()
                .totalCpuNanos(totalCpuNanos)
                .totalBlockedNanos(totalBlockedNanos)
                .totalWaitedNanos(totalWaitedNanos)
                .totalAllocatedBytes(totalAllocatedBytes)
                .build();
    }

    private static void mergeRootTimers(List<Aggregate.Timer> toBeMergedRootTimers,
            List<MutableTimer> rootTimers) {
        for (Aggregate.Timer toBeMergedRootTimer : toBeMergedRootTimers) {
            mergeRootTimer(toBeMergedRootTimer, rootTimers);
        }
    }

    private static void mergeRootTimer(Timer toBeMergedRootTimer, List<MutableTimer> rootTimers) {
        for (MutableTimer rootTimer : rootTimers) {
            if (toBeMergedRootTimer.getName().equals(rootTimer.getName())) {
                rootTimer.merge(toBeMergedRootTimer);
                return;
            }
        }
        MutableTimer rootTimer = MutableTimer.createRootTimer(toBeMergedRootTimer.getName(),
                toBeMergedRootTimer.getExtended());
        rootTimer.merge(toBeMergedRootTimer);
        rootTimers.add(rootTimer);
    }

    @Value.Immutable
    interface TimerMergedAggregate {
        long transactionCount();
        List<MutableTimer> rootTimers();
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
            return NotAvailableAware.isNA(totalCpuNanos())
                    && NotAvailableAware.isNA(totalBlockedNanos())
                    && NotAvailableAware.isNA(totalWaitedNanos())
                    && NotAvailableAware.isNA(totalAllocatedBytes());
        }
    }
}
