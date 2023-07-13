/*
 * Copyright 2015-2023 the original author or authors.
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

import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.immutables.value.Value;

import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.common.util.Styles;
import org.glowroot.common2.repo.MutableThreadStats;
import org.glowroot.common2.repo.MutableTimer;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;

import static com.google.common.base.Preconditions.checkNotNull;

class AggregateMerging {

    private AggregateMerging() {}

    static MergedAggregate getMergedAggregate(List<OverviewAggregate> overviewAggregates) {
        long transactionCount = 0;
        List<MutableTimer> mainThreadRootTimers = Lists.newArrayList();
        MutableThreadStats mainThreadStats = new MutableThreadStats();
        MutableTimer auxThreadRootTimer = MutableTimer.createAuxThreadRootTimer();
        MutableThreadStats auxThreadStats = new MutableThreadStats();
        List<MutableTimer> asyncTimers = Lists.newArrayList();
        for (OverviewAggregate aggregate : overviewAggregates) {
            transactionCount += aggregate.transactionCount();
            mergeRootTimers(aggregate.mainThreadRootTimers(), mainThreadRootTimers);
            mainThreadStats.addThreadStats(aggregate.mainThreadStats());
            Aggregate.Timer toBeMergedAuxThreadRootTimer = aggregate.auxThreadRootTimer();
            if (toBeMergedAuxThreadRootTimer != null) {
                auxThreadRootTimer.merge(toBeMergedAuxThreadRootTimer);
                // aux thread stats is non-null when aux thread root timer is non-null
                auxThreadStats.addThreadStats(checkNotNull(aggregate.auxThreadStats()));
            }
            mergeRootTimers(aggregate.asyncTimers(), asyncTimers);
        }
        ImmutableMergedAggregate.Builder builder = ImmutableMergedAggregate.builder()
                .transactionCount(transactionCount)
                .mainThreadRootTimers(mainThreadRootTimers)
                .mainThreadStats(mainThreadStats);
        if (auxThreadRootTimer.getCount() != 0) {
            builder.auxThreadRootTimer(auxThreadRootTimer)
                    .auxThreadStats(auxThreadStats);
        }
        return builder.asyncTimers(asyncTimers)
                .build();
    }

    private static void mergeRootTimers(List<Aggregate.Timer> toBeMergedRootTimers,
            List<MutableTimer> rootTimers) {
        for (Aggregate.Timer toBeMergedRootTimer : toBeMergedRootTimers) {
            mergeRootTimer(toBeMergedRootTimer, rootTimers);
        }
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

    @Value.Immutable
    interface MergedAggregate {
        long transactionCount();
        List<MutableTimer> mainThreadRootTimers();
        MutableThreadStats mainThreadStats();
        @Nullable
        MutableTimer auxThreadRootTimer();
        @Nullable
        MutableThreadStats auxThreadStats();
        List<MutableTimer> asyncTimers();
    }

    @Value.Immutable
    @Styles.AllParameters
    interface PercentileValue {
        String dataSeriesName();
        long value();
    }
}
