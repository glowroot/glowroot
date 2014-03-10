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
package org.glowroot.local.store;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class AggregatePoint {

    private final long captureTime;
    private final double totalMillis;
    private final long count;
    private final long storedTraceCount;

    AggregatePoint(long captureTime, double totalMillis, long count, long storedTraceCount) {
        this.captureTime = captureTime;
        this.totalMillis = totalMillis;
        this.count = count;
        this.storedTraceCount = storedTraceCount;
    }

    public long getCaptureTime() {
        return captureTime;
    }

    public double getTotalMillis() {
        return totalMillis;
    }

    public long getCount() {
        return count;
    }

    public long getStoredTraceCount() {
        return storedTraceCount;
    }
}
