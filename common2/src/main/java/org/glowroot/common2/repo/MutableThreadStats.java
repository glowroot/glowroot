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
package org.glowroot.common2.repo;

import org.glowroot.common.util.NotAvailableAware;
import org.glowroot.common2.repo.util.UsedByJsonSerialization;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;

public class MutableThreadStats {

    private double totalCpuNanos;
    private double totalBlockedNanos;
    private double totalWaitedNanos;
    private double totalAllocatedBytes;

    @UsedByJsonSerialization
    public double getTotalCpuNanos() {
        return totalCpuNanos;
    }

    @UsedByJsonSerialization
    public double getTotalBlockedNanos() {
        return totalBlockedNanos;
    }

    @UsedByJsonSerialization
    public double getTotalWaitedNanos() {
        return totalWaitedNanos;
    }

    @UsedByJsonSerialization
    public double getTotalAllocatedBytes() {
        return totalAllocatedBytes;
    }

    public void addTotalCpuNanos(double totalCpuNanos) {
        this.totalCpuNanos = NotAvailableAware.add(this.totalCpuNanos, totalCpuNanos);
    }

    public void addTotalBlockedNanos(double totalBlockedNanos) {
        this.totalBlockedNanos = NotAvailableAware.add(this.totalBlockedNanos, totalBlockedNanos);
    }

    public void addTotalWaitedNanos(double totalWaitedNanos) {
        this.totalWaitedNanos = NotAvailableAware.add(this.totalWaitedNanos, totalWaitedNanos);
    }

    public void addTotalAllocatedBytes(double totalAllocatedBytes) {
        this.totalAllocatedBytes =
                NotAvailableAware.add(this.totalAllocatedBytes, totalAllocatedBytes);
    }

    public void addThreadStats(Aggregate.ThreadStats threadStats) {
        totalCpuNanos = NotAvailableAware.add(totalCpuNanos, threadStats.getTotalCpuNanos());
        totalBlockedNanos =
                NotAvailableAware.add(totalBlockedNanos, threadStats.getTotalBlockedNanos());
        totalWaitedNanos =
                NotAvailableAware.add(totalWaitedNanos, threadStats.getTotalWaitedNanos());
        totalAllocatedBytes =
                NotAvailableAware.add(totalAllocatedBytes, threadStats.getTotalAllocatedBytes());
    }

    Aggregate.ThreadStats toProto() {
        return Aggregate.ThreadStats.newBuilder()
                .setTotalCpuNanos(totalCpuNanos)
                .setTotalBlockedNanos(totalBlockedNanos)
                .setTotalWaitedNanos(totalWaitedNanos)
                .setTotalAllocatedBytes(totalAllocatedBytes)
                .build();
    }
}
