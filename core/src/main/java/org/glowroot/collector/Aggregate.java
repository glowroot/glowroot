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

    private long totalMillis;
    private long count;
    private long storedTraceCount;

    Aggregate() {}

    @OnlyUsedByTests
    public Aggregate(long totalMillis, long count) {
        this.totalMillis = totalMillis;
        this.count = count;
    }

    public long getTotalMillis() {
        return totalMillis;
    }

    public long getCount() {
        return count;
    }

    public long getStoredTraceCount() {
        return storedTraceCount;
    }

    void add(long millis) {
        totalMillis += millis;
        count++;
    }

    void addToStoredTraceCount() {
        storedTraceCount++;
    }
}
