/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.core.trace;

import org.informantproject.api.TraceMetric;

import com.google.common.base.Ticker;

/**
 * Element of MetricData.
 * 
 * All timing data is measured in nanoseconds.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class TraceMetricImpl implements TraceMetric {

    private final String name;
    // nanosecond rollover (292 years) isn't a concern for total time on a single trace
    private long total;
    private long min = Long.MAX_VALUE;
    private long max = Long.MIN_VALUE;
    private long count;

    private long startTick;
    // selfNestingLevel is written after any non-volatile fields are written and it is read before
    // any non-volatile fields are read, creating a memory barrier and making the latest values of
    // the non-volatile fields visible to the reading thread
    private volatile int selfNestingLevel;
    // this field cannot piggyback on the volatility of selfNestingLevel like the others, but that
    // is ok since it is primarily read (cheap for volatile) and rarely updated ('expensive' for
    // volatile)
    private volatile boolean firstStart = true;
    // only accessed by trace thread
    private long pauseTick;

    private final Ticker ticker;

    TraceMetricImpl(String name, Ticker ticker) {
        this.name = name;
        this.ticker = ticker;
    }

    public String getName() {
        return name;
    }

    public long getTotal() {
        return total;
    }

    public long getMin() {
        return min;
    }

    public long getMax() {
        return max;
    }

    public long getAverageTime() {
        return total / count;
    }

    public long getCount() {
        return count;
    }

    boolean isFirstStart() {
        return firstStart;
    }

    void firstStartSeen() {
        firstStart = false;
    }

    // safe to be called from another thread
    public Snapshot copyOf() {
        // try to grab a quick, consistent snapshot, but no guarantees on consistency if trace is
        // active

        // selfNestingLevel is read first since it is used as a memory barrier so that the
        // non-volatile fields below will be visible to this threads
        boolean active = selfNestingLevel > 0;

        Snapshot copy = new Snapshot();
        copy.name = name;
        copy.total = total;
        copy.min = min;
        copy.max = max;
        copy.count = count;

        if (active) {
            copy.active = true;
            long currentTick = ticker.read();
            long curr = currentTick - startTick;
            copy.total += curr;
            if (min == Long.MAX_VALUE) {
                copy.min = curr;
                copy.minActive = true;
            }
            if (curr > max) {
                copy.max = curr;
                copy.maxActive = true;
            }
            copy.count++;
        }
        return copy;
    }

    void start() {
        if (selfNestingLevel == 0) {
            startTick = ticker.read();
        }
        // selfNestingLevel is incremented after updating startTick since selfNestingLevel is used
        // as a memory barrier so startTick will be visible to other threads in copyOf()
        selfNestingLevel++;
    }

    void start(long startTick) {
        if (selfNestingLevel == 0) {
            this.startTick = startTick;
        }
        // selfNestingLevel is incremented after updating startTick since selfNestingLevel is used
        // as a memory barrier so startTick will be visible to other threads in copyOf()
        selfNestingLevel++;
    }

    public void stop() {
        if (selfNestingLevel == 1) {
            recordData(ticker.read() - startTick);
        }
        // selfNestingLevel is decremented after recording data since it is used as a memory barrier
        // so that all updated fields will be visible to other threads in copyOf()
        selfNestingLevel--;
    }

    void stop(long endTick) {
        if (selfNestingLevel == 1) {
            recordData(endTick - startTick);
        }
        // selfNestingLevel is decremented after recording data since it is volatile and creates a
        // memory barrier so that all updated fields will be visible to other threads in copyOf()
        selfNestingLevel--;
    }

    // this is kind of hacky, only meant to be used by "informant weaving" metric
    public void pause() {
        pauseTick = ticker.read();
        // selfNestingLevel is incremented (sort of unnecessarily) so that resume() can decrement
        // it (also sort of unnecessarily) to create memory barrier so that startTick will be
        // visible to other threads calling copyOf()
        selfNestingLevel++;
    }

    public void resume() {
        long pauseTicks = ticker.read() - pauseTick;
        startTick += pauseTicks;
        // selfNestingLevel is decremented (sort of unnecessarily) to create memory barrier so that
        // startTick will be visible to other threads calling copyOf()
        selfNestingLevel--;
    }

    private void recordData(long time) {
        if (time > max) {
            max = time;
        }
        if (time < min) {
            min = time;
        }
        count++;
        total += time;
    }

    public static class Snapshot {
        private String name;
        private long total;
        private long min;
        private long max;
        private long count;
        private boolean active;
        private boolean minActive;
        private boolean maxActive;
        public String getName() {
            return name;
        }
        public long getTotal() {
            return total;
        }
        public long getMin() {
            return min;
        }
        public long getMax() {
            return max;
        }
        public long getCount() {
            return count;
        }
        public boolean isActive() {
            return active;
        }
        public boolean isMinActive() {
            return minActive;
        }
        public boolean isMaxActive() {
            return maxActive;
        }
    }
}
