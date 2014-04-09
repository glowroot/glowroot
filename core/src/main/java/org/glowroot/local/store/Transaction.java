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
public class Transaction {

    private final String name;
    // aggregation uses microseconds to avoid (unlikely) 292 year nanosecond rollover
    private final long totalMicros;
    private final long count;
    private final long errorCount;
    private final long storedTraceCount;

    Transaction(String name, long totalMicros, long count, long errorCount, long storedTraceCount) {
        this.name = name;
        this.totalMicros = totalMicros;
        this.count = count;
        this.errorCount = errorCount;
        this.storedTraceCount = storedTraceCount;
    }

    public String getName() {
        return name;
    }

    public long getTotalMicros() {
        return totalMicros;
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
}
