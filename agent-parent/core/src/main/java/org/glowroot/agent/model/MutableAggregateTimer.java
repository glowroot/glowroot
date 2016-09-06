/*
 * Copyright 2014-2016 the original author or authors.
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
package org.glowroot.agent.model;

import java.util.ArrayList;
import java.util.List;

import com.google.common.collect.Lists;

import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;

public class MutableAggregateTimer {

    private final String name;
    private final boolean extended;
    // aggregates use double instead of long to avoid (unlikely) 292 year nanosecond rollover
    private double totalDurationNanos;
    private long count;
    private final List<MutableAggregateTimer> childTimers;

    public static MutableAggregateTimer createRootTimer(String name, boolean extended) {
        return new MutableAggregateTimer(name, extended, 0, 0,
                new ArrayList<MutableAggregateTimer>());
    }

    public MutableAggregateTimer(String name, boolean extended, double totalDurationNanos,
            long count, List<MutableAggregateTimer> nestedTimers) {
        this.name = name;
        this.extended = extended;
        this.totalDurationNanos = totalDurationNanos;
        this.count = count;
        this.childTimers = Lists.newArrayList(nestedTimers);
    }

    public String getName() {
        return name;
    }

    public boolean isExtended() {
        return extended;
    }

    public void merge(CommonTimerImpl timer) {
        count += timer.getCount();
        totalDurationNanos += timer.getTotalNanos();
        timer.mergeChildTimersInto2(childTimers);
    }

    public Aggregate.Timer toProto() {
        Aggregate.Timer.Builder builder = Aggregate.Timer.newBuilder()
                .setName(name)
                .setExtended(extended)
                .setTotalNanos(totalDurationNanos)
                .setCount(count);
        for (MutableAggregateTimer childTimer : childTimers) {
            builder.addChildTimer(childTimer.toProto());
        }
        return builder.build();
    }
}
