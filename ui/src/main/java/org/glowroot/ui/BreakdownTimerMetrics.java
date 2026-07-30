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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.glowroot.common.live.LiveAggregateRepository.OverviewAggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;

/**
 * Flattens overview aggregate timer trees the same way Transactions → average breakdown does
 * ({@code average.js} traverse / selfNanos), so ad hoc report metrics can target a timer by name.
 */
class BreakdownTimerMetrics {

    enum Kind {
        INCLUSIVE_NANOS, EXCLUSIVE_NANOS, COUNT
    }

    private BreakdownTimerMetrics() {}

    static double value(OverviewAggregate aggregate, String timerName, Kind kind) {
        Double value = flatten(aggregate).get(kind).get(timerName);
        return value == null ? 0 : value;
    }

    static List<String> timerNames(OverviewAggregate aggregate) {
        List<String> sorted =
                new ArrayList<String>(flatten(aggregate).get(Kind.INCLUSIVE_NANOS).keySet());
        Collections.sort(sorted);
        return sorted;
    }

    private static Map<Kind, Map<String, Double>> flatten(OverviewAggregate aggregate) {
        Map<String, Double> inclusive = new HashMap<String, Double>();
        Map<String, Double> exclusive = new HashMap<String, Double>();
        Map<String, Double> counts = new HashMap<String, Double>();
        for (Aggregate.Timer rootTimer : aggregate.mainThreadRootTimers()) {
            traverse(rootTimer, new HashSet<String>(), inclusive, exclusive, counts);
        }
        Map<Kind, Map<String, Double>> byKind = new HashMap<Kind, Map<String, Double>>();
        byKind.put(Kind.INCLUSIVE_NANOS, inclusive);
        byKind.put(Kind.EXCLUSIVE_NANOS, exclusive);
        byKind.put(Kind.COUNT, counts);
        return byKind;
    }

    private static void traverse(Aggregate.Timer timer, Set<String> parentTimerNames,
            Map<String, Double> inclusive, Map<String, Double> exclusive,
            Map<String, Double> counts) {
        String name = timer.getName();
        if (!parentTimerNames.contains(name)) {
            // only add when this timer name isn't appearing under itself via another branch
            // (same guard as average.js flatten)
            add(inclusive, name, timer.getTotalNanos());
            add(exclusive, name, StackedTimerTotals.selfNanos(timer));
            if (!timer.getExtended()) {
                // extended nodes carry synthetic counts — skip for timer-count (average.js)
                add(counts, name, timer.getCount());
            }
        }
        Set<String> childParents = new HashSet<String>(parentTimerNames);
        childParents.add(name);
        for (Aggregate.Timer child : timer.getChildTimerList()) {
            traverse(child, childParents, inclusive, exclusive, counts);
        }
    }

    private static void add(Map<String, Double> map, String name, double delta) {
        Double prior = map.get(name);
        map.put(name, prior == null ? delta : prior + delta);
    }
}
