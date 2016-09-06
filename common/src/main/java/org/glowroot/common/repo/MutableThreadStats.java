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
package org.glowroot.common.repo;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.glowroot.common.util.NotAvailableAware;
import org.glowroot.common.util.UsedByJsonSerialization;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.Proto.OptionalDouble;

public class MutableThreadStats {

    private double totalCpuNanos;
    private double totalBlockedNanos;
    private double totalWaitedNanos;
    private double totalAllocatedBytes;

    private boolean empty = true;

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

    public void addTotalCpuNanos(@Nullable Double totalCpuNanos) {
        if (totalCpuNanos != null) {
            this.totalCpuNanos = NotAvailableAware.add(this.totalCpuNanos, totalCpuNanos);
        } else {
            this.totalCpuNanos = NotAvailableAware.NA;
        }
    }

    public void addTotalBlockedNanos(@Nullable Double totalBlockedNanos) {
        if (totalBlockedNanos != null) {
            this.totalBlockedNanos =
                    NotAvailableAware.add(this.totalBlockedNanos, totalBlockedNanos);
        } else {
            this.totalBlockedNanos = NotAvailableAware.NA;
        }
    }

    public void addTotalWaitedNanos(@Nullable Double totalWaitedNanos) {
        if (totalWaitedNanos != null) {
            this.totalWaitedNanos = NotAvailableAware.add(this.totalWaitedNanos, totalWaitedNanos);
        } else {
            this.totalWaitedNanos = NotAvailableAware.NA;
        }
    }

    public void addTotalAllocatedBytes(@Nullable Double totalAllocatedBytes) {
        if (totalAllocatedBytes != null) {
            this.totalAllocatedBytes =
                    NotAvailableAware.add(this.totalAllocatedBytes, totalAllocatedBytes);
        } else {
            this.totalAllocatedBytes = NotAvailableAware.NA;
        }
    }

    public void addThreadStats(@Nullable Aggregate.ThreadStats threadStats) {
        if (threadStats == null) {
            totalCpuNanos = NotAvailableAware.NA;
            totalBlockedNanos = NotAvailableAware.NA;
            totalWaitedNanos = NotAvailableAware.NA;
            totalAllocatedBytes = NotAvailableAware.NA;
            return;
        }
        if (threadStats.hasTotalCpuNanos()) {
            totalCpuNanos =
                    NotAvailableAware.add(totalCpuNanos, threadStats.getTotalCpuNanos().getValue());
        } else {
            totalCpuNanos = NotAvailableAware.NA;
        }
        if (threadStats.hasTotalBlockedNanos()) {
            totalBlockedNanos = NotAvailableAware.add(totalBlockedNanos,
                    threadStats.getTotalBlockedNanos().getValue());
        } else {
            totalCpuNanos = NotAvailableAware.NA;
        }
        if (threadStats.hasTotalWaitedNanos()) {
            totalWaitedNanos = NotAvailableAware.add(totalWaitedNanos,
                    threadStats.getTotalWaitedNanos().getValue());
        } else {
            totalCpuNanos = NotAvailableAware.NA;
        }
        if (threadStats.hasTotalAllocatedBytes()) {
            totalAllocatedBytes = NotAvailableAware.add(totalAllocatedBytes,
                    threadStats.getTotalAllocatedBytes().getValue());
        } else {
            totalCpuNanos = NotAvailableAware.NA;
        }
        empty = false;
    }

    @JsonIgnore
    public boolean isNA() {
        if (empty) {
            return true;
        }
        return NotAvailableAware.isNA(totalCpuNanos)
                && NotAvailableAware.isNA(totalBlockedNanos)
                && NotAvailableAware.isNA(totalWaitedNanos)
                && NotAvailableAware.isNA(totalAllocatedBytes);
    }

    Aggregate.ThreadStats toProto() {
        Aggregate.ThreadStats.Builder builder = Aggregate.ThreadStats.newBuilder();
        if (!NotAvailableAware.isNA(totalCpuNanos)) {
            builder.setTotalCpuNanos(toProto(totalCpuNanos));
        }
        if (!NotAvailableAware.isNA(totalBlockedNanos)) {
            builder.setTotalBlockedNanos(toProto(totalBlockedNanos));
        }
        if (!NotAvailableAware.isNA(totalWaitedNanos)) {
            builder.setTotalWaitedNanos(toProto(totalWaitedNanos));
        }
        if (!NotAvailableAware.isNA(totalAllocatedBytes)) {
            builder.setTotalAllocatedBytes(toProto(totalAllocatedBytes));
        }
        return builder.build();
    }

    private static OptionalDouble toProto(double value) {
        return OptionalDouble.newBuilder().setValue(value).build();
    }
}
