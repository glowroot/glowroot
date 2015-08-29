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

public class TimerNode {

    private static final Ordering<TimerNode> orderingByTotal = new Ordering<TimerNode>() {
        @Override
        public int compare(@Nullable TimerNode left, @Nullable TimerNode right) {
            checkNotNull(left);
            checkNotNull(right);
            return Longs.compare(left.totalNanos, right.totalNanos);
        }
    };

    private final String name;
    private final boolean extended;
    private final long totalNanos;
    private final long count;
    private final boolean active;

    private final ImmutableList<TimerNode> childNodes;

    private TimerNode(String name, boolean extended, long total, long count, boolean active,
            List<TimerNode> childNodes) {
        this.name = name;
        this.extended = extended;
        this.totalNanos = total;
        this.count = count;
        this.active = active;
        this.childNodes = ImmutableList.copyOf(childNodes);
    }

    public String getName() {
        return name;
    }

    public boolean isExtended() {
        return extended;
    }

    public long getTotalNanos() {
        return totalNanos;
    }

    public long getCount() {
        return count;
    }

    public boolean isActive() {
        return active;
    }

    public ImmutableList<TimerNode> getChildNodes() {
        return ImmutableList.copyOf(TimerNode.orderingByTotal.reverse().sortedCopy(childNodes));
    }

    @JsonIgnore
    public List<String> getChildTimerNames() {
        List<String> timerNames = Lists.newArrayList();
        for (TimerNode childNode : getChildNodes()) {
            timerNames.add(childNode.getName());
        }
        return timerNames;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("name", name)
                .add("extended", extended)
                .add("totalNanos", totalNanos)
                .add("count", count)
                .add("active", active)
                .add("childNodes", childNodes)
                .toString();
    }

    @JsonCreator
    static TimerNode readValue(
            @JsonProperty("name") @Nullable String name,
            @JsonProperty("extended") @Nullable Boolean extended,
            @JsonProperty("totalNanos") @Nullable Long totalNanos,
            @JsonProperty("count") @Nullable Long count,
            @JsonProperty("active") @Nullable Boolean active,
            @JsonProperty("childNodes") @Nullable List</*@Nullable*/TimerNode> uncheckedChildNodes)
                    throws JsonMappingException {
        List<TimerNode> childNodes = orEmpty(uncheckedChildNodes, "childNodes");
        checkRequiredProperty(name, "name");
        checkRequiredProperty(totalNanos, "totalNanos");
        checkRequiredProperty(count, "count");
        return new TimerNode(name, orFalse(extended), totalNanos, count, orFalse(active),
                childNodes);
    }

    private static boolean orFalse(@Nullable Boolean value) {
        return value != null && value;
    }
}
