/*
 * Copyright 2015-2018 the original author or authors.
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
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

public class MergedThreadTimer implements TransactionTimer, AggregatedTimer {

    private final String name;
    private final boolean extended;
    private long totalNanos;
    private long count;
    private boolean active;
    private final List<MergedThreadTimer> childTimers;

    public static MergedThreadTimer createAuxThreadRootTimer() {
        return new MergedThreadTimer(TimerNameCache.AUXILIARY_THREAD_ROOT_TIMER_NAME, false);
    }

    public MergedThreadTimer(String name, boolean extended) {
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
    public long getTotalNanos() {
        return totalNanos;
    }

    @Override
    public long getCount() {
        return count;
    }

    @Override
    public void mergeChildTimersInto(AggregatedTimer timer) {
        for (MergedThreadTimer curr : this.childTimers) {
            String currName = curr.getName();
            boolean extended = curr.isExtended();
            AggregatedTimer matchingChildTimer = null;
            for (AggregatedTimer childTimer : timer.getChildTimers()) {
                if (currName.equals(childTimer.getName()) && extended == childTimer.isExtended()) {
                    matchingChildTimer = childTimer;
                    break;
                }
            }
            if (matchingChildTimer == null) {
                matchingChildTimer = timer.newChildTimer(curr.getName(), curr.isExtended());
            }
            matchingChildTimer.addDataFrom(curr);
        }
    }

    @Override
    public TransactionTimerSnapshot getSnapshot() {
        return ImmutableTransactionTimerSnapshot.builder()
                .totalNanos(totalNanos)
                .count(count)
                .active(active)
                .build();
    }

    @Override
    public List<MergedThreadTimer> getChildTimers() {
        return childTimers;
    }

    @Override
    public AggregatedTimer newChildTimer(String name, boolean extended) {
        MergedThreadTimer childTimer = new MergedThreadTimer(name, extended);
        childTimers.add(childTimer);
        return childTimer;
    }

    @Override
    public void addDataFrom(TransactionTimer timer) {
        TransactionTimerSnapshot snapshot = timer.getSnapshot();
        count += snapshot.count();
        totalNanos += snapshot.totalNanos();
        active = active || snapshot.active();
        timer.mergeChildTimersInto(this);
    }

    public Trace.Timer toProto() {
        Trace.Timer.Builder builder = Trace.Timer.newBuilder()
                .setName(name)
                .setExtended(extended)
                .setTotalNanos(totalNanos)
                .setCount(count)
                .setActive(active);
        for (MergedThreadTimer childTimer : childTimers) {
            builder.addChildTimer(childTimer.toProto());
        }
        return builder.build();
    }
}
