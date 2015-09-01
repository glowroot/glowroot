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
package org.glowroot.common.model;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.collect.Lists;

import org.glowroot.collector.spi.AggregateTimerNode;
import org.glowroot.collector.spi.TraceTimerNode;
import org.glowroot.markers.UsedByJsonBinding;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.glowroot.common.util.ObjectMappers.checkRequiredProperty;
import static org.glowroot.common.util.ObjectMappers.orEmpty;

@UsedByJsonBinding
public class MutableTimerNode implements AggregateTimerNode {

    // only null for synthetic root timer
    private final @Nullable String name;
    private final boolean extended;
    // aggregates use double instead of long to avoid (unlikely) 292 year nanosecond rollover
    private double totalNanos;
    private long count;
    private final List<MutableTimerNode> childNodes;

    public static MutableTimerNode createSyntheticRootNode() {
        return new MutableTimerNode(null, false, 0, 0, new ArrayList<MutableTimerNode>());
    }

    private MutableTimerNode(@Nullable String name, boolean extended, double totalNanos, long count,
            List<MutableTimerNode> nestedTimers) {
        this.name = name;
        this.extended = extended;
        this.totalNanos = totalNanos;
        this.count = count;
        this.childNodes = Lists.newArrayList(nestedTimers);
    }

    public void mergeMatchedTimer(MutableTimerNode timerNode) {
        count += timerNode.count();
        totalNanos += timerNode.totalNanos();
        for (MutableTimerNode toBeMergedChildNode : timerNode.childNodes()) {
            // for each to-be-merged nested node look for a match
            MutableTimerNode foundMatchingNestedTimer = null;
            for (MutableTimerNode nestedTimer : childNodes) {
                // timer names are only null for synthetic root timer
                String toBeMergedNestedTimerName = checkNotNull(toBeMergedChildNode.name());
                String nestedTimerName = checkNotNull(nestedTimer.name());
                if (toBeMergedNestedTimerName.equals(nestedTimerName)
                        && toBeMergedChildNode.extended() == nestedTimer.extended()) {
                    foundMatchingNestedTimer = nestedTimer;
                    break;
                }
            }
            if (foundMatchingNestedTimer == null) {
                childNodes.add(toBeMergedChildNode);
            } else {
                foundMatchingNestedTimer.mergeMatchedTimer(toBeMergedChildNode);
            }
        }
    }

    @Override
    @JsonProperty("name")
    public @Nullable String name() {
        return name;
    }

    @Override
    @JsonProperty("extended")
    public boolean extended() {
        return extended;
    }

    @Override
    @JsonProperty("totalNanos")
    public double totalNanos() {
        return totalNanos;
    }

    @Override
    @JsonProperty("count")
    public long count() {
        return count;
    }

    @Override
    @JsonProperty("childNodes")
    public List<MutableTimerNode> childNodes() {
        return childNodes;
    }

    public void mergeAsChildTimer(TraceTimerNode timerNode) {
        // timer names are only null for synthetic root node
        String timerName = checkNotNull(timerNode.name());
        boolean extended = timerNode.extended();
        MutableTimerNode matchingTimerNode = null;
        for (MutableTimerNode childNode : childNodes) {
            // timer names are only null for synthetic root node
            String nestedTimerName = checkNotNull(childNode.name());
            if (timerName.equals(nestedTimerName) && extended == childNode.extended()) {
                matchingTimerNode = childNode;
                break;
            }
        }
        if (matchingTimerNode == null) {
            matchingTimerNode = new MutableTimerNode(timerName, timerNode.extended(), 0, 0,
                    new ArrayList<MutableTimerNode>());
            childNodes.add(matchingTimerNode);
        }
        if (name == null) {
            // special case for synthetic root node
            totalNanos += timerNode.totalNanos();
            count += timerNode.count();
        }
        matchingTimerNode.totalNanos += timerNode.totalNanos();
        matchingTimerNode.count += timerNode.count();
        for (TraceTimerNode childNode : timerNode.childNodes()) {
            matchingTimerNode.mergeAsChildTimer(childNode);
        }
    }

    @JsonCreator
    static MutableTimerNode readValue(@JsonProperty("name") @Nullable String name,
            @JsonProperty("extended") @Nullable Boolean extended,
            @JsonProperty("totalNanos") @Nullable Double totalNanos,
            @JsonProperty("count") @Nullable Long count,
            @JsonProperty("nestedTimers") @Nullable List</*@Nullable*/MutableTimerNode> uncheckedNestedTimers)
                    throws JsonMappingException {
        List<MutableTimerNode> nestedTimers = orEmpty(uncheckedNestedTimers, "nestedTimers");
        checkRequiredProperty(totalNanos, "totalNanos");
        checkRequiredProperty(count, "count");
        return new MutableTimerNode(name, orFalse(extended), totalNanos, count, nestedTimers);
    }

    private static boolean orFalse(@Nullable Boolean value) {
        return value != null && value;
    }
}
