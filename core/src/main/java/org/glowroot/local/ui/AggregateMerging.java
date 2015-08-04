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
package org.glowroot.local.ui;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.immutables.value.Value;

import org.glowroot.collector.Aggregate;
import org.glowroot.collector.AggregateTimer;
import org.glowroot.collector.LazyHistogram;
import org.glowroot.collector.ProfileAggregate;
import org.glowroot.collector.QueryAggregate;
import org.glowroot.collector.QueryComponent;
import org.glowroot.collector.QueryComponent.AggregateQuery;
import org.glowroot.common.ObjectMappers;
import org.glowroot.common.Styles;
import org.glowroot.local.store.AggregateDao;
import org.glowroot.local.store.AlertingService;
import org.glowroot.transaction.model.ProfileNode;

public class AggregateMerging {

    private static final ObjectMapper mapper = ObjectMappers.create();

    private AggregateMerging() {}

    public static TimerMergedAggregate getTimerMergedAggregate(List<Aggregate> aggregates)
            throws Exception {
        long transactionCount = 0;
        AggregateTimer syntheticRootTimer = AggregateTimer.createSyntheticRootTimer();
        for (Aggregate aggregate : aggregates) {
            transactionCount += aggregate.transactionCount();
            AggregateTimer toBeMergedSyntheticRootTimer =
                    mapper.readValue(aggregate.timers(), AggregateTimer.class);
            syntheticRootTimer.mergeMatchedTimer(toBeMergedSyntheticRootTimer);
        }
        TimerMergedAggregate.Builder timerMergedAggregate = TimerMergedAggregate.builder();
        timerMergedAggregate.syntheticRootTimer(syntheticRootTimer);
        timerMergedAggregate.transactionCount(transactionCount);
        return timerMergedAggregate.build();
    }

    public static PercentileMergedAggregate getPercentileMergedAggregate(List<Aggregate> aggregates,
            List<Double> percentiles) throws Exception {
        long transactionCount = 0;
        long totalMicros = 0;
        LazyHistogram histogram = new LazyHistogram();
        for (Aggregate aggregate : aggregates) {
            transactionCount += aggregate.transactionCount();
            totalMicros += aggregate.totalMicros();
            histogram.decodeFromByteBuffer(ByteBuffer.wrap(aggregate.histogram()));
        }

        List<PercentileValue> percentileValues = Lists.newArrayList();
        for (double percentile : percentiles) {
            percentileValues.add(PercentileValue.of(
                    AlertingService.getPercentileWithSuffix(percentile) + " percentile",
                    histogram.getValueAtPercentile(percentile)));
        }
        return PercentileMergedAggregate.builder()
                .totalMicros(totalMicros)
                .transactionCount(transactionCount)
                .addAllPercentileValues(percentileValues)
                .build();
    }

    public static ThreadInfoAggregate getThreadInfoAggregate(List<Aggregate> aggregates) {
        Long totalCpuMicros = null;
        Long totalBlockedMicros = null;
        Long totalWaitedMicros = null;
        Long totalAllocatedKBytes = null;
        for (Aggregate aggregate : aggregates) {
            totalCpuMicros = nullAwareAdd(totalCpuMicros, aggregate.totalCpuMicros());
            totalBlockedMicros = nullAwareAdd(totalBlockedMicros, aggregate.totalBlockedMicros());
            totalWaitedMicros = nullAwareAdd(totalWaitedMicros, aggregate.totalWaitedMicros());
            totalAllocatedKBytes =
                    nullAwareAdd(totalAllocatedKBytes, aggregate.totalAllocatedKBytes());
        }
        return ThreadInfoAggregate.builder()
                .totalCpuMicros(totalCpuMicros)
                .totalBlockedMicros(totalBlockedMicros)
                .totalWaitedMicros(totalWaitedMicros)
                .totalAllocatedKBytes(totalAllocatedKBytes)
                .build();
    }

    public static Map<String, List<AggregateQuery>> getOrderedAndTruncatedQueries(
            List<QueryAggregate> queryAggregates, int maxAggregateQueriesPerQueryType)
                    throws IOException {
        QueryComponent queryComponent = new QueryComponent(maxAggregateQueriesPerQueryType, 0);
        for (QueryAggregate queryAggregate : queryAggregates) {
            String queries = queryAggregate.queries().read();
            if (!queries.equals(AggregateDao.OVERWRITTEN)) {
                queryComponent.mergeQueries(queries);
            }
        }
        return queryComponent.getOrderedAndTruncatedQueries();
    }

    public static ProfileNode getMergedProfile(List<ProfileAggregate> profileAggregates)
            throws IOException {
        ProfileNode syntheticRootNode = ProfileNode.createSyntheticRoot();
        for (ProfileAggregate profileAggregate : profileAggregates) {
            String profile = profileAggregate.profile().read();
            if (!profile.equals(AggregateDao.OVERWRITTEN)) {
                ProfileNode toBeMergedRootNode =
                        ObjectMappers.readRequiredValue(mapper, profile, ProfileNode.class);
                syntheticRootNode.mergeMatchedNode(toBeMergedRootNode);
            }
        }
        return syntheticRootNode;
    }

    private static @Nullable Long nullAwareAdd(@Nullable Long x, @Nullable Long y) {
        if (x == null) {
            return y;
        }
        if (y == null) {
            return x;
        }
        return x + y;
    }

    @Value.Immutable
    public static abstract class TimerMergedAggregateBase {
        @JsonProperty("timers")
        public abstract AggregateTimer syntheticRootTimer();
        public abstract long transactionCount();
    }

    @Value.Immutable
    public static abstract class PercentileMergedAggregateBase {

        public abstract long totalMicros();
        public abstract long transactionCount();
        public abstract ImmutableList<PercentileValue> percentileValues();
    }

    @Value.Immutable
    @Styles.AllParameters
    public static abstract class PercentileValueBase {
        public abstract String dataSeriesName();
        public abstract long value();
    }

    @Value.Immutable
    public abstract static class ThreadInfoAggregateBase {

        abstract @Nullable Long totalCpuMicros();
        abstract @Nullable Long totalBlockedMicros();
        abstract @Nullable Long totalWaitedMicros();
        abstract @Nullable Long totalAllocatedKBytes();

        public boolean isEmpty() {
            return totalCpuMicros() == null && totalBlockedMicros() == null
                    && totalWaitedMicros() == null && totalAllocatedKBytes() == null;
        }
    }
}
