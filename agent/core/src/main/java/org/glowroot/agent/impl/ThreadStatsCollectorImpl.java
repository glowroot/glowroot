/*
 * Copyright 2016-2018 the original author or authors.
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
package org.glowroot.agent.impl;

import org.glowroot.agent.impl.Transaction.ThreadStatsCollector;
import org.glowroot.agent.model.ThreadStats;
import org.glowroot.common.util.NotAvailableAware;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

class ThreadStatsCollectorImpl implements ThreadStatsCollector {

    private long totalCpuNanos;
    private long totalBlockedMillis;
    private long totalWaitedMillis;
    private long totalAllocatedBytes;

    @Override
    public void mergeThreadStats(ThreadStats threadStats) {
        totalCpuNanos = NotAvailableAware.add(totalCpuNanos, threadStats.getTotalCpuNanos());
        totalBlockedMillis =
                NotAvailableAware.add(totalBlockedMillis, threadStats.getTotalBlockedMillis());
        totalWaitedMillis =
                NotAvailableAware.add(totalWaitedMillis, threadStats.getTotalWaitedMillis());
        totalAllocatedBytes = NotAvailableAware.add(totalAllocatedBytes,
                threadStats.getTotalAllocatedBytes());
    }

    ThreadStats getMergedThreadStats() {
        return new ThreadStats(totalCpuNanos, totalBlockedMillis, totalWaitedMillis,
                totalAllocatedBytes);
    }

    long getTotalCpuNanos() {
        return totalCpuNanos;
    }

    public Trace.ThreadStats toProto() {
        return Trace.ThreadStats.newBuilder()
                .setTotalCpuNanos(totalCpuNanos)
                .setTotalBlockedNanos(NotAvailableAware.millisToNanos(totalBlockedMillis))
                .setTotalWaitedNanos(NotAvailableAware.millisToNanos(totalWaitedMillis))
                .setTotalAllocatedBytes(totalAllocatedBytes)
                .build();
    }
}
