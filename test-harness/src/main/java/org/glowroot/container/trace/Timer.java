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
    private final boolean extended;
    private final long total;
    private final long count;
    private final boolean active;

    private final ImmutableList<Timer> nestedTimers;

    private Timer(String name, boolean extended, long total, long count, boolean active,
            List<Timer> nestedTimers) {
        this.name = name;
        this.extended = extended;
        this.total = total;
        this.count = count;
        this.active = active;
        this.nestedTimers = ImmutableList.copyOf(nestedTimers);
    }

    public String getName() {
        return name;
    }

    public boolean isExtended() {
        return extended;
    }

    public long getTotal() {
        return total;
    }

    public long getCount() {
        return count;
    }

    public boolean isActive() {
        return active;
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
                .add("extended", extended)
                .add("total", total)
                .add("count", count)
                .add("active", active)
                .add("nestedTimers", nestedTimers)
                .toString();
    }

    @JsonCreator
    static Timer readValue(
            @JsonProperty("name") @Nullable String name,
            @JsonProperty("extended") @Nullable Boolean extended,
            @JsonProperty("total") @Nullable Long total,
            @JsonProperty("count") @Nullable Long count,
            @JsonProperty("active") @Nullable Boolean active,
            @JsonProperty("nestedTimers") @Nullable List</*@Nullable*/Timer> uncheckedNestedTimers)
                    throws JsonMappingException {
        List<Timer> nestedTimers = orEmpty(uncheckedNestedTimers, "nestedTimers");
        checkRequiredProperty(name, "name");
        checkRequiredProperty(total, "total");
        checkRequiredProperty(count, "count");
        return new Timer(name, orFalse(extended), total, count, orFalse(active), nestedTimers);
    }

    private static boolean orFalse(@Nullable Boolean value) {
        return value != null && value;
    }
}
