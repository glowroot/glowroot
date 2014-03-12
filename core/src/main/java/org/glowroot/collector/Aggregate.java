/*
 * Copyright 2013-2014 the original author or authors.
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
package org.glowroot.collector;

import org.glowroot.markers.NotThreadSafe;
import org.glowroot.markers.OnlyUsedByTests;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// must be used under an appropriate lock
@NotThreadSafe
public class Aggregate {

    private long totalDuration; // nanoseconds
    private long count;
    private long errorCount;
    private long storedTraceCount;

    Aggregate() {}

    @OnlyUsedByTests
    public Aggregate(long totalDuration, long count) {
        this.totalDuration = totalDuration;
        this.count = count;
    }

    public long getTotalDuration() {
        return totalDuration;
    }

    public long getCount() {
        return count;
    }

    public long getErrorCount() {
        return errorCount;
    }

    public long getStoredTraceCount() {
        return storedTraceCount;
    }

    void add(long duration) {
        totalDuration += duration;
        count++;
    }

    void addToErrorCount() {
        errorCount++;
    }

    void addToStoredTraceCount() {
        storedTraceCount++;
    }
}
