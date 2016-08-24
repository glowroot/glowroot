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

import org.glowroot.agent.model.TimerImpl.TimerImplSnapshot;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

class MutableTimer {

    private final String name;
    private final boolean extended;
    private long totalNanos;
    private long count;
    private boolean active;
    private final List<MutableTimer> childTimers;

    static MutableTimer createRootTimer(String name, boolean extended) {
        return new MutableTimer(name, extended, 0, 0, new ArrayList<MutableTimer>());
    }

    public MutableTimer(String name, boolean extended, long totalNanos, long count,
            List<MutableTimer> nestedTimers) {
        this.name = name;
        this.extended = extended;
        this.totalNanos = totalNanos;
        this.count = count;
        this.childTimers = Lists.newArrayList(nestedTimers);
    }

    String getName() {
        return name;
    }

    public boolean isExtended() {
        return extended;
    }

    void merge(CommonTimerImpl timer) {
        TimerImplSnapshot snapshot = timer.getSnapshot();
        count += snapshot.count();
        totalNanos += snapshot.totalNanos();
        active = active || snapshot.active();
        timer.mergeChildTimersInto(childTimers);
    }

    Trace.Timer toProto() {
        Trace.Timer.Builder builder = Trace.Timer.newBuilder()
                .setName(name)
                .setExtended(extended)
                .setTotalNanos(totalNanos)
                .setCount(count)
                .setActive(active);
        for (MutableTimer childTimer : childTimers) {
            builder.addChildTimer(childTimer.toProto());
        }
        return builder.build();
    }
}
