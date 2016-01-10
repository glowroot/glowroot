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
package org.glowroot.storage.repo;

import org.glowroot.common.util.NotAvailableAware;
import org.glowroot.common.util.UsedByJsonSerialization;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate.OptionalDouble;

public class MutableThreadStats {

    private double totalCpuNanos = NotAvailableAware.NA;
    private double totalBlockedNanos = NotAvailableAware.NA;
    private double totalWaitedNanos = NotAvailableAware.NA;
    private double totalAllocatedBytes = NotAvailableAware.NA;

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

    public void addThreadStats(Aggregate.ThreadStats threadStats) {
        if (threadStats.hasTotalCpuNanos()) {
            totalCpuNanos = NotAvailableAware.add(totalCpuNanos,
                    threadStats.getTotalCpuNanos().getValue());
        }
        if (threadStats.hasTotalBlockedNanos()) {
            totalBlockedNanos = NotAvailableAware.add(totalBlockedNanos,
                    threadStats.getTotalBlockedNanos().getValue());
        }
        if (threadStats.hasTotalWaitedNanos()) {
            totalWaitedNanos = NotAvailableAware.add(totalWaitedNanos,
                    threadStats.getTotalWaitedNanos().getValue());
        }
        if (threadStats.hasTotalAllocatedBytes()) {
            totalAllocatedBytes = NotAvailableAware.add(totalAllocatedBytes,
                    threadStats.getTotalAllocatedBytes().getValue());
        }
    }

    public boolean isEmpty() {
        return NotAvailableAware.isNA(totalCpuNanos)
                && NotAvailableAware.isNA(totalBlockedNanos)
                && NotAvailableAware.isNA(totalWaitedNanos)
                && NotAvailableAware.isNA(totalAllocatedBytes);
    }

    Aggregate.ThreadStats toProto() {
        return Aggregate.ThreadStats.newBuilder()
                .setTotalCpuNanos(toOptionalDouble(totalCpuNanos))
                .setTotalBlockedNanos(toOptionalDouble(totalBlockedNanos))
                .setTotalWaitedNanos(toOptionalDouble(totalWaitedNanos))
                .setTotalAllocatedBytes(toOptionalDouble(totalAllocatedBytes))
                .build();
    }

    private static OptionalDouble toOptionalDouble(double value) {
        if (NotAvailableAware.isNA(value)) {
            return OptionalDouble.getDefaultInstance();
        } else {
            return OptionalDouble.newBuilder().setValue(value).build();
        }
    }
}
