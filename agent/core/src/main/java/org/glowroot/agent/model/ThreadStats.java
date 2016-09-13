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

import org.glowroot.common.util.NotAvailableAware;

// not using Immutables for micro-optimization purposes, since instantiated for each transaction
public class ThreadStats {

    public static final ThreadStats NA = new ThreadStats(NotAvailableAware.NA, NotAvailableAware.NA,
            NotAvailableAware.NA, NotAvailableAware.NA);

    private final long totalCpuNanos;
    private final long totalBlockedMillis; // not converting to nanos here for micro-opt purposes
    private final long totalWaitedMillis; // not converting to nanos here for micro-opt purposes
    private final long totalAllocatedBytes;

    public ThreadStats(long totalCpuNanos, long totalBlockedMillis, long totalWaitedMillis,
            long totalAllocatedBytes) {
        this.totalCpuNanos = totalCpuNanos;
        this.totalBlockedMillis = totalBlockedMillis;
        this.totalWaitedMillis = totalWaitedMillis;
        this.totalAllocatedBytes = totalAllocatedBytes;
    }

    public long getTotalCpuNanos() {
        return totalCpuNanos;
    }

    public long getTotalBlockedMillis() {
        return totalBlockedMillis;
    }

    public long getTotalWaitedMillis() {
        return totalWaitedMillis;
    }

    public long getTotalAllocatedBytes() {
        return totalAllocatedBytes;
    }
}
