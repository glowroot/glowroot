/**
 * Copyright 2011 the original author or authors.
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
package org.informantproject.trace;

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
    private volatile long totalTime;
    private volatile long minimumTime = Long.MAX_VALUE;
    private volatile long maximumTime = Long.MIN_VALUE;
    private volatile long count;

    MetricDataItem(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public long getTotalTime() {
        return totalTime;
    }

    public long getMinimumTime() {
        return minimumTime;
    }

    public long getMaximumTime() {
        return maximumTime;
    }

    public long getAverageTime() {
        return totalTime / count;
    }

    public long getCount() {
        return count;
    }

    void recordData(long time) {
        if (time > maximumTime) {
            maximumTime = time;
        }
        if (time < minimumTime) {
            minimumTime = time;
        }
        count++;
        totalTime += time;
    }
}
