/*
 * Copyright 2026 the original author or authors.
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
package org.glowroot.common2.repo.util;

import java.util.concurrent.CompletableFuture;

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.glowroot.common.live.ImmutableAggregateQuery;
import org.glowroot.common.live.ImmutableOverviewAggregate;
import org.glowroot.common.live.ImmutableThroughputAggregate;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.ThroughputAggregate;
import org.glowroot.common2.repo.AggregateRepository;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.common2.repo.GaugeValueRepository;
import org.glowroot.common2.repo.ImmutableTraceQuery;
import org.glowroot.common2.repo.TraceRepository;
import org.glowroot.common2.repo.util.RollupLevelService.DataKind;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition.MetricCondition;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueMessage.GaugeValue;
import org.glowroot.wire.api.model.Proto.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MetricServiceTest {

    private static final String AGENT_ID = "agent-a";
    private static final long START = 60_000;
    private static final long END = 120_000;

    private AggregateRepository aggregateRepository;
    private GaugeValueRepository gaugeValueRepository;
    private TraceRepository traceRepository;
    private RollupLevelService rollupLevelService;
    private MetricService metricService;

    @BeforeEach
    public void beforeEachTest() {
        aggregateRepository = mock(AggregateRepository.class);
        gaugeValueRepository = mock(GaugeValueRepository.class);
        traceRepository = mock(TraceRepository.class);
        rollupLevelService = mock(RollupLevelService.class);
        when(rollupLevelService.getRollupLevelForView(anyLong(), anyLong(), any(DataKind.class)))
                .thenReturn(0);
        when(rollupLevelService.getGaugeRollupLevelForView(anyLong(), anyLong(), anyBoolean()))
                .thenReturn(0);
        metricService = new MetricService(aggregateRepository, gaugeValueRepository,
                traceRepository, rollupLevelService);
    }

    @Test
    public void transactionCountSumsThroughput() {
        ThroughputAggregate aggregate = ImmutableThroughputAggregate.builder()
                .captureTime(END)
                .transactionCount(7)
                .errorCount(1L)
                .build();
        when(aggregateRepository.readThroughputAggregates(eq(AGENT_ID),
                eq(query()), eq(CassandraProfile.collector)))
                        .thenReturn(CompletableFuture.completedFuture(ImmutableList.of(aggregate)));

        Number value = metricService.getMetricValue(AGENT_ID, metric("transaction:count"), START,
                END, CassandraProfile.collector).toCompletableFuture().join();

        assertThat(value).isEqualTo(7L);
    }

    @Test
    public void transactionAverageConvertsNanosToMillis() {
        OverviewAggregate aggregate = ImmutableOverviewAggregate.builder()
                .captureTime(END)
                .totalDurationNanos(2_000_000.0)
                .transactionCount(2)
                .asyncTransactions(false)
                .mainThreadStats(Aggregate.ThreadStats.getDefaultInstance())
                .build();
        when(aggregateRepository.readOverviewAggregates(eq(AGENT_ID), eq(query()),
                eq(CassandraProfile.collector)))
                        .thenReturn(CompletableFuture.completedFuture(ImmutableList.of(aggregate)));

        Number value = metricService.getMetricValue(AGENT_ID, metric("transaction:average"), START,
                END, CassandraProfile.collector).toCompletableFuture().join();

        assertThat(value.doubleValue()).isEqualTo(1.0);
    }

    @Test
    public void errorRateIsPercent() {
        ThroughputAggregate aggregate = ImmutableThroughputAggregate.builder()
                .captureTime(END)
                .transactionCount(10)
                .errorCount(2L)
                .build();
        when(aggregateRepository.readThroughputAggregates(eq(AGENT_ID), eq(query()),
                eq(CassandraProfile.collector)))
                        .thenReturn(CompletableFuture.completedFuture(ImmutableList.of(aggregate)));

        Number value = metricService.getMetricValue(AGENT_ID, metric("error:rate"), START, END,
                CassandraProfile.collector).toCompletableFuture().join();

        assertThat(value.doubleValue()).isEqualTo(20.0);
    }

    @Test
    public void errorCountWithEmptyFilterUsesTraceRepository() {
        // protobuf string default is "", not null — MetricService treats only null as "no filter"
        ImmutableTraceQuery traceQuery = ImmutableTraceQuery.builder()
                .transactionType("tt")
                .from(START)
                .to(END)
                .build();
        when(traceRepository.readErrorMessageCount(AGENT_ID, traceQuery, "",
                CassandraProfile.collector))
                        .thenReturn(CompletableFuture.completedFuture(3L));

        Number value = metricService.getMetricValue(AGENT_ID, metric("error:count"), START, END,
                CassandraProfile.collector).toCompletableFuture().join();

        assertThat(value).isEqualTo(3L);
    }

    @Test
    public void errorCountWithFilterUsesTraceRepository() {
        MetricCondition condition = MetricCondition.newBuilder()
                .setMetric("error:count")
                .setTransactionType("tt")
                .setErrorMessageFilter("boom")
                .build();
        ImmutableTraceQuery traceQuery = ImmutableTraceQuery.builder()
                .transactionType("tt")
                .from(START)
                .to(END)
                .build();
        when(traceRepository.readErrorMessageCount(AGENT_ID, traceQuery, "boom",
                CassandraProfile.collector))
                        .thenReturn(CompletableFuture.completedFuture(9L));

        Number value = metricService.getMetricValue(AGENT_ID, condition, START, END,
                CassandraProfile.collector).toCompletableFuture().join();

        assertThat(value).isEqualTo(9L);
    }

    @Test
    public void gaugeValueIsWeightedAverage() {
        GaugeValue g1 = GaugeValue.newBuilder().setValue(10).setWeight(1).build();
        GaugeValue g2 = GaugeValue.newBuilder().setValue(30).setWeight(1).build();
        when(gaugeValueRepository.readGaugeValues(eq(AGENT_ID), eq("java.lang:type=Memory"),
                eq(START + 1), eq(END), anyInt(), eq(CassandraProfile.collector)))
                        .thenReturn(CompletableFuture.completedFuture(ImmutableList.of(g1, g2)));

        MetricCondition condition = MetricCondition.newBuilder()
                .setMetric("gauge:java.lang:type=Memory")
                .build();

        Number value = metricService.getMetricValue(AGENT_ID, condition, START, END,
                CassandraProfile.collector).toCompletableFuture().join();

        assertThat(value.doubleValue()).isEqualTo(20.0);
    }

    @Test
    public void emptyAggregatesReturnNullForAverage() {
        when(aggregateRepository.readOverviewAggregates(eq(AGENT_ID), eq(query()),
                eq(CassandraProfile.collector)))
                        .thenReturn(CompletableFuture.completedFuture(ImmutableList.of()));

        Number value = metricService.getMetricValue(AGENT_ID, metric("transaction:average"), START,
                END, CassandraProfile.collector).toCompletableFuture().join();

        assertThat(value).isNull();
    }

    @Test
    public void unexpectedMetricThrows() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> metricService.getMetricValue(AGENT_ID, metric("nope"), START, END,
                        CassandraProfile.collector))
                .withMessage("Unexpected metric: nope");
    }

    private static MetricCondition metric(String name) {
        MetricCondition.Builder builder = MetricCondition.newBuilder()
                .setMetric(name)
                .setTransactionType("tt");
        if (name.equals("transaction:x-percentile")) {
            builder.setPercentile(OptionalDouble.newBuilder().setValue(95));
        }
        return builder.build();
    }

    private static ImmutableAggregateQuery query() {
        return ImmutableAggregateQuery.builder()
                .transactionType("tt")
                .from(START + 1)
                .to(END)
                .rollupLevel(0)
                .build();
    }
}
