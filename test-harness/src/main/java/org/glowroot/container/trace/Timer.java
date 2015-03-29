/*
 * Copyright 2013-2015 the original author or authors.
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
package org.glowroot.container.trace;

import java.util.List;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.primitives.Longs;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.glowroot.container.common.ObjectMappers.checkRequiredProperty;
import static org.glowroot.container.common.ObjectMappers.orEmpty;

public class Timer {

    private static final Ordering<Timer> orderingByTotal = new Ordering<Timer>() {
        @Override
        public int compare(@Nullable Timer left, @Nullable Timer right) {
            checkNotNull(left);
            checkNotNull(right);
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

    private final ImmutableList<Timer> nestedTimers;

    private Timer(String name, long total, long min, long max, long count, boolean active,
            boolean minActive, boolean maxActive, List<Timer> nestedTimers) {
        this.name = name;
        this.total = total;
        this.min = min;
        this.max = max;
        this.count = count;
        this.active = active;
        this.minActive = minActive;
        this.maxActive = maxActive;
        this.nestedTimers = ImmutableList.copyOf(nestedTimers);
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

    public ImmutableList<Timer> getNestedTimers() {
        return ImmutableList.copyOf(Timer.orderingByTotal.reverse().sortedCopy(nestedTimers));
    }

    @JsonIgnore
    public List<String> getNestedTimerNames() {
        List<String> stableNestedTimers = Lists.newArrayList();
        for (Timer stableNestedTimer : getNestedTimers()) {
            stableNestedTimers.add(stableNestedTimer.getName());
        }
        return stableNestedTimers;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("name", name)
                .add("total", total)
                .add("min", min)
                .add("max", max)
                .add("count", count)
                .add("active", active)
                .add("minActive", minActive)
                .add("maxActive", maxActive)
                .add("nestedTimers", nestedTimers)
                .toString();
    }

    @JsonCreator
    static Timer readValue(
            @JsonProperty("name") @Nullable String name,
            @JsonProperty("total") @Nullable Long total,
            @JsonProperty("min") @Nullable Long min,
            @JsonProperty("max") @Nullable Long max,
            @JsonProperty("count") @Nullable Long count,
            @JsonProperty("active") @Nullable Boolean active,
            @JsonProperty("minActive") @Nullable Boolean minActive,
            @JsonProperty("maxActive") @Nullable Boolean maxActive,
            @JsonProperty("nestedTimers") @Nullable List</*@Nullable*/Timer> uncheckedNestedTimers)
            throws JsonMappingException {
        List<Timer> nestedTimers = orEmpty(uncheckedNestedTimers, "nestedTimers");
        checkRequiredProperty(name, "name");
        checkRequiredProperty(total, "total");
        checkRequiredProperty(min, "min");
        checkRequiredProperty(max, "max");
        checkRequiredProperty(count, "count");
        checkRequiredProperty(active, "active");
        checkRequiredProperty(minActive, "minActive");
        checkRequiredProperty(maxActive, "maxActive");
        return new Timer(name, total, min, max, count, active, minActive, maxActive,
                nestedTimers);
    }
}
