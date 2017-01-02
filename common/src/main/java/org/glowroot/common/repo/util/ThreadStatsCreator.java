/*
 * Copyright 2017 the original author or authors.
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
package org.glowroot.common.repo.util;

import javax.annotation.Nullable;

import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.Proto.OptionalDouble;

public class ThreadStatsCreator {

    private ThreadStatsCreator() {}

    public static @Nullable Aggregate.ThreadStats create(@Nullable Double totalCpuNanos,
            @Nullable Double totalBlockedNanos, @Nullable Double totalWaitedNanos,
            @Nullable Double totalAllocatedBytes) {
        if (totalCpuNanos == null && totalBlockedNanos == null && totalWaitedNanos == null
                && totalAllocatedBytes == null) {
            return null;
        }
        Aggregate.ThreadStats.Builder threadStats = Aggregate.ThreadStats.newBuilder();
        if (totalCpuNanos != null) {
            threadStats.setTotalCpuNanos(OptionalDouble.newBuilder().setValue(totalCpuNanos));
        }
        if (totalBlockedNanos != null) {
            threadStats
                    .setTotalBlockedNanos(OptionalDouble.newBuilder().setValue(totalBlockedNanos));
        }
        if (totalWaitedNanos != null) {
            threadStats.setTotalWaitedNanos(OptionalDouble.newBuilder().setValue(totalWaitedNanos));
        }
        if (totalAllocatedBytes != null) {
            threadStats.setTotalAllocatedBytes(
                    OptionalDouble.newBuilder().setValue(totalAllocatedBytes));
        }
        return threadStats.build();
    }
}
