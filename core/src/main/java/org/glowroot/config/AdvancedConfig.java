/*
 * Copyright 2013-2014 the original author or authors.
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
public abstract class AdvancedConfig {

    @Value.Default
    public boolean metricWrapperMethods() {
        return true;
    }

    @Value.Default
    public int immediatePartialStoreThresholdSeconds() {
        return 60;
    }

    // used to limit memory requirement, also used to help limit trace capture size
    @Value.Default
    public int maxTraceEntriesPerTransaction() {
        return 2000;
    }

    // used to limit memory requirement, also used to help limit trace capture size
    @Value.Default
    public int maxStackTraceSamplesPerTransaction() {
        return 10000;
    }

    @Value.Default
    public boolean captureThreadInfo() {
        return true;
    }

    @Value.Default
    public boolean captureGcInfo() {
        return true;
    }

    @Value.Default
    public int mbeanGaugeNotFoundDelaySeconds() {
        return 60;
    }

    @Value.Default
    public int internalQueryTimeoutSeconds() {
        return 60;
    }

    @Value.Derived
    @Json.Ignore
    public String version() {
        return Hashing.sha1().hashString(Marshaling.toJson(this), Charsets.UTF_8).toString();
    }
}
