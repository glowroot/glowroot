/*
 * Copyright 2017 the original author or authors.
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

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Ordering;
import com.google.common.primitives.Longs;
import org.immutables.value.Value;

import org.glowroot.common.model.SyntheticResult.ErrorInterval;
import org.glowroot.ui.MultiErrorIntervalCollector.MultiErrorInterval;

class MultiErrorIntervalMerger {

    private final List<GroupedMultiErrorInterval> groupedMultiErrorIntervals =
            Lists.newArrayList();

    public void addMultiErrorIntervals(String syntheticMonitorId,
            List<MultiErrorInterval> multiErrorIntervals) {
        for (MultiErrorInterval multiErrorInterval : multiErrorIntervals) {
            addMultiErrorInterval(syntheticMonitorId, multiErrorInterval);
        }
    }

    private void addMultiErrorInterval(String syntheticMonitorId,
            MultiErrorInterval multiErrorInterval) {
        List<GroupedMultiErrorInterval> overlapping = Lists.newArrayList();
        for (Iterator<GroupedMultiErrorInterval> i = groupedMultiErrorIntervals.iterator(); i
                .hasNext();) {
            GroupedMultiErrorInterval groupedMultiErrorInterval = i.next();
            if (isOverlapping(multiErrorInterval, groupedMultiErrorInterval)) {
                overlapping.add(groupedMultiErrorInterval);
                i.remove();
            }
        }
        if (overlapping.isEmpty()) {
            groupedMultiErrorIntervals.add(ImmutableGroupedMultiErrorInterval.builder()
                    .from(multiErrorInterval.from())
                    .to(multiErrorInterval.to())
                    .putErrorIntervals(syntheticMonitorId, multiErrorInterval.errorIntervals())
                    .build());
        } else {
            groupedMultiErrorIntervals.removeAll(overlapping);
            groupedMultiErrorIntervals
                    .add(mergeWithOverlapping(syntheticMonitorId, multiErrorInterval, overlapping));
        }
    }

    public List<GroupedMultiErrorInterval> getGroupedMultiErrorIntervals() {
        return new GroupedMultiErrorIntervalOrdering().sortedCopy(groupedMultiErrorIntervals);
    }

    private static boolean isOverlapping(MultiErrorInterval multiErrorInterval,
            GroupedMultiErrorInterval groupedMultiErrorInterval) {
        return multiErrorInterval.from() <= groupedMultiErrorInterval.to()
                && groupedMultiErrorInterval.from() <= multiErrorInterval.to();
    }

    private static GroupedMultiErrorInterval mergeWithOverlapping(String syntheticMonitorId,
            MultiErrorInterval multiErrorInterval, List<GroupedMultiErrorInterval> overlapping) {
        long minFrom = multiErrorInterval.from();
        long maxTo = multiErrorInterval.to();
        ListMultimap<String, ErrorInterval> errorIntervals = ArrayListMultimap.create();
        errorIntervals.putAll(syntheticMonitorId, multiErrorInterval.errorIntervals());
        for (GroupedMultiErrorInterval groupedMultiErrorInterval : overlapping) {
            minFrom = Math.min(minFrom, groupedMultiErrorInterval.from());
            maxTo = Math.max(maxTo, groupedMultiErrorInterval.to());
            for (Map.Entry<String, List<ErrorInterval>> entry : groupedMultiErrorInterval
                    .errorIntervals().entrySet()) {
                errorIntervals.putAll(entry.getKey(), entry.getValue());
            }
        }
        return ImmutableGroupedMultiErrorInterval.builder()
                .from(minFrom)
                .to(maxTo)
                .putAllErrorIntervals(Multimaps.asMap(errorIntervals))
                .build();
    }

    @Value.Immutable
    public interface GroupedMultiErrorInterval {

        long from();
        long to();
        Map<String, List<ErrorInterval>> errorIntervals(); // key is synetheticMonitorId
    }

    private static class GroupedMultiErrorIntervalOrdering
            extends Ordering<GroupedMultiErrorInterval> {
        @Override
        public int compare(GroupedMultiErrorInterval left, GroupedMultiErrorInterval right) {
            return Longs.compare(left.from(), right.from());
        }
    }
}
