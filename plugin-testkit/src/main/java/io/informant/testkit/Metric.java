/**
 * Copyright 2013 the original author or authors.
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
package io.informant.testkit;

import checkers.igj.quals.Immutable;
import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.Objects;
import com.google.common.collect.Ordering;
import com.google.common.primitives.Longs;

import static io.informant.common.Nullness.assertNonNull;
import static io.informant.container.common.ObjectMappers.checkRequiredProperty;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class Metric {

    static final Ordering<Metric> orderingByTotal = new Ordering<Metric>() {
        @Override
        public int compare(@Nullable Metric left, @Nullable Metric right) {
            assertNonNull(left, "Ordering of non-null elements only");
            assertNonNull(right, "Ordering of non-null elements only");
            return Longs.compare(left.total, right.total);
        }
    };

    private final String name;
    private final long total;
    private final long min;
    private final long max;
    private final long count;
    private final boolean active;
    private final boolean minActive;
    private final boolean maxActive;

    private Metric(String name, long total, long min, long max, long count, boolean active,
            boolean minActive, boolean maxActive) {
        this.name = name;
        this.total = total;
        this.min = min;
        this.max = max;
        this.count = count;
        this.active = active;
        this.minActive = minActive;
        this.maxActive = maxActive;
    }

    public String getName() {
        return name;
    }

    public long getTotal() {
        return total;
    }

    public long getMin() {
        return min;
    }

    public long getMax() {
        return max;
    }

    public long getCount() {
        return count;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isMinActive() {
        return minActive;
    }

    public boolean isMaxActive() {
        return maxActive;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("name", name)
                .add("total", total)
                .add("min", min)
                .add("max", max)
                .add("count", count)
                .add("active", active)
                .add("minActive", minActive)
                .add("maxActive", maxActive)
                .toString();
    }

    @JsonCreator
    static Metric readValue(
            @JsonProperty("name") @Nullable String name,
            @JsonProperty("total") @Nullable Long total,
            @JsonProperty("min") @Nullable Long min,
            @JsonProperty("max") @Nullable Long max,
            @JsonProperty("count") @Nullable Long count,
            @JsonProperty("active") @Nullable Boolean active,
            @JsonProperty("minActive") @Nullable Boolean minActive,
            @JsonProperty("maxActive") @Nullable Boolean maxActive)
            throws JsonMappingException {
        checkRequiredProperty(name, "name");
        checkRequiredProperty(total, "total");
        checkRequiredProperty(min, "min");
        checkRequiredProperty(max, "max");
        checkRequiredProperty(count, "count");
        checkRequiredProperty(active, "active");
        checkRequiredProperty(minActive, "minActive");
        checkRequiredProperty(maxActive, "maxActive");
        return new Metric(name, total, min, max, count, active, minActive, maxActive);
    }
}
