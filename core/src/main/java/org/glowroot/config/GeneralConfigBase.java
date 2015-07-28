/*
 * Copyright 2011-2015 the original author or authors.
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
package org.glowroot.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;

@Value.Immutable
public abstract class GeneralConfigBase {

    // 0 means store all traces
    @Value.Default
    public int traceStoreThresholdMillis() {
        return 2000;
    }

    // 0 means profiling disabled
    @Value.Default
    public int profilingIntervalMillis() {
        return 1000;
    }

    @Value.Default
    public String defaultDisplayedTransactionType() {
        return "";
    }

    @Value.Default
    public ImmutableList<Double> defaultDisplayedPercentiles() {
        return ImmutableList.of(50.0, 95.0, 99.0);
    }

    @Value.Derived
    @JsonIgnore
    public String version() {
        return Versions.getVersion(this);
    }
}
