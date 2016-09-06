/*
 * Copyright 2016 the original author or authors.
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

import javax.annotation.Nullable;

import com.google.common.base.Ticker;

import org.glowroot.agent.util.Tickers;

public class AsyncTimerImpl implements CommonTimerImpl {

    private static final Ticker ticker = Tickers.getTicker();

    private final TimerNameImpl timerName;
    private final long startTick;

    private volatile long totalNanos = -1;

    // this is for maintaining list of async timers
    private volatile @Nullable AsyncTimerImpl nextAsyncTimer;

    public AsyncTimerImpl(TimerNameImpl timerName, long startTick) {
        this.timerName = timerName;
        this.startTick = startTick;
    }

    public void end(long endTick) {
        totalNanos = endTick - startTick;
    }

    @Override
    public String getName() {
        return timerName.name();
    }

    @Override
    public boolean isExtended() {
        return false;
    }

    @Override
    public long getTotalNanos() {
        long totalNanos = this.totalNanos;
        if (totalNanos == -1) {
            // active
            totalNanos = ticker.read() - startTick;
        }
        return totalNanos;
    }

    @Override
    public long getCount() {
        return 1;
    }

    @Override
    public void mergeChildTimersInto(List<MutableTraceTimer> childTimers) {
        // async timers have no child timers
    }

    @Override
    public void mergeChildTimersInto2(List<MutableAggregateTimer> childTimers) {
        // async timers have no child timers
    }

    public boolean active() {
        return totalNanos == -1;
    }

    @Override
    public TimerImplSnapshot getSnapshot() {
        long totalNanos = this.totalNanos;
        boolean active = false;
        if (totalNanos == -1) {
            // active
            active = true;
            totalNanos = ticker.read() - startTick;
        }
        return ImmutableTimerImplSnapshot.builder()
                .totalNanos(totalNanos)
                .count(1)
                .active(active)
                .build();
    }
}
