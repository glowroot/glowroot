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
package org.glowroot.ui;

import java.util.Map;

import org.junit.jupiter.api.Test;

import org.glowroot.common.live.ImmutableOverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;

import static org.assertj.core.api.Assertions.assertThat;

public class StackedTimerTotalsTest {

    @Test
    public void leafOnlyTimerMatchesInclusiveTotal() {
        Aggregate.Timer leaf = Aggregate.Timer.newBuilder()
                .setName("jdbc")
                .setTotalNanos(5_000_000)
                .setCount(1)
                .build();
        Aggregate.Timer root = Aggregate.Timer.newBuilder()
                .setName("http request")
                .setTotalNanos(5_000_000)
                .setCount(1)
                .addChildTimer(leaf)
                .build();

        OverviewAggregate aggregate = overview(1000, 5_000_000, 1, root);

        Map<String, Double> stacked = StackedTimerTotals.create(aggregate);

        // Chart skips the root timer; leaf self-time equals inclusive total when there are no
        // nested children under jdbc.
        assertThat(stacked).containsOnlyKeys("jdbc");
        assertThat(stacked.get("jdbc")).isEqualTo(5_000_000);
        assertThat(StackedTimerTotals.selfNanos(leaf)).isEqualTo(leaf.getTotalNanos());
    }

    @Test
    public void parentSelfPlusChildEqualsInclusiveParent() {
        Aggregate.Timer child = Aggregate.Timer.newBuilder()
                .setName("jdbc")
                .setTotalNanos(4_000_000)
                .setCount(1)
                .build();
        Aggregate.Timer parent = Aggregate.Timer.newBuilder()
                .setName("http")
                .setTotalNanos(10_000_000)
                .setCount(1)
                .addChildTimer(child)
                .build();
        Aggregate.Timer root = Aggregate.Timer.newBuilder()
                .setName("http request")
                .setTotalNanos(10_000_000)
                .setCount(1)
                .addChildTimer(parent)
                .build();

        OverviewAggregate aggregate = overview(1000, 10_000_000, 1, root);
        Map<String, Double> stacked = StackedTimerTotals.create(aggregate);

        double httpSelf = stacked.get("http");
        double jdbcSelf = stacked.get("jdbc");

        assertThat(httpSelf).isEqualTo(6_000_000); // exclusive / chart segment
        assertThat(jdbcSelf).isEqualTo(4_000_000);
        // Inclusive parent (breakdown table) = chart(parent self) + chart(child self)
        assertThat(httpSelf + jdbcSelf).isEqualTo(parent.getTotalNanos());
        assertThat(StackedTimerTotals.selfNanos(parent)).isEqualTo(httpSelf);
    }

    @Test
    public void captureTimeFilterMatchesBreakdownMergeWindow() {
        assertThat(StackedTimerTotals.captureTimeInMergedRange(1000, 1000, 2000)).isFalse();
        assertThat(StackedTimerTotals.captureTimeInMergedRange(1001, 1000, 2000)).isTrue();
        assertThat(StackedTimerTotals.captureTimeInMergedRange(2000, 1000, 2000)).isTrue();
        assertThat(StackedTimerTotals.captureTimeInMergedRange(2001, 1000, 2000)).isFalse();
    }

    private static OverviewAggregate overview(long captureTime, double totalDurationNanos,
            long transactionCount, Aggregate.Timer rootTimer) {
        return ImmutableOverviewAggregate.builder()
                .captureTime(captureTime)
                .totalDurationNanos(totalDurationNanos)
                .transactionCount(transactionCount)
                .asyncTransactions(false)
                .addMainThreadRootTimers(rootTimer)
                .mainThreadStats(Aggregate.ThreadStats.getDefaultInstance())
                .build();
    }
}
