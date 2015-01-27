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

import static org.glowroot.local.store.ObjectMappers.checkRequiredProperty;
import static org.glowroot.local.store.ObjectMappers.orEmpty;

@UsedByJsonBinding
public class AggregateMetric {

    private final String name;
    // aggregation uses microseconds to avoid (unlikely) 292 year nanosecond rollover
    private long totalMicros;
    private long count;
    private final List<AggregateMetric> nestedMetrics;

    public static AggregateMetric createSyntheticRootMetric() {
        return new AggregateMetric("<multiple root nodes>", 0, 0, new ArrayList<AggregateMetric>());
    }

    private AggregateMetric(String name, long totalMicros, long count,
            List<AggregateMetric> nestedMetrics) {
        this.name = name;
        this.totalMicros = totalMicros;
        this.count = count;
        this.nestedMetrics = Lists.newArrayList(nestedMetrics);
    }

    public void mergeMatchedMetric(AggregateMetric aggregateMetric) {
        count += aggregateMetric.getCount();
        totalMicros += aggregateMetric.getTotalMicros();
        for (AggregateMetric toBeMergedNestedMetric : aggregateMetric.getNestedMetrics()) {
            // for each to-be-merged child node look for a match
            AggregateMetric foundMatchingChildMetric = null;
            for (AggregateMetric childMetric : nestedMetrics) {
                if (toBeMergedNestedMetric.getName().equals(childMetric.getName())) {
                    foundMatchingChildMetric = childMetric;
                    break;
                }
            }
            if (foundMatchingChildMetric == null) {
                nestedMetrics.add(toBeMergedNestedMetric);
            } else {
                foundMatchingChildMetric.mergeMatchedMetric(toBeMergedNestedMetric);
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

    public List<AggregateMetric> getNestedMetrics() {
        return nestedMetrics;
    }

    @JsonCreator
    static AggregateMetric readValue(
            @JsonProperty("name") @Nullable String name,
            @JsonProperty("totalMicros") @Nullable Long totalMicros,
            @JsonProperty("count") @Nullable Long count,
            @JsonProperty("nestedMetrics") @Nullable List</*@Nullable*/AggregateMetric> uncheckedNestedMetrics)
            throws JsonMappingException {
        List<AggregateMetric> nestedMetrics = orEmpty(uncheckedNestedMetrics, "nestedMetrics");
        checkRequiredProperty(name, "name");
        checkRequiredProperty(totalMicros, "totalMicros");
        checkRequiredProperty(count, "count");
        return new AggregateMetric(name, totalMicros, count, nestedMetrics);
    }
}
