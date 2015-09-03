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

import com.google.common.collect.Lists;

import org.glowroot.agent.model.TimerImpl;
import org.glowroot.collector.spi.model.AggregateOuterClass.Aggregate;
import org.glowroot.markers.UsedByJsonBinding;

@UsedByJsonBinding
public class MutableTimer {

    private final String name;
    private final boolean extended;
    // aggregates use double instead of long to avoid (unlikely) 292 year nanosecond rollover
    private double totalNanos;
    private long count;
    private final List<MutableTimer> childTimers;

    public static MutableTimer createRootTimer(String name, boolean extended) {
        return new MutableTimer(name, extended, 0, 0, new ArrayList<MutableTimer>());
    }

    private MutableTimer(String name, boolean extended, double totalNanos, long count,
            List<MutableTimer> nestedTimers) {
        this.name = name;
        this.extended = extended;
        this.totalNanos = totalNanos;
        this.count = count;
        this.childTimers = Lists.newArrayList(nestedTimers);
    }

    public String getName() {
        return name;
    }

    public boolean isExtended() {
        return extended;
    }

    public double getTotalNanos() {
        return totalNanos;
    }

    public long getCount() {
        return count;
    }

    public List<MutableTimer> getChildTimers() {
        return childTimers;
    }

    public void merge(Aggregate.Timer timer) {
        count += timer.getCount();
        totalNanos += timer.getTotalNanos();
        for (Aggregate.Timer toBeMergedChildTimer : timer.getChildTimerList()) {
            String toBeMergedChildTimerName = toBeMergedChildTimer.getName();
            boolean extended = toBeMergedChildTimer.getExtended();
            MutableTimer matchingChildTimer = null;
            for (MutableTimer childTimer : childTimers) {
                if (toBeMergedChildTimerName.equals(childTimer.getName())
                        && extended == childTimer.isExtended()) {
                    matchingChildTimer = childTimer;
                    break;
                }
            }
            if (matchingChildTimer == null) {
                matchingChildTimer = new MutableTimer(toBeMergedChildTimer.getName(),
                        toBeMergedChildTimer.getExtended(), 0, 0, new ArrayList<MutableTimer>());
                childTimers.add(matchingChildTimer);
            }
            matchingChildTimer.merge(toBeMergedChildTimer);
        }
    }

    public void merge(TimerImpl timer) {
        count += timer.getCount();
        totalNanos += timer.getTotalNanos();
        for (TimerImpl toBeMergedChildTimer : timer.getChildTimers()) {
            String toBeMergedChildTimerName = toBeMergedChildTimer.getName();
            boolean extended = toBeMergedChildTimer.isExtended();
            MutableTimer matchingChildTimer = null;
            for (MutableTimer childTimer : childTimers) {
                if (toBeMergedChildTimerName.equals(childTimer.getName())
                        && extended == childTimer.isExtended()) {
                    matchingChildTimer = childTimer;
                    break;
                }
            }
            if (matchingChildTimer == null) {
                matchingChildTimer = new MutableTimer(toBeMergedChildTimer.getName(),
                        toBeMergedChildTimer.isExtended(), 0, 0, new ArrayList<MutableTimer>());
                childTimers.add(matchingChildTimer);
            }
            matchingChildTimer.merge(toBeMergedChildTimer);
        }
    }

    public Aggregate.Timer toProtobuf() {
        Aggregate.Timer.Builder builder = Aggregate.Timer.newBuilder()
                .setName(name)
                .setExtended(extended)
                .setTotalNanos(totalNanos)
                .setCount(count);
        for (MutableTimer childTimer : childTimers) {
            builder.addChildTimer(childTimer.toProtobuf());
        }
        return builder.build();
    }
}
