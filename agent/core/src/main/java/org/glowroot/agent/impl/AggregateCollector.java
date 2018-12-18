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

import org.glowroot.agent.impl.Transaction.RootTimerCollector;
import org.glowroot.agent.impl.Transaction.ThreadStatsCollector;
import org.glowroot.agent.model.MutableAggregateTimer;
import org.glowroot.agent.model.QueryCollector;
import org.glowroot.agent.model.ServiceCallCollector;
import org.glowroot.agent.model.SharedQueryTextCollection;
import org.glowroot.agent.model.ThreadProfile;
import org.glowroot.agent.model.ThreadStats;
import org.glowroot.agent.model.TransactionTimer;
import org.glowroot.common.config.AdvancedConfig;
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

@Styles.Private
class AggregateCollector {

    private final @Nullable String transactionName;
    // aggregates use double instead of long to avoid (unlikely) 292 year nanosecond rollover
    private double totalDurationNanos;
    private long transactionCount;
    private long errorCount;
    private boolean asyncTransactions;
    private final RootTimerCollectorImpl mainThreadRootTimers = new RootTimerCollectorImpl();
    private final ThreadStatsCollectorImpl mainThreadStats = new ThreadStatsCollectorImpl();
    // histogram values are in nanoseconds, but with microsecond precision to reduce the number of
    // buckets (and memory) required
    private final LazyHistogram durationNanosHistogram = new LazyHistogram();
    private final QueryCollector queries;
    private final ServiceCallCollector serviceCalls;
    // lazy instantiated to reduce memory footprint
    private @MonotonicNonNull MutableAggregateTimer auxThreadRootTimer;
    private @MonotonicNonNull ThreadStatsCollectorImpl auxThreadStats;
    private @MonotonicNonNull RootTimerCollectorImpl asyncTimers;
    private @MonotonicNonNull MutableProfile mainThreadProfile;
    private @MonotonicNonNull MutableProfile auxThreadProfile;

    // lock is primarily for visibility (there is almost no contention since written via a single
    // thread and flushed afterwards via a different thread, with potential concurrent access by the
    // UI for "live" data when running the embedded collector)
    private final Object lock = new Object();

    AggregateCollector(@Nullable String transactionName, int maxQueryAggregates,
            int maxServiceCallAggregates) {
        this.transactionName = transactionName;

        int queriesHardLimitMultiplierWhileBuilding = transactionName == null
                ? AdvancedConfig.OVERALL_AGGREGATE_QUERIES_HARD_LIMIT_MULTIPLIER
                : AdvancedConfig.TRANSACTION_AGGREGATE_QUERIES_HARD_LIMIT_MULTIPLIER;
        queries = new QueryCollector(maxQueryAggregates, queriesHardLimitMultiplierWhileBuilding);

        int serviceCallsHardLimitMultiplierWhileBuilding = transactionName == null
                ? AdvancedConfig.OVERALL_AGGREGATE_SERVICE_CALLS_HARD_LIMIT_MULTIPLIER
                : AdvancedConfig.TRANSACTION_AGGREGATE_SERVICE_CALLS_HARD_LIMIT_MULTIPLIER;
        serviceCalls = new ServiceCallCollector(maxServiceCallAggregates,
                serviceCallsHardLimitMultiplierWhileBuilding);
    }

    void mergeDataFrom(Transaction transaction) {
        synchronized (lock) {
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
            mainThreadRootTimers.mergeRootTimer(transaction.getMainThreadRootTimer());
            if (transaction.hasAuxThreadContexts()) {
                if (auxThreadRootTimer == null) {
                    auxThreadRootTimer = MutableAggregateTimer.createAuxThreadRootTimer();
                }
                transaction.mergeAuxThreadTimersInto(auxThreadRootTimer);
                if (auxThreadStats == null) {
                    auxThreadStats = new ThreadStatsCollectorImpl();
                }
                transaction.mergeAuxThreadStatsInto(auxThreadStats);
            }
            if (transaction.hasAsyncTimers()) {
                if (asyncTimers == null) {
                    asyncTimers = new RootTimerCollectorImpl();
                }
                transaction.mergeAsyncTimersInto(asyncTimers);
            }
            durationNanosHistogram.add(totalDurationNanos);
            transaction.mergeQueriesInto(queries);
            transaction.mergeServiceCallsInto(serviceCalls);
            ThreadProfile toBeMergedMainThreadProfile = transaction.getMainThreadProfile();
            if (toBeMergedMainThreadProfile != null) {
                if (mainThreadProfile == null) {
                    mainThreadProfile = new MutableProfile();
                }
                toBeMergedMainThreadProfile.mergeInto(mainThreadProfile);
            }
            ThreadProfile toBeMergedAuxThreadProfile = transaction.getAuxThreadProfile();
            if (toBeMergedAuxThreadProfile != null) {
                if (auxThreadProfile == null) {
                    auxThreadProfile = new MutableProfile();
                }
                toBeMergedAuxThreadProfile.mergeInto(auxThreadProfile);
            }
        }
    }

    Aggregate build(SharedQueryTextCollection sharedQueryTextCollection,
            ScratchBuffer scratchBuffer) {
        synchronized (lock) {
            Aggregate.Builder builder = Aggregate.newBuilder()
                    .setTotalDurationNanos(totalDurationNanos)
                    .setTransactionCount(transactionCount)
                    .setErrorCount(errorCount)
                    .setAsyncTransactions(asyncTransactions)
                    .addAllMainThreadRootTimer(mainThreadRootTimers.toProto())
                    .setMainThreadStats(mainThreadStats.toProto())
                    .setDurationNanosHistogram(durationNanosHistogram.toProto(scratchBuffer));
            if (auxThreadRootTimer != null) {
                builder.setAuxThreadRootTimer(auxThreadRootTimer.toProto());
                // aux thread stats is non-null when aux thread root timer is non-null
                builder.setAuxThreadStats(checkNotNull(auxThreadStats).toProto());
            }
            if (asyncTimers != null) {
                builder.addAllAsyncTimer(asyncTimers.toProto());
            }
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
    }

    void mergeOverallSummaryInto(OverallSummaryCollector collector) {
        synchronized (lock) {
            collector.mergeSummary(totalDurationNanos, transactionCount, 0);
        }
    }

    void mergeTransactionNameSummariesInto(TransactionNameSummaryCollector collector) {
        checkNotNull(transactionName);
        synchronized (lock) {
            collector.collect(transactionName, totalDurationNanos, transactionCount, 0);
        }
    }

    void mergeOverallErrorSummaryInto(OverallErrorSummaryCollector collector) {
        synchronized (lock) {
            collector.mergeErrorSummary(errorCount, transactionCount, 0);
        }
    }

    void mergeTransactionNameErrorSummariesInto(TransactionNameErrorSummaryCollector collector) {
        checkNotNull(transactionName);
        synchronized (lock) {
            if (errorCount != 0) {
                collector.collect(transactionName, errorCount, transactionCount, 0);
            }
        }
    }

    OverviewAggregate getOverviewAggregate(long captureTime) {
        synchronized (lock) {
            ImmutableOverviewAggregate.Builder builder = ImmutableOverviewAggregate.builder()
                    .captureTime(captureTime)
                    .totalDurationNanos(totalDurationNanos)
                    .transactionCount(transactionCount)
                    .asyncTransactions(asyncTransactions)
                    .mainThreadRootTimers(mainThreadRootTimers.toProto())
                    .mainThreadStats(mainThreadStats.toProto());
            if (auxThreadRootTimer != null) {
                builder.auxThreadRootTimer(auxThreadRootTimer.toProto());
                // aux thread stats is non-null when aux thread root timer is non-null
                builder.auxThreadStats(checkNotNull(auxThreadStats).toProto());
            }
            if (asyncTimers != null) {
                builder.asyncTimers(asyncTimers.toProto());
            }
            return builder.build();
        }
    }

    PercentileAggregate getPercentileAggregate(long captureTime) {
        synchronized (lock) {
            return ImmutablePercentileAggregate.builder()
                    .captureTime(captureTime)
                    .totalDurationNanos(totalDurationNanos)
                    .transactionCount(transactionCount)
                    .durationNanosHistogram(durationNanosHistogram.toProto(new ScratchBuffer()))
                    .build();
        }
    }

    ThroughputAggregate getThroughputAggregate(long captureTime) {
        synchronized (lock) {
            return ImmutableThroughputAggregate.builder()
                    .captureTime(captureTime)
                    .transactionCount(transactionCount)
                    .errorCount(errorCount)
                    .build();
        }
    }

    @Nullable
    String getFullQueryText(String fullQueryTextSha1) {
        if (queries == null) {
            return null;
        }
        synchronized (lock) {
            return queries.getFullQueryText(fullQueryTextSha1);
        }
    }

    void mergeQueriesInto(org.glowroot.common.model.QueryCollector collector) {
        if (queries != null) {
            synchronized (lock) {
                queries.mergeQueriesInto(collector);
            }
        }
    }

    void mergeServiceCallsInto(org.glowroot.common.model.ServiceCallCollector collector) {
        if (serviceCalls != null) {
            synchronized (lock) {
                serviceCalls.mergeServiceCallsInto(collector);
            }
        }
    }

    void mergeMainThreadProfilesInto(ProfileCollector collector) {
        synchronized (lock) {
            if (mainThreadProfile != null) {
                collector.mergeProfile(mainThreadProfile.toProto());
            }
        }
    }

    void mergeAuxThreadProfilesInto(ProfileCollector collector) {
        synchronized (lock) {
            if (auxThreadProfile != null) {
                collector.mergeProfile(auxThreadProfile.toProto());
            }
        }
    }

    private static class RootTimerCollectorImpl implements RootTimerCollector {

        List<MutableAggregateTimer> rootMutableTimers = Lists.newArrayList();

        @Override
        public void mergeRootTimer(TransactionTimer toBeMergedRootTimer) {
            for (MutableAggregateTimer rootTimer : rootMutableTimers) {
                if (toBeMergedRootTimer.getName().equals(rootTimer.getName())
                        && toBeMergedRootTimer.isExtended() == rootTimer.isExtended()) {
                    rootTimer.addDataFrom(toBeMergedRootTimer);
                    return;
                }
            }
            MutableAggregateTimer rootTimer = new MutableAggregateTimer(
                    toBeMergedRootTimer.getName(), toBeMergedRootTimer.isExtended());
            rootTimer.addDataFrom(toBeMergedRootTimer);
            rootMutableTimers.add(rootTimer);
        }

        private List<Aggregate.Timer> toProto() {
            List<Aggregate.Timer> rootTimers = Lists.newArrayList();
            for (MutableAggregateTimer rootMutableTimer : rootMutableTimers) {
                rootTimers.add(rootMutableTimer.toProto());
            }
            return rootTimers;
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
