/*
 * Copyright 2013-2014 the original author or authors.
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

import java.util.List;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.collect.TreeTraverser;

import static org.glowroot.common.ObjectMappers.checkRequiredProperty;
import static org.glowroot.common.ObjectMappers.nullToEmpty;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class Aggregate {

    private final long captureTime;
    // aggregation uses microseconds to avoid (unlikely) 292 year nanosecond rollover
    private final long totalMicros;
    private final long count;
    private final long errorCount;
    private final long storedTraceCount;
    private final AggregateMetric syntheticRootAggregateMetric;

    Aggregate(long captureTime, long totalMicros, long count, long errorCount,
            long storedTraceCount, AggregateMetric syntheticRootAggregateMetric) {
        this.captureTime = captureTime;
        this.totalMicros = totalMicros;
        this.count = count;
        this.errorCount = errorCount;
        this.storedTraceCount = storedTraceCount;
        this.syntheticRootAggregateMetric = syntheticRootAggregateMetric;
    }

    public long getCaptureTime() {
        return captureTime;
    }

    public long getTotalMicros() {
        return totalMicros;
    }

    public long getCount() {
        return count;
    }

    public long getErrorCount() {
        return errorCount;
    }

    public long getStoredTraceCount() {
        return storedTraceCount;
    }

    public AggregateMetric getSyntheticRootAggregateMetric() {
        return syntheticRootAggregateMetric;
    }

    public static class AggregateMetric {

        public static final TreeTraverser<AggregateMetric> TRAVERSER =
                new TreeTraverser<AggregateMetric>() {
                    @Override
                    public Iterable<AggregateMetric> children(AggregateMetric root) {
                        return root.getNestedMetrics();
                    }
                };

        private final String name;
        // aggregation uses microseconds to avoid (unlikely) 292 year nanosecond rollover
        private final long totalMicros;
        private final long count;
        private final List<AggregateMetric> nestedMetrics;

        private AggregateMetric(String name, long totalMicros, long count,
                List<AggregateMetric> nestedMetrics) {
            this.name = name;
            this.totalMicros = totalMicros;
            this.count = count;
            this.nestedMetrics = nestedMetrics;
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
                @JsonProperty("nestedMetrics") @Nullable List<AggregateMetric> nestedMetrics)
                throws JsonMappingException {
            checkRequiredProperty(name, "name");
            checkRequiredProperty(totalMicros, "totalMicros");
            checkRequiredProperty(count, "count");
            return new AggregateMetric(name, totalMicros, count, nullToEmpty(nestedMetrics));
        }
    }
}
