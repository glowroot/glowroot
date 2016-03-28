/*
 * Copyright 2013-2016 the original author or authors.
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

import java.io.IOException;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import org.glowroot.agent.config.AdvancedConfig;
import org.glowroot.agent.model.CommonTimerImpl;
import org.glowroot.agent.model.Profile;
import org.glowroot.agent.model.ThreadContextImpl;
import org.glowroot.agent.model.ThreadStats;
import org.glowroot.agent.model.TimerImpl;
import org.glowroot.agent.model.Transaction;
import org.glowroot.common.model.LazyHistogram;
import org.glowroot.common.model.LazyHistogram.ScratchBuffer;
import org.glowroot.common.model.MutableProfile;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.common.model.ServiceCallCollector;
import org.glowroot.common.util.NotAvailableAware;
import org.glowroot.common.util.Styles;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.Proto.OptionalDouble;

// must be used under an appropriate lock
@Styles.Private
class AggregateCollector {

    private final @Nullable String transactionName;
    private long totalDurationNanos;
    private long transactionCount;
    private long errorCount;
    private boolean asyncTransactions;
    private final List<MutableTimer> mainThreadRootTimers = Lists.newArrayList();
    private final List<MutableTimer> auxThreadRootTimers = Lists.newArrayList();
    private final List<MutableTimer> asyncRootTimers = Lists.newArrayList();
    private final MutableThreadStats mainThreadStats = new MutableThreadStats();
    private final MutableThreadStats auxThreadStats = new MutableThreadStats();
    // histogram values are in nanoseconds, but with microsecond precision to reduce the number of
    // buckets (and memory) required
    private final LazyHistogram durationNanosHistogram = new LazyHistogram();
    // lazy instantiated to reduce memory footprint
    private @MonotonicNonNull QueryCollector queries;
    private @MonotonicNonNull ServiceCallCollector serviceCalls;
    private @MonotonicNonNull MutableProfile mainThreadProfile;
    private @MonotonicNonNull MutableProfile auxThreadProfile;

    private final int maxAggregateQueriesPerType;
    private final int maxAggregateServiceCallsPerType;

    AggregateCollector(@Nullable String transactionName, int maxAggregateQueriesPerType,
            int maxAggregateServiceCallsPerType) {
        this.transactionName = transactionName;
        this.maxAggregateQueriesPerType = maxAggregateQueriesPerType;
        this.maxAggregateServiceCallsPerType = maxAggregateServiceCallsPerType;
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
        this.mainThreadStats.addThreadStats(transaction.getMainThreadStats());
        for (ThreadContextImpl auxThreadContext : transaction.getAuxThreadContexts()) {
            this.auxThreadStats.addThreadStats(auxThreadContext.getThreadStats());
        }
        durationNanosHistogram.add(totalDurationNanos);
    }

    void mergeMainThreadRootTimer(TimerImpl toBeMergedRootTimer) {
        mergeRootTimer(toBeMergedRootTimer, mainThreadRootTimers);
    }

    void mergeAuxThreadRootTimer(TimerImpl toBeMergedRootTimer) {
        mergeRootTimer(toBeMergedRootTimer, auxThreadRootTimers);
    }

    void mergeAsyncRootTimer(CommonTimerImpl toBeMergedRootTimer) {
        mergeRootTimer(toBeMergedRootTimer, asyncRootTimers);
    }

    void mergeMainThreadProfile(Profile toBeMergedProfile) {
        if (mainThreadProfile == null) {
            mainThreadProfile = new MutableProfile();
        }
        toBeMergedProfile.mergeIntoProfile(mainThreadProfile);
    }

    void mergeAuxThreadProfile(Profile toBeMergedProfile) {
        if (auxThreadProfile == null) {
            auxThreadProfile = new MutableProfile();
        }
        toBeMergedProfile.mergeIntoProfile(auxThreadProfile);
    }

    QueryCollector getQueryCollector() {
        if (queries == null) {
            int queriesHardLimitMultiplierWhileBuilding = transactionName == null
                    ? AdvancedConfig.OVERALL_AGGREGATE_QUERIES_HARD_LIMIT_MULTIPLIER
                    : AdvancedConfig.TRANSACTION_AGGREGATE_QUERIES_HARD_LIMIT_MULTIPLIER;
            queries = new QueryCollector(maxAggregateQueriesPerType,
                    queriesHardLimitMultiplierWhileBuilding);
        }
        return queries;
    }

    ServiceCallCollector getServiceCallCollector() {
        if (serviceCalls == null) {
            int serviceCallsHardLimitMultiplierWhileBuilding = transactionName == null
                    ? AdvancedConfig.OVERALL_AGGREGATE_SERVICE_CALLS_HARD_LIMIT_MULTIPLIER
                    : AdvancedConfig.TRANSACTION_AGGREGATE_SERVICE_CALLS_HARD_LIMIT_MULTIPLIER;
            serviceCalls = new ServiceCallCollector(maxAggregateServiceCallsPerType,
                    serviceCallsHardLimitMultiplierWhileBuilding);
        }
        return serviceCalls;
    }

    Aggregate build(ScratchBuffer scratchBuffer) throws IOException {
        Aggregate.Builder builder = Aggregate.newBuilder()
                .setTotalDurationNanos(totalDurationNanos)
                .setTransactionCount(transactionCount)
                .setErrorCount(errorCount)
                .setAsyncTransactions(asyncTransactions)
                .addAllMainThreadRootTimer(getRootTimersProtobuf(mainThreadRootTimers))
                .addAllAuxThreadRootTimer(getRootTimersProtobuf(auxThreadRootTimers))
                .addAllAsyncRootTimer(getRootTimersProtobuf(asyncRootTimers))
                .setDurationNanosHistogram(durationNanosHistogram.toProto(scratchBuffer));
        if (!mainThreadStats.isNA()) {
            builder.setMainThreadStats(mainThreadStats.toProto());
        }
        if (!auxThreadStats.isNA()) {
            builder.setAuxThreadStats(auxThreadStats.toProto());
        }
        if (queries != null) {
            builder.addAllQueriesByType(queries.toProto());
        }
        if (serviceCalls != null) {
            builder.addAllServiceCallsByType(serviceCalls.toProto());
        }
        if (mainThreadProfile != null) {
            builder.setMainThreadProfile(mainThreadProfile.toProto());
        }
        if (auxThreadProfile != null) {
            builder.setAuxThreadProfile(auxThreadProfile.toProto());
        }
        return builder.build();
    }

    private static void mergeRootTimer(CommonTimerImpl toBeMergedRootTimer,
            List<MutableTimer> rootTimers) {
        for (MutableTimer rootTimer : rootTimers) {
            if (toBeMergedRootTimer.getName().equals(rootTimer.getName())) {
                rootTimer.merge(toBeMergedRootTimer);
                return;
            }
        }
        MutableTimer rootTimer = MutableTimer.createRootTimer(toBeMergedRootTimer.getName(),
                toBeMergedRootTimer.isExtended());
        rootTimer.merge(toBeMergedRootTimer);
        rootTimers.add(rootTimer);
    }

    private static List<Aggregate.Timer> getRootTimersProtobuf(List<MutableTimer> rootTimers) {
        List<Aggregate.Timer> protobufRootTimers =
                Lists.newArrayListWithCapacity(rootTimers.size());
        for (MutableTimer rootTimer : rootTimers) {
            protobufRootTimers.add(rootTimer.toProto());
        }
        return protobufRootTimers;
    }

    private static class MutableThreadStats {

        private double totalCpuNanos;
        private double totalBlockedNanos;
        private double totalWaitedNanos;
        private double totalAllocatedBytes;

        private boolean empty = true;

        private void addThreadStats(ThreadStats threadStats) {
            totalCpuNanos = NotAvailableAware.add(totalCpuNanos, threadStats.getTotalCpuNanos());
            totalBlockedNanos = NotAvailableAware.addMillisToNanos(totalBlockedNanos,
                    threadStats.getTotalBlockedMillis());
            totalWaitedNanos = NotAvailableAware.addMillisToNanos(totalWaitedNanos,
                    threadStats.getTotalWaitedMillis());
            totalAllocatedBytes = NotAvailableAware.add(totalAllocatedBytes,
                    threadStats.getTotalAllocatedBytes());
            empty = false;
        }

        private boolean isNA() {
            if (empty) {
                return true;
            }
            return NotAvailableAware.isNA(totalCpuNanos)
                    && NotAvailableAware.isNA(totalBlockedNanos)
                    && NotAvailableAware.isNA(totalWaitedNanos)
                    && NotAvailableAware.isNA(totalAllocatedBytes);
        }

        public Aggregate.ThreadStats toProto() {
            Aggregate.ThreadStats.Builder builder = Aggregate.ThreadStats.newBuilder();
            if (!NotAvailableAware.isNA(totalCpuNanos)) {
                builder.setTotalCpuNanos(toProto(totalCpuNanos));
            }
            if (!NotAvailableAware.isNA(totalBlockedNanos)) {
                builder.setTotalBlockedNanos(toProto(totalBlockedNanos));
            }
            if (!NotAvailableAware.isNA(totalWaitedNanos)) {
                builder.setTotalWaitedNanos(toProto(totalWaitedNanos));
            }
            if (!NotAvailableAware.isNA(totalAllocatedBytes)) {
                builder.setTotalAllocatedBytes(toProto(totalAllocatedBytes));
            }
            return builder.build();
        }

        private static OptionalDouble toProto(double value) {
            return OptionalDouble.newBuilder().setValue(value).build();
        }
    }
}
