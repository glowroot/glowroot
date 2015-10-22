/*
 * Copyright 2013-2015 the original author or authors.
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
package org.glowroot.agent.model;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.collect.Lists;

import org.glowroot.agent.model.ThreadInfoComponent.ThreadInfoData;
import org.glowroot.common.config.AdvancedConfig;
import org.glowroot.common.live.ImmutableErrorPoint;
import org.glowroot.common.live.ImmutableOverallErrorSummary;
import org.glowroot.common.live.ImmutableOverallSummary;
import org.glowroot.common.live.ImmutableOverviewAggregate;
import org.glowroot.common.live.ImmutablePercentileAggregate;
import org.glowroot.common.live.ImmutableThroughputAggregate;
import org.glowroot.common.live.ImmutableTransactionErrorSummary;
import org.glowroot.common.live.ImmutableTransactionSummary;
import org.glowroot.common.live.LiveAggregateRepository.ErrorPoint;
import org.glowroot.common.live.LiveAggregateRepository.OverallErrorSummary;
import org.glowroot.common.live.LiveAggregateRepository.OverallSummary;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.live.LiveAggregateRepository.ThroughputAggregate;
import org.glowroot.common.live.LiveAggregateRepository.TransactionErrorSummary;
import org.glowroot.common.live.LiveAggregateRepository.TransactionSummary;
import org.glowroot.common.model.LazyHistogram;
import org.glowroot.common.model.LazyHistogram.ScratchBuffer;
import org.glowroot.common.model.MutableProfileTree;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.common.util.NotAvailableAware;
import org.glowroot.common.util.Styles;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate.OptionalDouble;
import org.glowroot.wire.api.model.ProfileTreeOuterClass.ProfileTree;

import static com.google.common.base.Preconditions.checkNotNull;

// must be used under an appropriate lock
@Styles.Private
class AggregateCollector {

    private final @Nullable String transactionName;
    private long totalNanos;
    private long transactionCount;
    private long errorCount;
    private double totalCpuNanos = NotAvailableAware.NA;
    private double totalBlockedNanos = NotAvailableAware.NA;
    private double totalWaitedNanos = NotAvailableAware.NA;
    private double totalAllocatedBytes = NotAvailableAware.NA;
    // histogram values are in nanoseconds, but with microsecond precision to reduce the number of
    // buckets (and memory) required
    private final LazyHistogram lazyHistogram = new LazyHistogram();
    private final List<MutableTimer> rootTimers = Lists.newArrayList();
    private final QueryCollector queries;
    private final MutableProfileTree profileTree = new MutableProfileTree();

    AggregateCollector(@Nullable String transactionName, int maxAggregateQueriesPerQueryType) {
        int hardLimitMultiplierWhileBuilding = transactionName == null
                ? AdvancedConfig.OVERALL_AGGREGATE_QUERIES_HARD_LIMIT_MULTIPLIER
                : AdvancedConfig.TRANSACTION_AGGREGATE_QUERIES_HARD_LIMIT_MULTIPLIER;
        queries = new QueryCollector(maxAggregateQueriesPerQueryType,
                hardLimitMultiplierWhileBuilding);
        this.transactionName = transactionName;
    }

    void add(Transaction transaction) {
        long totalNanos = transaction.getDurationNanos();
        this.totalNanos += totalNanos;
        transactionCount++;
        if (transaction.getErrorMessage() != null) {
            errorCount++;
        }
        ThreadInfoData threadInfo = transaction.getThreadInfo();
        if (threadInfo != null) {
            totalCpuNanos = NotAvailableAware.add(totalCpuNanos, threadInfo.threadCpuNanos());
            totalBlockedNanos =
                    NotAvailableAware.add(totalBlockedNanos, threadInfo.threadBlockedNanos());
            totalWaitedNanos =
                    NotAvailableAware.add(totalWaitedNanos, threadInfo.threadWaitedNanos());
            totalAllocatedBytes =
                    NotAvailableAware.add(totalAllocatedBytes, threadInfo.threadAllocatedBytes());
        }
        lazyHistogram.add(totalNanos);
    }

    void mergeRootTimer(TimerImpl toBeMergedRootTimer) {
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

    void mergeQueries(Iterator<QueryData> toBeMergedQueries) {
        while (toBeMergedQueries.hasNext()) {
            QueryData toBeMergedQuery = toBeMergedQueries.next();
            queries.mergeQuery(toBeMergedQuery.getQueryType(), toBeMergedQuery.getQueryText(),
                    toBeMergedQuery.getTotalNanos(), toBeMergedQuery.getExecutionCount(),
                    toBeMergedQuery.getTotalRows());
        }
    }

    void mergeProfile(Profile toBeMergedProfile) {
        toBeMergedProfile.mergeIntoProfileTree(profileTree);
    }

    Aggregate build(ScratchBuffer scratchBuffer) throws IOException {
        return Aggregate.newBuilder()
                .setTotalNanos(totalNanos)
                .setTransactionCount(transactionCount)
                .setErrorCount(errorCount)
                .setTotalCpuNanos(toOptionalDouble(totalCpuNanos))
                .setTotalBlockedNanos(toOptionalDouble(totalBlockedNanos))
                .setTotalWaitedNanos(toOptionalDouble(totalWaitedNanos))
                .setTotalAllocatedBytes(toOptionalDouble(totalAllocatedBytes))
                .setTotalNanosHistogram(lazyHistogram.toProtobuf(scratchBuffer))
                .addAllRootTimer(getRootTimersProtobuf())
                .addAllQueriesByType(queries.toProtobuf(true))
                .setProfileTree(profileTree.toProtobuf())
                .build();
    }

    OverviewAggregate buildLiveOverviewAggregate(long captureTime) throws IOException {
        return ImmutableOverviewAggregate.builder()
                .captureTime(captureTime)
                .totalNanos(totalNanos)
                .transactionCount(transactionCount)
                .totalCpuNanos(totalCpuNanos)
                .totalBlockedNanos(totalBlockedNanos)
                .totalWaitedNanos(totalWaitedNanos)
                .totalAllocatedBytes(totalAllocatedBytes)
                .rootTimers(getRootTimersProtobuf())
                .build();
    }

    PercentileAggregate buildLivePercentileAggregate(long captureTime)
            throws IOException {
        return ImmutablePercentileAggregate.builder()
                .captureTime(captureTime)
                .totalNanos(totalNanos)
                .transactionCount(transactionCount)
                .histogram(lazyHistogram.toProtobuf(new ScratchBuffer()))
                .build();
    }

    ThroughputAggregate buildLiveThroughputAggregate(long captureTime) throws IOException {
        return ImmutableThroughputAggregate.builder()
                .captureTime(captureTime)
                .transactionCount(transactionCount)
                .build();
    }

    OverallSummary getLiveOverallSummary() {
        return ImmutableOverallSummary.builder()
                .totalNanos(totalNanos)
                .transactionCount(transactionCount)
                .build();
    }

    TransactionSummary getLiveTransactionSummary() {
        // this method should not be called on overall aggregate
        checkNotNull(transactionName);
        return ImmutableTransactionSummary.builder()
                .transactionName(transactionName)
                .totalNanos(totalNanos)
                .transactionCount(transactionCount)
                .build();
    }

    OverallErrorSummary getLiveOverallErrorSummary() {
        return ImmutableOverallErrorSummary.builder()
                .errorCount(errorCount)
                .transactionCount(transactionCount)
                .build();
    }

    TransactionErrorSummary getLiveTransactionErrorSummary() {
        // this method should not be called on overall aggregate
        checkNotNull(transactionName);
        return ImmutableTransactionErrorSummary.builder()
                .transactionName(transactionName)
                .errorCount(errorCount)
                .transactionCount(transactionCount)
                .build();
    }

    // needs to return copy for thread safety
    ProfileTree getLiveProfile() throws IOException {
        return profileTree.toProtobuf();
    }

    // needs to return copy for thread safety
    List<Aggregate.QueriesByType> getLiveQueries() {
        return queries.toProtobuf(false);
    }

    @Nullable
    ErrorPoint buildErrorPoint(long captureTime) {
        if (errorCount == 0) {
            return null;
        }
        return ImmutableErrorPoint.builder()
                .captureTime(captureTime)
                .errorCount(errorCount)
                .transactionCount(transactionCount)
                .build();
    }

    private List<Aggregate.Timer> getRootTimersProtobuf() {
        List<Aggregate.Timer> rootTimers = Lists.newArrayListWithCapacity(this.rootTimers.size());
        for (MutableTimer rootTimer : this.rootTimers) {
            rootTimers.add(rootTimer.toProtobuf());
        }
        return rootTimers;
    }

    private static OptionalDouble toOptionalDouble(double value) {
        if (value == NotAvailableAware.NA) {
            return OptionalDouble.getDefaultInstance();
        } else {
            return OptionalDouble.newBuilder().setValue(value).build();
        }
    }
}
