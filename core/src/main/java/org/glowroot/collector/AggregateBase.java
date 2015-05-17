/*
 * Copyright 2013-2015 the original author or authors.
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
package org.glowroot.collector;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonRawValue;
import org.immutables.value.Value;

@Value.Immutable
public abstract class AggregateBase {

    public abstract String transactionType();
    public abstract @Nullable String transactionName();
    public abstract long captureTime();
    // aggregation uses microseconds to avoid (unlikely) 292 year nanosecond rollover
    public abstract long totalMicros();
    public abstract long errorCount();
    public abstract long transactionCount();
    public abstract @Nullable Long totalCpuMicros();
    public abstract @Nullable Long totalBlockedMicros();
    public abstract @Nullable Long totalWaitedMicros();
    // aggregation uses kbytes to avoid (unlikely) rollover
    public abstract @Nullable Long totalAllocatedKBytes();
    public abstract long traceCount();
    public abstract byte[] histogram();
    // timers json is always from "synthetic root"
    @JsonRawValue
    public abstract String timers();
    @JsonRawValue
    public abstract @Nullable String queries();
    // profile json is always from "synthetic root"
    @JsonRawValue
    public abstract @Nullable String profile();
}
