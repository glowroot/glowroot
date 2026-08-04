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

import java.util.List;

import org.junit.jupiter.api.Test;

import org.glowroot.common.live.ImmutableOverviewAggregate;
import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.ui.BreakdownTimerMetrics.Kind;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;

import static org.assertj.core.api.Assertions.assertThat;

public class BreakdownTimerMetricsTest {

    @Test
    public void leafTimerInclusiveExclusiveAndCount() {
        Aggregate.Timer leaf = Aggregate.Timer.newBuilder()
                .setName("jdbc query")
                .setTotalNanos(5_000_000)
                .setCount(2)
                .build();
        Aggregate.Timer root = Aggregate.Timer.newBuilder()
                .setName("http request")
                .setTotalNanos(5_000_000)
                .setCount(1)
                .addChildTimer(leaf)
                .build();
        OverviewAggregate aggregate = overview(1000, 5_000_000, 1, root);

        assertThat(BreakdownTimerMetrics.value(aggregate, "jdbc query", Kind.INCLUSIVE_NANOS))
                .isEqualTo(5_000_000);
        assertThat(BreakdownTimerMetrics.value(aggregate, "jdbc query", Kind.EXCLUSIVE_NANOS))
                .isEqualTo(5_000_000);
        assertThat(BreakdownTimerMetrics.value(aggregate, "jdbc query", Kind.COUNT))
                .isEqualTo(2);
        assertThat(BreakdownTimerMetrics.value(aggregate, "http request", Kind.INCLUSIVE_NANOS))
                .isEqualTo(5_000_000);
        assertThat(BreakdownTimerMetrics.value(aggregate, "http request", Kind.EXCLUSIVE_NANOS))
                .isEqualTo(0);
    }

    @Test
    public void parentExclusiveIsSelfTime() {
        Aggregate.Timer child = Aggregate.Timer.newBuilder()
                .setName("jdbc query")
                .setTotalNanos(4_000_000)
                .setCount(1)
                .build();
        Aggregate.Timer parent = Aggregate.Timer.newBuilder()
                .setName("http client request")
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
        OverviewAggregate aggregate = overview(1000, 10_000_000, 2, root);

        assertThat(BreakdownTimerMetrics.value(aggregate, "http client request",
                Kind.INCLUSIVE_NANOS)).isEqualTo(10_000_000);
        assertThat(BreakdownTimerMetrics.value(aggregate, "http client request",
                Kind.EXCLUSIVE_NANOS)).isEqualTo(6_000_000);
        assertThat(BreakdownTimerMetrics.value(aggregate, "jdbc query", Kind.EXCLUSIVE_NANOS))
                .isEqualTo(4_000_000);
    }

    @Test
    public void duplicateTimerNamesUnderDifferentParentsAreSummed() {
        Aggregate.Timer jdbcUnderA = Aggregate.Timer.newBuilder()
                .setName("jdbc query")
                .setTotalNanos(1_000_000)
                .setCount(1)
                .build();
        Aggregate.Timer jdbcUnderB = Aggregate.Timer.newBuilder()
                .setName("jdbc query")
                .setTotalNanos(2_000_000)
                .setCount(3)
                .build();
        Aggregate.Timer a = Aggregate.Timer.newBuilder()
                .setName("a")
                .setTotalNanos(1_000_000)
                .setCount(1)
                .addChildTimer(jdbcUnderA)
                .build();
        Aggregate.Timer b = Aggregate.Timer.newBuilder()
                .setName("b")
                .setTotalNanos(2_000_000)
                .setCount(1)
                .addChildTimer(jdbcUnderB)
                .build();
        Aggregate.Timer root = Aggregate.Timer.newBuilder()
                .setName("http request")
                .setTotalNanos(3_000_000)
                .setCount(1)
                .addChildTimer(a)
                .addChildTimer(b)
                .build();
        OverviewAggregate aggregate = overview(1000, 3_000_000, 1, root);

        assertThat(BreakdownTimerMetrics.value(aggregate, "jdbc query", Kind.INCLUSIVE_NANOS))
                .isEqualTo(3_000_000);
        assertThat(BreakdownTimerMetrics.value(aggregate, "jdbc query", Kind.COUNT))
                .isEqualTo(4);
    }

    @Test
    public void extendedTimersDoNotAddToCount() {
        Aggregate.Timer extended = Aggregate.Timer.newBuilder()
                .setName("jdbc query")
                .setTotalNanos(9_000_000)
                .setCount(9)
                .setExtended(true)
                .build();
        Aggregate.Timer normal = Aggregate.Timer.newBuilder()
                .setName("jdbc query")
                .setTotalNanos(1_000_000)
                .setCount(2)
                .build();
        Aggregate.Timer root = Aggregate.Timer.newBuilder()
                .setName("http request")
                .setTotalNanos(10_000_000)
                .setCount(1)
                .addChildTimer(extended)
                .addChildTimer(normal)
                .build();
        OverviewAggregate aggregate = overview(1000, 10_000_000, 1, root);

        assertThat(BreakdownTimerMetrics.value(aggregate, "jdbc query", Kind.COUNT))
                .isEqualTo(2);
        // Inclusive still sums both nodes (matches breakdown table totalNanos).
        assertThat(BreakdownTimerMetrics.value(aggregate, "jdbc query", Kind.INCLUSIVE_NANOS))
                .isEqualTo(10_000_000);
    }

    @Test
    public void missingTimerReturnsZero() {
        Aggregate.Timer leaf = Aggregate.Timer.newBuilder()
                .setName("logging")
                .setTotalNanos(1_000_000)
                .setCount(1)
                .build();
        Aggregate.Timer root = Aggregate.Timer.newBuilder()
                .setName("http request")
                .setTotalNanos(1_000_000)
                .setCount(1)
                .addChildTimer(leaf)
                .build();
        OverviewAggregate aggregate = overview(1000, 1_000_000, 1, root);

        assertThat(BreakdownTimerMetrics.value(aggregate, "jdbc query", Kind.INCLUSIVE_NANOS))
                .isEqualTo(0);
        assertThat(BreakdownTimerMetrics.value(aggregate, "jdbc query", Kind.COUNT))
                .isEqualTo(0);
    }

    @Test
    public void timerNamesAreSortedUnique() {
        Aggregate.Timer leaf = Aggregate.Timer.newBuilder()
                .setName("jdbc query")
                .setTotalNanos(1_000_000)
                .setCount(1)
                .build();
        Aggregate.Timer root = Aggregate.Timer.newBuilder()
                .setName("http request")
                .setTotalNanos(1_000_000)
                .setCount(1)
                .addChildTimer(leaf)
                .build();
        OverviewAggregate aggregate = overview(1000, 1_000_000, 1, root);

        List<String> names = BreakdownTimerMetrics.timerNames(aggregate);
        assertThat(names).containsExactly("http request", "jdbc query");
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
