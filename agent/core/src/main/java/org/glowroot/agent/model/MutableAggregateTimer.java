/*
 * Copyright 2014-2018 the original author or authors.
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

import java.util.List;

import com.google.common.collect.Lists;

import org.glowroot.agent.impl.TimerNameCache;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;

public class MutableAggregateTimer implements AggregatedTimer {

    private final String name;
    private final boolean extended;
    // aggregates use double instead of long to avoid (unlikely) 292 year nanosecond rollover
    private double totalDurationNanos;
    private long count;
    private final List<MutableAggregateTimer> childTimers;

    public static MutableAggregateTimer createAuxThreadRootTimer() {
        return new MutableAggregateTimer(TimerNameCache.AUXILIARY_THREAD_ROOT_TIMER_NAME, false);
    }

    public MutableAggregateTimer(String name, boolean extended) {
        this.name = name;
        this.extended = extended;
        this.childTimers = Lists.newArrayList();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isExtended() {
        return extended;
    }

    @Override
    public List<MutableAggregateTimer> getChildTimers() {
        return childTimers;
    }

    @Override
    public AggregatedTimer newChildTimer(String name, boolean extended) {
        MutableAggregateTimer childTimer = new MutableAggregateTimer(name, extended);
        childTimers.add(childTimer);
        return childTimer;
    }

    @Override
    public void addDataFrom(TransactionTimer timer) {
        count += timer.getCount();
        totalDurationNanos += timer.getTotalNanos();
        timer.mergeChildTimersInto(this);
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
