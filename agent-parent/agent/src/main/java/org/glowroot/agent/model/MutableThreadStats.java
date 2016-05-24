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
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.Proto.OptionalDouble;
import org.glowroot.wire.api.model.Proto.OptionalInt64;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

public class MutableThreadStats {

    private long totalCpuNanos;
    private long totalBlockedNanos;
    private long totalWaitedNanos;
    private long totalAllocatedBytes;

    private boolean empty = true;

    public void addThreadStats(ThreadStats threadStats) {
        totalCpuNanos = NotAvailableAware.add(totalCpuNanos, threadStats.getTotalCpuNanos());
        totalBlockedNanos = NotAvailableAware.addMillisToNanos(totalBlockedNanos,
                threadStats.getTotalBlockedMillis());
        totalWaitedNanos = NotAvailableAware.addMillisToNanos(totalWaitedNanos,
                threadStats.getTotalWaitedMillis());
        totalAllocatedBytes = NotAvailableAware.add(totalAllocatedBytes,
                threadStats.getTotalAllocatedBytes());
        empty = false;
    }

    public boolean isNA() {
        if (empty) {
            return true;
        }
        return NotAvailableAware.isNA(totalCpuNanos)
                && NotAvailableAware.isNA(totalBlockedNanos)
                && NotAvailableAware.isNA(totalWaitedNanos)
                && NotAvailableAware.isNA(totalAllocatedBytes);
    }

    public Aggregate.ThreadStats toAggregateProto() {
        Aggregate.ThreadStats.Builder builder = Aggregate.ThreadStats.newBuilder();
        if (!NotAvailableAware.isNA(totalCpuNanos)) {
            builder.setTotalCpuNanos(toAggregateProto(totalCpuNanos));
        }
        if (!NotAvailableAware.isNA(totalBlockedNanos)) {
            builder.setTotalBlockedNanos(toAggregateProto(totalBlockedNanos));
        }
        if (!NotAvailableAware.isNA(totalWaitedNanos)) {
            builder.setTotalWaitedNanos(toAggregateProto(totalWaitedNanos));
        }
        if (!NotAvailableAware.isNA(totalAllocatedBytes)) {
            builder.setTotalAllocatedBytes(toAggregateProto(totalAllocatedBytes));
        }
        return builder.build();
    }

    public Trace.ThreadStats toTraceProto() {
        Trace.ThreadStats.Builder builder = Trace.ThreadStats.newBuilder();
        if (!NotAvailableAware.isNA(totalCpuNanos)) {
            builder.setTotalCpuNanos(toTraceProto(totalCpuNanos));
        }
        if (!NotAvailableAware.isNA(totalBlockedNanos)) {
            builder.setTotalBlockedNanos(toTraceProto(totalBlockedNanos));
        }
        if (!NotAvailableAware.isNA(totalWaitedNanos)) {
            builder.setTotalWaitedNanos(toTraceProto(totalWaitedNanos));
        }
        if (!NotAvailableAware.isNA(totalAllocatedBytes)) {
            builder.setTotalAllocatedBytes(toTraceProto(totalAllocatedBytes));
        }
        return builder.build();
    }

    private static OptionalDouble toAggregateProto(long value) {
        return OptionalDouble.newBuilder().setValue(value).build();
    }

    private static OptionalInt64 toTraceProto(long value) {
        return OptionalInt64.newBuilder().setValue(value).build();
    }
}
