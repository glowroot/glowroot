/*
 * Copyright 2015-2016 the original author or authors.
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

import org.glowroot.wire.api.model.TraceOuterClass.Trace;

public class MutableTraceTimer implements CommonTimerImpl {

    private final String name;
    private final boolean extended;
    private long totalNanos;
    private long count;
    private boolean active;
    private final List<MutableTraceTimer> childTimers;

    public static MutableTraceTimer createRootTimer(String name, boolean extended) {
        return new MutableTraceTimer(name, extended, 0, 0, new ArrayList<MutableTraceTimer>());
    }

    public MutableTraceTimer(String name, boolean extended, long totalNanos, long count,
            List<MutableTraceTimer> nestedTimers) {
        this.name = name;
        this.extended = extended;
        this.totalNanos = totalNanos;
        this.count = count;
        this.childTimers = Lists.newArrayList(nestedTimers);
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
    public long getTotalNanos() {
        return totalNanos;
    }

    @Override
    public long getCount() {
        return count;
    }

    @Override
    public void mergeChildTimersInto(List<MutableTraceTimer> childTimers) {
        for (MutableTraceTimer curr : this.childTimers) {
            String currName = curr.getName();
            boolean extended = curr.isExtended();
            MutableTraceTimer matchingChildTimer = null;
            for (MutableTraceTimer childTimer : childTimers) {
                if (currName.equals(childTimer.getName()) && extended == childTimer.isExtended()) {
                    matchingChildTimer = childTimer;
                    break;
                }
            }
            if (matchingChildTimer == null) {
                matchingChildTimer = new MutableTraceTimer(curr.getName(),
                        curr.isExtended(), 0, 0, new ArrayList<MutableTraceTimer>());
                childTimers.add(matchingChildTimer);
            }
            matchingChildTimer.merge(curr);
        }
    }

    @Override
    public void mergeChildTimersInto2(List<MutableAggregateTimer> childTimers) {
        for (MutableTraceTimer curr : this.childTimers) {
            String currName = curr.getName();
            boolean extended = curr.isExtended();
            MutableAggregateTimer matchingChildTimer = null;
            for (MutableAggregateTimer childTimer : childTimers) {
                if (currName.equals(childTimer.getName()) && extended == childTimer.isExtended()) {
                    matchingChildTimer = childTimer;
                    break;
                }
            }
            if (matchingChildTimer == null) {
                matchingChildTimer = new MutableAggregateTimer(curr.getName(), curr.isExtended(), 0,
                        0, new ArrayList<MutableAggregateTimer>());
                childTimers.add(matchingChildTimer);
            }
            matchingChildTimer.merge(curr);
        }
    }

    @Override
    public TimerImplSnapshot getSnapshot() {
        return ImmutableTimerImplSnapshot.builder()
                .totalNanos(totalNanos)
                .count(count)
                .active(active)
                .build();
    }

    public void merge(CommonTimerImpl timer) {
        TimerImplSnapshot snapshot = timer.getSnapshot();
        count += snapshot.count();
        totalNanos += snapshot.totalNanos();
        active = active || snapshot.active();
        timer.mergeChildTimersInto(childTimers);
    }

    public Trace.Timer toProto() {
        Trace.Timer.Builder builder = Trace.Timer.newBuilder()
                .setName(name)
                .setExtended(extended)
                .setTotalNanos(totalNanos)
                .setCount(count)
                .setActive(active);
        for (MutableTraceTimer childTimer : childTimers) {
            builder.addChildTimer(childTimer.toProto());
        }
        return builder.build();
    }
}
