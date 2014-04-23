/*
 * Copyright 2014 the original author or authors.
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
package org.glowroot.local.ui;

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
class TransactionMetric {

    static final TreeTraverser<TransactionMetric> TRAVERSER =
            new TreeTraverser<TransactionMetric>() {
                @Override
                public Iterable<TransactionMetric> children(TransactionMetric root) {
                    return root.getNestedMetrics();
                }
            };

    private final String name;
    // aggregation uses microseconds to avoid (unlikely) 292 year nanosecond rollover
    private final long totalMicros;
    private final long count;
    private final List<TransactionMetric> nestedMetrics;

    private TransactionMetric(String name, long totalMicros, long count,
            List<TransactionMetric> nestedMetrics) {
        this.name = name;
        this.totalMicros = totalMicros;
        this.count = count;
        this.nestedMetrics = nestedMetrics;
    }

    String getName() {
        return name;
    }

    long getTotalMicros() {
        return totalMicros;
    }

    // TODO this is currently unused
    long getCount() {
        return count;
    }

    List<TransactionMetric> getNestedMetrics() {
        return nestedMetrics;
    }

    @JsonCreator
    static TransactionMetric readValue(
            @JsonProperty("name") @Nullable String name,
            @JsonProperty("totalMicros") @Nullable Long totalMicros,
            @JsonProperty("count") @Nullable Long count,
            @JsonProperty("nestedMetrics") @Nullable List<TransactionMetric> nestedMetrics)
            throws JsonMappingException {
        checkRequiredProperty(name, "name");
        checkRequiredProperty(totalMicros, "totalMicros");
        checkRequiredProperty(count, "count");
        return new TransactionMetric(name, totalMicros, count, nullToEmpty(nestedMetrics));
    }
}
