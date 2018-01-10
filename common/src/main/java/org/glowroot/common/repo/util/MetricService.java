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
package org.glowroot.common.repo.util;

import java.util.List;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;

import org.glowroot.common.live.ImmutableTransactionQuery;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.PercentileAggregate;
import org.glowroot.common.live.LiveAggregateRepository.ThroughputAggregate;
import org.glowroot.common.model.LazyHistogram;
import org.glowroot.common.repo.AggregateRepository;
import org.glowroot.common.repo.GaugeValueRepository;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition.MetricCondition;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValue;

import static com.google.common.base.Preconditions.checkState;

class MetricService {

    private static final double NANOSECONDS_PER_MILLISECOND = 1000000.0;

    private final AggregateRepository aggregateRepository;
    private final GaugeValueRepository gaugeValueRepository;
    private final RollupLevelService rollupLevelService;

    public MetricService(AggregateRepository aggregateRepository,
            GaugeValueRepository gaugeValueRepository, RollupLevelService rollupLevelService) {
        this.aggregateRepository = aggregateRepository;
        this.gaugeValueRepository = gaugeValueRepository;
        this.rollupLevelService = rollupLevelService;
    }

    @Nullable
    Number getMetricValue(String agentRollupId, MetricCondition metricCondition, long startTime,
            long endTime) throws Exception {
        String metric = metricCondition.getMetric();
        if (metric.equals("transaction:x-percentile")) {
            return getTransactionDurationPercentile(agentRollupId,
                    metricCondition.getTransactionType(),
                    Strings.emptyToNull(metricCondition.getTransactionName()),
                    metricCondition.getPercentile().getValue(), startTime, endTime);
        } else if (metric.equals("transaction:average")) {
            return getTransactionAverage(agentRollupId, metricCondition.getTransactionType(),
                    Strings.emptyToNull(metricCondition.getTransactionName()), startTime, endTime);
        } else if (metric.equals("transaction:count")) {
            return getTransactionCount(agentRollupId, metricCondition.getTransactionType(),
                    Strings.emptyToNull(metricCondition.getTransactionName()), startTime, endTime);
        } else if (metric.equals("error:rate")) {
            return getErrorRate(agentRollupId, metricCondition.getTransactionType(),
                    Strings.emptyToNull(metricCondition.getTransactionName()), startTime, endTime);
        } else if (metric.equals("error:count")) {
            return getErrorCount(agentRollupId, metricCondition.getTransactionType(),
                    Strings.emptyToNull(metricCondition.getTransactionName()), startTime, endTime);
        } else if (metric.startsWith("gauge:")) {
            return getGaugeValue(agentRollupId, metric.substring("gauge:".length()), startTime,
                    endTime);
        } else {
            throw new IllegalStateException("Unexpected metric: " + metric);
        }
    }

    private @Nullable Double getTransactionDurationPercentile(String agentRollupId,
            String transactionType, @Nullable String transactionName, double percentile,
            long startTime, long endTime) throws Exception {
        int rollupLevel = rollupLevelService.getRollupLevelForView(startTime, endTime);
        // startTime + 1 in order to not include the aggregate value at startTime
        List<PercentileAggregate> aggregates =
                aggregateRepository.readPercentileAggregates(agentRollupId,
                        ImmutableTransactionQuery.builder()
                                .transactionType(transactionType)
                                .transactionName(transactionName)
                                .from(startTime + 1)
                                .to(endTime)
                                .rollupLevel(rollupLevel)
                                .build());
        if (aggregates.isEmpty()) {
            return null;
        }
        LazyHistogram durationNanosHistogram = new LazyHistogram();
        for (PercentileAggregate aggregate : aggregates) {
            durationNanosHistogram.merge(aggregate.durationNanosHistogram());
        }
        return durationNanosHistogram.getValueAtPercentile(percentile)
                / NANOSECONDS_PER_MILLISECOND;
    }

    private @Nullable Double getTransactionAverage(String agentRollupId, String transactionType,
            @Nullable String transactionName, long startTime, long endTime) throws Exception {
        List<OverviewAggregate> aggregates = getOverviewAggregates(agentRollupId, transactionType,
                transactionName, startTime, endTime);
        if (aggregates.isEmpty()) {
            return null;
        }
        long totalDurationNanos = 0;
        long totalTransactionCount = 0;
        for (OverviewAggregate aggregate : aggregates) {
            totalDurationNanos += aggregate.totalDurationNanos();
            totalTransactionCount += aggregate.transactionCount();
        }
        // individual aggregate transaction counts cannot be zero, and aggregates is non-empty
        // (see above conditional), so totalTransactionCount is guaranteed non-zero
        checkState(totalTransactionCount != 0);
        return totalDurationNanos / (totalTransactionCount * NANOSECONDS_PER_MILLISECOND);
    }

    private long getTransactionCount(String agentRollupId, String transactionType,
            @Nullable String transactionName, long startTime, long endTime) throws Exception {
        List<ThroughputAggregate> throughputAggregates = getThroughputAggregates(agentRollupId,
                transactionType, transactionName, startTime, endTime);
        long totalTransactionCount = 0;
        for (ThroughputAggregate throughputAggregate : throughputAggregates) {
            totalTransactionCount += throughputAggregate.transactionCount();
        }
        return totalTransactionCount;
    }

    private @Nullable Double getErrorRate(String agentRollupId, String transactionType,
            @Nullable String transactionName, long startTime, long endTime) throws Exception {
        List<ThroughputAggregate> aggregates = getThroughputAggregates(agentRollupId,
                transactionType, transactionName, startTime, endTime);
        if (aggregates.isEmpty()) {
            return null;
        }
        long totalTransactionCount = 0;
        long totalErrorCount = 0;
        for (ThroughputAggregate aggregate : aggregates) {
            totalTransactionCount += aggregate.transactionCount();
            totalErrorCount += MoreObjects.firstNonNull(aggregate.errorCount(), 0L);
        }
        // individual aggregate transaction counts cannot be zero, and aggregates is non-empty
        // (see above conditional), so totalTransactionCount is guaranteed non-zero
        checkState(totalTransactionCount != 0);
        return (100.0 * totalErrorCount) / totalTransactionCount;
    }

    private long getErrorCount(String agentRollupId, String transactionType,
            @Nullable String transactionName, long startTime, long endTime) throws Exception {
        List<ThroughputAggregate> aggregates = getThroughputAggregates(agentRollupId,
                transactionType, transactionName, startTime, endTime);
        long totalErrorCount = 0;
        for (ThroughputAggregate aggregate : aggregates) {
            totalErrorCount += MoreObjects.firstNonNull(aggregate.errorCount(), 0L);
        }
        return totalErrorCount;
    }

    private @Nullable Double getGaugeValue(String agentRollupId, String gaugeName,
            long startTime, long endTime) throws Exception {
        int rollupLevel = rollupLevelService.getGaugeRollupLevelForView(startTime, endTime,
                agentRollupId.endsWith("::"));
        // startTime + 1 in order to not include the gauge value at startTime
        List<GaugeValue> gaugeValues = gaugeValueRepository.readGaugeValues(agentRollupId,
                gaugeName, startTime + 1, endTime, rollupLevel);
        if (gaugeValues.isEmpty()) {
            return null;
        }
        double totalWeightedValue = 0;
        long totalWeight = 0;
        for (GaugeValue gaugeValue : gaugeValues) {
            totalWeightedValue += gaugeValue.getValue() * gaugeValue.getWeight();
            totalWeight += gaugeValue.getWeight();
        }
        // individual gauge value weights cannot be zero, and gaugeValues is non-empty
        // (see above conditional), so totalWeight is guaranteed non-zero
        checkState(totalWeight != 0);
        return totalWeightedValue / totalWeight;
    }

    private List<ThroughputAggregate> getThroughputAggregates(String agentRollupId,
            String transactionType, @Nullable String transactionName, long startTime, long endTime)
            throws Exception {
        int rollupLevel = rollupLevelService.getRollupLevelForView(startTime, endTime);
        // startTime + 1 in order to not include the aggregate at startTime
        return aggregateRepository.readThroughputAggregates(agentRollupId,
                ImmutableTransactionQuery.builder()
                        .transactionType(transactionType)
                        .transactionName(transactionName)
                        .from(startTime + 1)
                        .to(endTime)
                        .rollupLevel(rollupLevel)
                        .build());
    }

    private List<OverviewAggregate> getOverviewAggregates(String agentRollupId,
            String transactionType, @Nullable String transactionName, long startTime, long endTime)
            throws Exception {
        int rollupLevel = rollupLevelService.getRollupLevelForView(startTime, endTime);
        // startTime + 1 in order to not include the aggregate at startTime
        return aggregateRepository.readOverviewAggregates(agentRollupId,
                ImmutableTransactionQuery.builder()
                        .transactionType(transactionType)
                        .transactionName(transactionName)
                        .from(startTime + 1)
                        .to(endTime)
                        .rollupLevel(rollupLevel)
                        .build());
    }
}
