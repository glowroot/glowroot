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

import java.util.HashMap;
import java.util.Map;

import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;

/**
 * Builds stacked (leaf / self-time) timer totals used by the transaction average chart.
 *
 * <p>Self time for a timer is {@code totalNanos - sum(child.totalNanos)}. Chart series use these
 * exclusive values so stacked segments add up to average response time. The breakdown table uses
 * inclusive {@code totalNanos} instead, which is why parent timer rows often look larger than their
 * chart segments (see issue #1158).
 */
class StackedTimerTotals {

    private StackedTimerTotals() {}

    static Map<String, Double> create(OverviewAggregate overviewAggregate) {
        Map<String, Double> stackedTimers = new HashMap<String, Double>();
        for (Aggregate.Timer rootTimer : overviewAggregate.mainThreadRootTimers()) {
            // skip root timers — root self-time becomes the chart "other" bucket
            for (Aggregate.Timer topLevelTimer : rootTimer.getChildTimerList()) {
                addToStackedTimer(topLevelTimer, stackedTimers);
            }
        }
        return stackedTimers;
    }

    static double selfNanos(Aggregate.Timer timer) {
        double totalNestedNanos = 0;
        for (Aggregate.Timer childTimer : timer.getChildTimerList()) {
            totalNestedNanos += childTimer.getTotalNanos();
        }
        return timer.getTotalNanos() - totalNestedNanos;
    }

    /**
     * Whether an aggregate capture time belongs in the merged breakdown for the selected chart
     * range (same filter as {@link TransactionJsonService#getOverview}).
     */
    static boolean captureTimeInMergedRange(long captureTime, long from, long to) {
        return captureTime > from && captureTime <= to;
    }

    private static void addToStackedTimer(Aggregate.Timer timer, Map<String, Double> stackedTimers) {
        for (Aggregate.Timer childTimer : timer.getChildTimerList()) {
            addToStackedTimer(childTimer, stackedTimers);
        }
        String timerName = timer.getName();
        Double existing = stackedTimers.get(timerName);
        double leafNanos = selfNanos(timer);
        if (existing == null) {
            stackedTimers.put(timerName, leafNanos);
        } else {
            stackedTimers.put(timerName, existing + leafNanos);
        }
    }
}
