/*
 * Copyright 2011-2014 the original author or authors.
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

import com.google.common.base.Charsets;
import com.google.common.hash.Hashing;
import org.immutables.common.marshal.Marshaling;
import org.immutables.value.Json;
import org.immutables.value.Value;

@Value.Immutable
@Json.Marshaled
public abstract class TraceConfig {

    // if tracing is disabled mid-trace there should be no issue
    // active traces will not accumulate additional entries
    // but they will be logged / emailed if they exceed the defined thresholds
    //
    // if tracing is enabled mid-trace there should be no issue
    // active traces that were not captured at their start will
    // continue not to accumulate entries
    // and they will not be logged / emailed even if they exceed the defined
    // thresholds
    @Value.Default
    public boolean enabled() {
        return true;
    }

    // 0 means log all traces, -1 means log no traces
    @Value.Default
    public int storeThresholdMillis() {
        return 3000;
    }

    @Value.Default
    public boolean outlierProfilingEnabled() {
        return true;
    }

    // minimum is imposed because of OutlierProfileWatcher#PERIOD_MILLIS
    // -1 means no stack traces are gathered, should be minimum 100 milliseconds
    @Value.Default
    public int outlierProfilingInitialDelayMillis() {
        return 10000;
    }

    @Value.Default
    public int outlierProfilingIntervalMillis() {
        return 1000;
    }

    @Value.Derived
    @Json.Ignore
    public String version() {
        return Hashing.sha1().hashString(Marshaling.toJson(this), Charsets.UTF_8).toString();
    }
}
