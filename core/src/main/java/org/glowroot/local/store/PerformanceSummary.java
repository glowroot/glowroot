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
package org.glowroot.local.store;

import javax.annotation.Nullable;

import org.immutables.value.Json;
import org.immutables.value.Value;

@Value.Immutable
@Json.Marshaled
public abstract class PerformanceSummary {
    public abstract @Nullable String transactionName();
    // aggregation uses microseconds to avoid (unlikely) 292 year nanosecond rollover
    public abstract long totalMicros();
    public abstract long transactionCount();
}
