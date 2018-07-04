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

    private long cpuNanos;
    private long blockedMillis;
    private long waitedMillis;
    private long allocatedBytes;

    @Override
    public void mergeThreadStats(ThreadStats threadStats) {
        cpuNanos = NotAvailableAware.add(cpuNanos, threadStats.getCpuNanos());
        blockedMillis = NotAvailableAware.add(blockedMillis, threadStats.getBlockedMillis());
        waitedMillis = NotAvailableAware.add(waitedMillis, threadStats.getWaitedMillis());
        allocatedBytes =
                NotAvailableAware.add(allocatedBytes, threadStats.getAllocatedBytes());
    }

    ThreadStats getMergedThreadStats() {
        return new ThreadStats(cpuNanos, blockedMillis, waitedMillis, allocatedBytes);
    }

    long getCpuNanos() {
        return cpuNanos;
    }

    public Trace.ThreadStats toProto() {
        return Trace.ThreadStats.newBuilder()
                .setCpuNanos(cpuNanos)
                .setBlockedNanos(NotAvailableAware.millisToNanos(blockedMillis))
                .setWaitedNanos(NotAvailableAware.millisToNanos(waitedMillis))
                .setAllocatedBytes(allocatedBytes)
                .build();
    }
}
