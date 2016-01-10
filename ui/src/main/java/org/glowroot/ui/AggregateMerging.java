/*
 * Copyright 2015-2016 the original author or authors.
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

import javax.annotation.Nullable;

import com.google.common.collect.Lists;
import org.immutables.value.Value;

import org.glowroot.common.util.Styles;
import org.glowroot.storage.repo.AggregateRepository.OverviewAggregate;
import org.glowroot.storage.repo.MutableThreadStats;
import org.glowroot.storage.repo.MutableTimer;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate.ThreadStats;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate.Timer;

class AggregateMerging {

    private AggregateMerging() {}

    static MergedAggregate getMergedAggregate(List<OverviewAggregate> overviewAggregates)
            throws Exception {
        long transactionCount = 0;
        List<MutableTimer> mainThreadRootTimers = Lists.newArrayList();
        List<MutableTimer> auxThreadRootTimers = Lists.newArrayList();
        List<MutableTimer> asyncRootTimers = Lists.newArrayList();
        MutableThreadStats mainThreadStats = new MutableThreadStats();
        MutableThreadStats auxThreadStats = new MutableThreadStats();
        for (OverviewAggregate aggregate : overviewAggregates) {
            transactionCount += aggregate.transactionCount();
            mergeRootTimers(aggregate.mainThreadRootTimers(), mainThreadRootTimers);
            mergeRootTimers(aggregate.auxThreadRootTimers(), auxThreadRootTimers);
            mergeRootTimers(aggregate.asyncRootTimers(), asyncRootTimers);
            ThreadStats threadStats = aggregate.mainThreadStats();
            if (threadStats != null) {
                mainThreadStats.addThreadStats(threadStats);
            }
            threadStats = aggregate.auxThreadStats();
            if (threadStats != null) {
                auxThreadStats.addThreadStats(threadStats);
            }
        }
        ImmutableMergedAggregate.Builder mergedAggregate = ImmutableMergedAggregate.builder();
        mergedAggregate.transactionCount(transactionCount);
        mergedAggregate.mainThreadRootTimers(mainThreadRootTimers);
        mergedAggregate.auxThreadRootTimers(auxThreadRootTimers);
        mergedAggregate.asyncRootTimers(asyncRootTimers);
        if (!mainThreadStats.isEmpty()) {
            mergedAggregate.mainThreadStats(mainThreadStats);
        }
        if (!auxThreadStats.isEmpty()) {
            mergedAggregate.auxThreadStats(auxThreadStats);
        }
        return mergedAggregate.build();
    }

    private static void mergeRootTimers(List<Aggregate.Timer> toBeMergedRootTimers,
            List<MutableTimer> rootTimers) {
        for (Aggregate.Timer toBeMergedRootTimer : toBeMergedRootTimers) {
            mergeRootTimer(toBeMergedRootTimer, rootTimers);
        }
    }

    private static void mergeRootTimer(Timer toBeMergedRootTimer, List<MutableTimer> rootTimers) {
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
        List<MutableTimer> auxThreadRootTimers();
        List<MutableTimer> asyncRootTimers();
        @Nullable
        MutableThreadStats mainThreadStats();
        @Nullable
        MutableThreadStats auxThreadStats();
    }

    @Value.Immutable
    @Styles.AllParameters
    interface PercentileValue {
        String dataSeriesName();
        long value();
    }
}
