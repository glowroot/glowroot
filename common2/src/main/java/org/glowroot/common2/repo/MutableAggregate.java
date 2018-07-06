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
package org.glowroot.common2.repo;

import java.util.List;

import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.common.live.ImmutableOverviewAggregate;
import org.glowroot.common.live.ImmutablePercentileAggregate;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.model.LazyHistogram;
import org.glowroot.common.model.LazyHistogram.ScratchBuffer;
import org.glowroot.common.model.MutableProfile;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.common.model.ServiceCallCollector;
import org.glowroot.common.util.Styles;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;

import static com.google.common.base.Preconditions.checkNotNull;

@Styles.Private
public class MutableAggregate {

    private double totalDurationNanos;
    private long transactionCount;
    private long errorCount;
    private boolean asyncTransactions;
    private final List<MutableTimer> mainThreadRootTimers = Lists.newArrayList();
    private final MutableThreadStats mainThreadStats = new MutableThreadStats();
    private final List<MutableTimer> asyncTimers = Lists.newArrayList();
    // histogram values are in nanoseconds, but with microsecond precision to reduce the number of
    // buckets (and memory) required
    private final LazyHistogram durationNanosHistogram = new LazyHistogram();
    // lazy instantiated to reduce memory footprint
    private @MonotonicNonNull MutableTimer auxThreadRootTimer;
    private @MonotonicNonNull MutableThreadStats auxThreadStats;
    private @MonotonicNonNull QueryCollector queries;
    private @MonotonicNonNull ServiceCallCollector serviceCalls;
    private @MonotonicNonNull MutableProfile mainThreadProfile;
    private @MonotonicNonNull MutableProfile auxThreadProfile;

    private final int maxQueryAggregates;
    private final int maxServiceCallAggregates;

    public MutableAggregate(int maxQueryAggregates, int maxServiceCallAggregates) {
        this.maxQueryAggregates = maxQueryAggregates;
        this.maxServiceCallAggregates = maxServiceCallAggregates;
    }

    public double getTotalDurationNanos() {
        return totalDurationNanos;
    }

    public long getTransactionCount() {
        return transactionCount;
    }

    public long getErrorCount() {
        return errorCount;
    }

    public boolean isAsyncTransactions() {
        return asyncTransactions;
    }

    public List<Aggregate.Timer> getMainThreadRootTimersProto() {
        return toProto(mainThreadRootTimers);
    }

    public MutableThreadStats getMainThreadStats() {
        return mainThreadStats;
    }

    public Aggregate. /*@Nullable*/ Timer getAuxThreadRootTimerProto() {
        if (auxThreadRootTimer == null) {
            return null;
        } else {
            return auxThreadRootTimer.toProto();
        }
    }

    public @Nullable MutableThreadStats getAuxThreadStats() {
        return auxThreadStats;
    }

    public List<Aggregate.Timer> getAsyncTimersProto() {
        return toProto(asyncTimers);
    }

    public LazyHistogram getDurationNanosHistogram() {
        return durationNanosHistogram;
    }

    public @Nullable QueryCollector getQueries() {
        return queries;
    }

    public @Nullable ServiceCallCollector getServiceCalls() {
        return serviceCalls;
    }

    public @Nullable MutableProfile getMainThreadProfile() {
        return mainThreadProfile;
    }

    public @Nullable MutableProfile getAuxThreadProfile() {
        return auxThreadProfile;
    }

    public boolean isEmpty() {
        return transactionCount == 0;
    }

    public void addTotalDurationNanos(double totalDurationNanos) {
        this.totalDurationNanos += totalDurationNanos;
    }

    public void addTransactionCount(long transactionCount) {
        this.transactionCount += transactionCount;
    }

    public void addErrorCount(long errorCount) {
        this.errorCount += errorCount;
    }

    public void addAsyncTransactions(boolean asyncTransactions) {
        if (asyncTransactions) {
            this.asyncTransactions = true;
        }
    }

    public void mergeMainThreadRootTimers(List<Aggregate.Timer> toBeMergedMainThreadRootTimers) {
        mergeRootTimers(toBeMergedMainThreadRootTimers, mainThreadRootTimers);
    }

    public void mergeMainThreadStats(Aggregate.ThreadStats threadStats) {
        mainThreadStats.addThreadStats(threadStats);
    }

    public void addMainThreadTotalCpuNanos(double totalCpuNanos) {
        mainThreadStats.addTotalCpuNanos(totalCpuNanos);
    }

    public void addMainThreadTotalBlockedNanos(double totalBlockedNanos) {
        mainThreadStats.addTotalBlockedNanos(totalBlockedNanos);
    }

    public void addMainThreadTotalWaitedNanos(double totalWaitedNanos) {
        mainThreadStats.addTotalWaitedNanos(totalWaitedNanos);
    }

    public void addMainThreadTotalAllocatedBytes(double totalAllocatedBytes) {
        mainThreadStats.addTotalAllocatedBytes(totalAllocatedBytes);
    }

    public void mergeAuxThreadRootTimer(Aggregate.Timer toBeMergedAuxThreadRootTimer) {
        if (auxThreadRootTimer == null) {
            auxThreadRootTimer = MutableTimer.createAuxThreadRootTimer();
        }
        auxThreadRootTimer.merge(toBeMergedAuxThreadRootTimer);
    }

    public void mergeAuxThreadStats(Aggregate.ThreadStats threadStats) {
        if (auxThreadStats == null) {
            auxThreadStats = new MutableThreadStats();
        }
        auxThreadStats.addThreadStats(threadStats);
    }

    public void addAuxThreadTotalCpuNanos(double totalCpuNanos) {
        if (auxThreadStats == null) {
            auxThreadStats = new MutableThreadStats();
        }
        auxThreadStats.addTotalCpuNanos(totalCpuNanos);
    }

    public void addAuxThreadTotalBlockedNanos(double totalBlockedNanos) {
        if (auxThreadStats == null) {
            auxThreadStats = new MutableThreadStats();
        }
        auxThreadStats.addTotalBlockedNanos(totalBlockedNanos);
    }

    public void addAuxThreadTotalWaitedNanos(double totalWaitedNanos) {
        if (auxThreadStats == null) {
            auxThreadStats = new MutableThreadStats();
        }
        auxThreadStats.addTotalWaitedNanos(totalWaitedNanos);
    }

    public void addAuxThreadTotalAllocatedBytes(double totalAllocatedBytes) {
        if (auxThreadStats == null) {
            auxThreadStats = new MutableThreadStats();
        }
        auxThreadStats.addTotalAllocatedBytes(totalAllocatedBytes);
    }

    public void mergeAsyncTimers(List<Aggregate.Timer> toBeMergedAsyncTimers) {
        mergeRootTimers(toBeMergedAsyncTimers, asyncTimers);
    }

    public void mergeDurationNanosHistogram(Aggregate.Histogram toBeMergedDurationNanosHistogram) {
        durationNanosHistogram.merge(toBeMergedDurationNanosHistogram);
    }

    public OverviewAggregate toOverviewAggregate(long captureTime) {
        ImmutableOverviewAggregate.Builder builder = ImmutableOverviewAggregate.builder()
                .captureTime(captureTime)
                .totalDurationNanos(totalDurationNanos)
                .transactionCount(transactionCount)
                .asyncTransactions(asyncTransactions)
                .mainThreadRootTimers(toProto(mainThreadRootTimers))
                .mainThreadStats(mainThreadStats.toProto())
                .asyncTimers(toProto(asyncTimers));
        if (auxThreadRootTimer != null) {
            builder.auxThreadRootTimer(auxThreadRootTimer.toProto());
            // aux thread stats is non-null when aux thread root timer is non-null
            builder.auxThreadStats(checkNotNull(auxThreadStats).toProto());
        }
        return builder.build();
    }

    public PercentileAggregate toPercentileAggregate(long captureTime) {
        return ImmutablePercentileAggregate.builder()
                .captureTime(captureTime)
                .totalDurationNanos(totalDurationNanos)
                .transactionCount(transactionCount)
                .durationNanosHistogram(durationNanosHistogram.toProto(new ScratchBuffer()))
                .build();
    }

    public void mergeQuery(String queryType, String truncatedQueryText,
            @Nullable String fullQueryTextSha1, double totalDurationNanos, long executionCount,
            boolean hasTotalRows, long totalRows) {
        if (queries == null) {
            queries = new QueryCollector(maxQueryAggregates);
        }
        queries.mergeQuery(queryType, truncatedQueryText, fullQueryTextSha1, totalDurationNanos,
                executionCount, hasTotalRows, totalRows);
    }

    public void mergeServiceCall(String serviceCallType, String serviceCallText,
            double totalDurationNanos, long executionCount) {
        if (serviceCalls == null) {
            serviceCalls = new ServiceCallCollector(maxServiceCallAggregates);
        }
        serviceCalls.mergeServiceCall(serviceCallType, serviceCallText, totalDurationNanos,
                executionCount);
    }

    public void mergeMainThreadProfile(Profile toBeMergedProfile) {
        if (mainThreadProfile == null) {
            mainThreadProfile = new MutableProfile();
        }
        mainThreadProfile.merge(toBeMergedProfile);
    }

    public void mergeAuxThreadProfile(Profile toBeMergedProfile) {
        if (auxThreadProfile == null) {
            auxThreadProfile = new MutableProfile();
        }
        auxThreadProfile.merge(toBeMergedProfile);
    }

    public static void mergeRootTimers(List<Aggregate.Timer> toBeMergedRootTimers,
            List<MutableTimer> rootTimers) {
        for (Aggregate.Timer toBeMergedRootTimer : toBeMergedRootTimers) {
            mergeRootTimer(toBeMergedRootTimer, rootTimers);
        }
    }

    public static List<Aggregate.Timer> toProto(List<MutableTimer> rootTimers) {
        List<Aggregate.Timer> protobufRootTimers =
                Lists.newArrayListWithCapacity(rootTimers.size());
        for (MutableTimer rootTimer : rootTimers) {
            protobufRootTimers.add(rootTimer.toProto());
        }
        return protobufRootTimers;
    }

    private static void mergeRootTimer(Aggregate.Timer toBeMergedRootTimer,
            List<MutableTimer> rootTimers) {
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
}
