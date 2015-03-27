/*
 * Copyright 2014-2015 the original author or authors.
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
package org.glowroot.local.store;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.collect.Lists;

import org.glowroot.markers.UsedByJsonBinding;

import static org.glowroot.common.ObjectMappers.checkRequiredProperty;
import static org.glowroot.common.ObjectMappers.orEmpty;

@UsedByJsonBinding
public class AggregateTimer {

    private final String name;
    // aggregation uses microseconds to avoid (unlikely) 292 year nanosecond rollover
    private long totalMicros;
    private long count;
    private final List<AggregateTimer> nestedTimers;

    public static AggregateTimer createSyntheticRootTimer() {
        return new AggregateTimer("<multiple root nodes>", 0, 0, new ArrayList<AggregateTimer>());
    }

    private AggregateTimer(String name, long totalMicros, long count,
            List<AggregateTimer> nestedTimers) {
        this.name = name;
        this.totalMicros = totalMicros;
        this.count = count;
        this.nestedTimers = Lists.newArrayList(nestedTimers);
    }

    public void mergeMatchedTimer(AggregateTimer aggregateTimer) {
        count += aggregateTimer.getCount();
        totalMicros += aggregateTimer.getTotalMicros();
        for (AggregateTimer toBeMergedNestedTimer : aggregateTimer.getNestedTimers()) {
            // for each to-be-merged child node look for a match
            AggregateTimer foundMatchingChildTimer = null;
            for (AggregateTimer childTimer : nestedTimers) {
                if (toBeMergedNestedTimer.getName().equals(childTimer.getName())) {
                    foundMatchingChildTimer = childTimer;
                    break;
                }
            }
            if (foundMatchingChildTimer == null) {
                nestedTimers.add(toBeMergedNestedTimer);
            } else {
                foundMatchingChildTimer.mergeMatchedTimer(toBeMergedNestedTimer);
            }
        }
    }

    public String getName() {
        return name;
    }

    public long getTotalMicros() {
        return totalMicros;
    }

    public long getCount() {
        return count;
    }

    public List<AggregateTimer> getNestedTimers() {
        return nestedTimers;
    }

    @JsonCreator
    static AggregateTimer readValue(
            @JsonProperty("name") @Nullable String name,
            @JsonProperty("totalMicros") @Nullable Long totalMicros,
            @JsonProperty("count") @Nullable Long count,
            @JsonProperty("nestedTimers") @Nullable List</*@Nullable*/AggregateTimer> uncheckedNestedTimers)
            throws JsonMappingException {
        List<AggregateTimer> nestedTimers = orEmpty(uncheckedNestedTimers, "nestedTimers");
        checkRequiredProperty(name, "name");
        checkRequiredProperty(totalMicros, "totalMicros");
        checkRequiredProperty(count, "count");
        return new AggregateTimer(name, totalMicros, count, nestedTimers);
    }
}
