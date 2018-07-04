/*
 * Copyright 2013-2018 the original author or authors.
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
package org.glowroot.agent.impl;

import java.util.List;

import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.agent.config.AdvancedConfig;
import org.glowroot.agent.impl.Transaction.RootTimerCollector;
import org.glowroot.agent.impl.Transaction.ThreadStatsCollector;
import org.glowroot.agent.model.CommonTimerImpl;
import org.glowroot.agent.model.MutableAggregateTimer;
import org.glowroot.agent.model.QueryCollector;
import org.glowroot.agent.model.ServiceCallCollector;
import org.glowroot.agent.model.SharedQueryTextCollection;
import org.glowroot.agent.model.ThreadProfile;
import org.glowroot.agent.model.ThreadStats;
import org.glowroot.common.live.ImmutableOverviewAggregate;
import org.glowroot.common.live.ImmutablePercentileAggregate;
import org.glowroot.common.live.ImmutableThroughputAggregate;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.live.LiveAggregateRepository.ThroughputAggregate;
import org.glowroot.common.model.LazyHistogram;
import org.glowroot.common.model.LazyHistogram.ScratchBuffer;
import org.glowroot.common.model.MutableProfile;
import org.glowroot.common.model.OverallErrorSummaryCollector;
import org.glowroot.common.model.OverallSummaryCollector;
import org.glowroot.common.model.ProfileCollector;
import org.glowroot.common.model.TransactionNameErrorSummaryCollector;
import org.glowroot.common.model.TransactionNameSummaryCollector;
import org.glowroot.common.util.NotAvailableAware;
import org.glowroot.common.util.Styles;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;

import static com.google.common.base.Preconditions.checkNotNull;

// must be used under an appropriate lock
@Styles.Private
class AggregateCollector {

    private final @Nullable String transactionName;
    // aggregates use double instead of long to avoid (unlikely) 292 year nanosecond rollover
    private double totalDurationNanos;
    private long transactionCount;
    private long errorCount;
    private boolean asyncTransactions;
    private final RootTimerCollectorImpl mainThreadRootTimers = new RootTimerCollectorImpl();
    private final RootTimerCollectorImpl auxThreadRootTimers = new RootTimerCollectorImpl();
    private final RootTimerCollectorImpl asyncTimers = new RootTimerCollectorImpl();
    private final ThreadStatsCollectorImpl mainThreadStats = new ThreadStatsCollectorImpl();
    private final ThreadStatsCollectorImpl auxThreadStats = new ThreadStatsCollectorImpl();
    // histogram values are in nanoseconds, but with microsecond precision to reduce the number of
    // buckets (and memory) required
    private final LazyHistogram durationNanosHistogram = new LazyHistogram();
    // lazy instantiated to reduce memory footprint
    private @MonotonicNonNull QueryCollector queries;
    private @MonotonicNonNull ServiceCallCollector serviceCalls;
    private @MonotonicNonNull MutableProfile mainThreadProfile;
    private @MonotonicNonNull MutableProfile auxThreadProfile;

    private final int maxQueryAggregates;
    private final int maxServiceCallAggregates;

    AggregateCollector(@Nullable String transactionName, int maxQueryAggregates,
            int maxServiceCallAggregates) {
        this.transactionName = transactionName;
        this.maxQueryAggregates = maxQueryAggregates;
        this.maxServiceCallAggregates = maxServiceCallAggregates;
    }

    void add(Transaction transaction) {
        long totalDurationNanos = transaction.getDurationNanos();
        this.totalDurationNanos += totalDurationNanos;
        transactionCount++;
        if (transaction.getErrorMessage() != null) {
            errorCount++;
        }
        if (transaction.isAsync()) {
            asyncTransactions = true;
        }
        mainThreadStats.mergeThreadStats(transaction.getMainThreadStats());
        transaction.mergeAuxThreadStatsInto(auxThreadStats);
        durationNanosHistogram.add(totalDurationNanos);
    }

    RootTimerCollector getMainThreadRootTimers() {
        return mainThreadRootTimers;
    }

    RootTimerCollector getAuxThreadRootTimers() {
        return auxThreadRootTimers;
    }

    RootTimerCollector getAsyncTimers() {
        return asyncTimers;
    }

    void mergeMainThreadProfile(ThreadProfile toBeMergedProfile) {
        if (mainThreadProfile == null) {
            mainThreadProfile = new MutableProfile();
        }
        toBeMergedProfile.mergeInto(mainThreadProfile);
    }

    void mergeAuxThreadProfile(ThreadProfile toBeMergedProfile) {
        if (auxThreadProfile == null) {
            auxThreadProfile = new MutableProfile();
        }
        toBeMergedProfile.mergeInto(auxThreadProfile);
    }

    QueryCollector getQueryCollector() {
        if (queries == null) {
            int hardLimitMultiplierWhileBuilding = transactionName == null
                    ? AdvancedConfig.OVERALL_AGGREGATE_QUERIES_HARD_LIMIT_MULTIPLIER
                    : AdvancedConfig.TRANSACTION_AGGREGATE_QUERIES_HARD_LIMIT_MULTIPLIER;
            queries = new QueryCollector(maxQueryAggregates, hardLimitMultiplierWhileBuilding);
        }
        return queries;
    }

    ServiceCallCollector getServiceCallCollector() {
        if (serviceCalls == null) {
            int hardLimitMultiplierWhileBuilding = transactionName == null
                    ? AdvancedConfig.OVERALL_AGGREGATE_SERVICE_CALLS_HARD_LIMIT_MULTIPLIER
                    : AdvancedConfig.TRANSACTION_AGGREGATE_SERVICE_CALLS_HARD_LIMIT_MULTIPLIER;
            serviceCalls = new ServiceCallCollector(maxServiceCallAggregates,
                    hardLimitMultiplierWhileBuilding);
        }
        return serviceCalls;
    }

    Aggregate build(SharedQueryTextCollection sharedQueryTextCollection,
            ScratchBuffer scratchBuffer) {
        Aggregate.Builder builder = Aggregate.newBuilder()
                .setTotalDurationNanos(totalDurationNanos)
                .setTransactionCount(transactionCount)
                .setErrorCount(errorCount)
                .setAsyncTransactions(asyncTransactions)
                .addAllMainThreadRootTimer(mainThreadRootTimers.toProto())
                .addAllAuxThreadRootTimer(auxThreadRootTimers.toProto())
                .addAllAsyncTimer(asyncTimers.toProto())
                .setDurationNanosHistogram(durationNanosHistogram.toProto(scratchBuffer))
                .setMainThreadStats(mainThreadStats.toProto())
                .setAuxThreadStats(auxThreadStats.toProto());
        if (queries != null) {
            builder.addAllQuery(queries.toAggregateProto(sharedQueryTextCollection, false));
        }
        if (serviceCalls != null) {
            builder.addAllServiceCall(serviceCalls.toAggregateProto());
        }
        if (mainThreadProfile != null) {
            builder.setMainThreadProfile(mainThreadProfile.toProto());
        }
        if (auxThreadProfile != null) {
            builder.setAuxThreadProfile(auxThreadProfile.toProto());
        }
        return builder.build();
    }

    void mergeOverallSummaryInto(OverallSummaryCollector collector) {
        collector.mergeSummary(totalDurationNanos, transactionCount, 0);
    }

    void mergeTransactionNameSummariesInto(TransactionNameSummaryCollector collector) {
        checkNotNull(transactionName);
        collector.collect(transactionName, totalDurationNanos, transactionCount, 0);
    }

    void mergeOverallErrorSummaryInto(OverallErrorSummaryCollector collector) {
        collector.mergeErrorSummary(errorCount, transactionCount, 0);
    }

    void mergeTransactionNameErrorSummariesInto(TransactionNameErrorSummaryCollector collector) {
        checkNotNull(transactionName);
        if (errorCount != 0) {
            collector.collect(transactionName, errorCount, transactionCount, 0);
        }
    }

    OverviewAggregate getOverviewAggregate(long captureTime) {
        return ImmutableOverviewAggregate.builder()
                .captureTime(captureTime)
                .totalDurationNanos(totalDurationNanos)
                .transactionCount(transactionCount)
                .asyncTransactions(asyncTransactions)
                .mainThreadRootTimers(mainThreadRootTimers.toProto())
                .auxThreadRootTimers(auxThreadRootTimers.toProto())
                .asyncTimers(asyncTimers.toProto())
                .mainThreadStats(mainThreadStats.toProto())
                .auxThreadStats(auxThreadStats.toProto())
                .build();
    }

    PercentileAggregate getPercentileAggregate(long captureTime) {
        return ImmutablePercentileAggregate.builder()
                .captureTime(captureTime)
                .totalDurationNanos(totalDurationNanos)
                .transactionCount(transactionCount)
                .durationNanosHistogram(durationNanosHistogram.toProto(new ScratchBuffer()))
                .build();
    }

    ThroughputAggregate getThroughputAggregate(long captureTime) {
        return ImmutableThroughputAggregate.builder()
                .captureTime(captureTime)
                .transactionCount(transactionCount)
                .errorCount(errorCount)
                .build();
    }

    @Nullable
    String getFullQueryText(String fullQueryTextSha1) {
        if (queries == null) {
            return null;
        }
        return queries.getFullQueryText(fullQueryTextSha1);
    }

    void mergeQueriesInto(org.glowroot.common.model.QueryCollector collector) {
        if (queries != null) {
            queries.mergeQueriesInto(collector);
        }
    }

    void mergeServiceCallsInto(org.glowroot.common.model.ServiceCallCollector collector) {
        if (serviceCalls != null) {
            serviceCalls.mergeServiceCallsInto(collector);
        }
    }

    void mergeMainThreadProfilesInto(ProfileCollector collector) {
        if (mainThreadProfile != null) {
            collector.mergeProfile(mainThreadProfile.toProto());
        }
    }

    void mergeAuxThreadProfilesInto(ProfileCollector collector) {
        if (auxThreadProfile != null) {
            collector.mergeProfile(auxThreadProfile.toProto());
        }
    }

    private static class RootTimerCollectorImpl implements RootTimerCollector {

        List<MutableAggregateTimer> rootMutableTimers = Lists.newArrayList();

        @Override
        public void mergeRootTimer(CommonTimerImpl rootTimer) {
            mergeRootTimer(rootTimer, rootMutableTimers);
        }

        private List<Aggregate.Timer> toProto() {
            List<Aggregate.Timer> rootTimers = Lists.newArrayList();
            for (MutableAggregateTimer rootMutableTimer : rootMutableTimers) {
                rootTimers.add(rootMutableTimer.toProto());
            }
            return rootTimers;
        }

        private static void mergeRootTimer(CommonTimerImpl toBeMergedRootTimer,
                List<MutableAggregateTimer> rootTimers) {
            for (MutableAggregateTimer rootTimer : rootTimers) {
                if (toBeMergedRootTimer.getName().equals(rootTimer.getName())
                        && toBeMergedRootTimer.isExtended() == rootTimer.isExtended()) {
                    rootTimer.merge(toBeMergedRootTimer);
                    return;
                }
            }
            MutableAggregateTimer rootTimer = MutableAggregateTimer.createRootTimer(
                    toBeMergedRootTimer.getName(), toBeMergedRootTimer.isExtended());
            rootTimer.merge(toBeMergedRootTimer);
            rootTimers.add(rootTimer);
        }
    }

    private static class ThreadStatsCollectorImpl implements ThreadStatsCollector {

        // aggregates use double instead of long to avoid (unlikely) 292 year nanosecond rollover
        private double totalCpuNanos;
        private long totalBlockedMillis;
        private long totalWaitedMillis;
        private double totalAllocatedBytes;

        @Override
        public void mergeThreadStats(ThreadStats threadStats) {
            totalCpuNanos = NotAvailableAware.add(totalCpuNanos, threadStats.getCpuNanos());
            totalBlockedMillis =
                    NotAvailableAware.add(totalBlockedMillis, threadStats.getBlockedMillis());
            totalWaitedMillis =
                    NotAvailableAware.add(totalWaitedMillis, threadStats.getWaitedMillis());
            totalAllocatedBytes = NotAvailableAware.add(totalAllocatedBytes,
                    threadStats.getAllocatedBytes());
        }

        public Aggregate.ThreadStats toProto() {
            return Aggregate.ThreadStats.newBuilder()
                    .setTotalCpuNanos(totalCpuNanos)
                    .setTotalBlockedNanos(NotAvailableAware.millisToNanos(totalBlockedMillis))
                    .setTotalWaitedNanos(NotAvailableAware.millisToNanos(totalWaitedMillis))
                    .setTotalAllocatedBytes(totalAllocatedBytes)
                    .build();
        }
    }
}
