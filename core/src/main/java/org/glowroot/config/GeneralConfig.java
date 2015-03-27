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
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.immutables.value.Value;

@Value.Immutable
@JsonSerialize(as = ImmutableGeneralConfig.class)
@JsonDeserialize(as = ImmutableGeneralConfig.class)
public abstract class GeneralConfig {

    // if tracing is disabled mid-trace there should be no issue
    // active traces will not accumulate additional entries
    // but they will be stored if they exceed the defined thresholds
    //
    // if tracing is enabled mid-trace there should be no issue
    // active traces that were not captured at their start will
    // continue not to accumulate entries
    // and they will not be stored even if they exceed the defined thresholds
    @Value.Default
    public boolean enabled() {
        return true;
    }

    // 0 means store all traces, -1 means store no traces
    @Value.Default
    public int traceStoreThresholdMillis() {
        return 3000;
    }

    // 0 means profiling disabled
    @Value.Default
    public int profilingIntervalMillis() {
        return 2000;
    }

    @Value.Default
    public String defaultTransactionType() {
        return "";
    }

    @Value.Derived
    @JsonIgnore
    public String version() {
        return Versions.getVersion(this);
    }
}
