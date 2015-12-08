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
package org.glowroot.agent.impl;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.collect.Lists;

import org.glowroot.agent.model.Profile;
import org.glowroot.agent.model.QueryData;
import org.glowroot.agent.model.ThreadInfoData;
import org.glowroot.agent.model.TimerImpl;
import org.glowroot.agent.model.Transaction;
import org.glowroot.common.config.AdvancedConfig;
import org.glowroot.common.model.LazyHistogram;
import org.glowroot.common.model.LazyHistogram.ScratchBuffer;
import org.glowroot.common.model.MutableProfile;
import org.glowroot.common.model.QueryCollector;
import org.glowroot.common.util.NotAvailableAware;
import org.glowroot.common.util.Styles;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate.OptionalDouble;

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
    private final List<MutableTimer> rootTimers = Lists.newArrayList();
    // histogram values are in nanoseconds, but with microsecond precision to reduce the number of
    // buckets (and memory) required
    private final LazyHistogram lazyHistogram = new LazyHistogram();
    private final MutableProfile profile = new MutableProfile();
    private final QueryCollector queries;

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

    void mergeProfile(Profile toBeMergedProfile) {
        toBeMergedProfile.mergeIntoProfile(profile);
    }

    void mergeQueries(Iterator<QueryData> toBeMergedQueries) {
        while (toBeMergedQueries.hasNext()) {
            QueryData toBeMergedQuery = toBeMergedQueries.next();
            queries.mergeQuery(toBeMergedQuery.getQueryType(), toBeMergedQuery.getQueryText(),
                    toBeMergedQuery.getTotalNanos(), toBeMergedQuery.getExecutionCount(),
                    toBeMergedQuery.getTotalRows());
        }
    }

    Aggregate build(ScratchBuffer scratchBuffer) throws IOException {
        Aggregate.Builder builder = Aggregate.newBuilder()
                .setTotalNanos(totalNanos)
                .setTransactionCount(transactionCount)
                .setErrorCount(errorCount)
                .setTotalCpuNanos(toOptionalDouble(totalCpuNanos))
                .setTotalBlockedNanos(toOptionalDouble(totalBlockedNanos))
                .setTotalWaitedNanos(toOptionalDouble(totalWaitedNanos))
                .setTotalAllocatedBytes(toOptionalDouble(totalAllocatedBytes))
                .addAllRootTimer(getRootTimersProtobuf())
                .setTotalNanosHistogram(lazyHistogram.toProtobuf(scratchBuffer));
        if (profile.getSampleCount() > 0) {
            builder.setProfile(profile.toProtobuf());
        }
        return builder.addAllQueriesByType(queries.toProtobuf(true))
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
