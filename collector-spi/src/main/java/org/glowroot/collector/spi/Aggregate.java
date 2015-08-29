/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.collector.spi;

import java.util.Collection;
import java.util.Map;

import javax.annotation.Nullable;

public interface Aggregate {

    long captureTime();

    // aggregates use double instead of long to avoid (unlikely) 292 year nanosecond rollover
    double totalNanos();

    long transactionCount();
    long errorCount();

    // aggregates use double instead of long to avoid (unlikely) 292 year nanosecond rollover
    double totalCpuNanos(); // -1 means N/A
    double totalBlockedNanos(); // -1 means N/A
    double totalWaitedNanos(); // -1 means N/A
    double totalAllocatedBytes(); // -1 means N/A

    Histogram histogram();

    AggregateTimerNode syntheticRootTimerNode();

    // key is query type (e.g. "SQL", "CQL")
    Map<String, ? extends Collection<? extends Query>> queries();

    @Nullable
    ProfileNode syntheticRootProfileNode();
}
