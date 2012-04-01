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

import com.google.common.base.Ticker;

/**
 * Element of MetricData.
 * 
 * All timing data is measured in nanoseconds.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class MetricDataItem {

    private final String name;
    // nanosecond rollover (292 years) isn't a concern for total time on a single trace
    private volatile long total;
    private volatile long min = Long.MAX_VALUE;
    private volatile long max = Long.MIN_VALUE;
    private volatile long count;

    // these fields don't need to be thread safe since they are only accessed by the trace thread
    private long startTick;
    private int selfNestingLevel;

    private final Ticker ticker;

    MetricDataItem(String name, Ticker ticker) {
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

    void recordData(long time) {
        if (time > max) {
            max = time;
        }
        if (time < min) {
            min = time;
        }
        count++;
        total += time;
    }

    void start() {
        if (selfNestingLevel == 0) {
            startTick = ticker.read();
        }
        selfNestingLevel++;
    }

    void start(long startTick) {
        if (selfNestingLevel == 0) {
            this.startTick = startTick;
        }
        selfNestingLevel++;
    }

    void stop() {
        selfNestingLevel--;
        if (selfNestingLevel == 0) {
            recordData(ticker.read() - startTick);
        }
    }

    void stop(long endTick) {
        selfNestingLevel--;
        if (selfNestingLevel == 0) {
            recordData(endTick - startTick);
        }
    }
}
