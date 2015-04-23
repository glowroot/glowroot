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
package org.glowroot.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.immutables.value.Value;

@Value.Immutable
@JsonSerialize
public abstract class AdvancedConfigBase {

    @Value.Default
    public boolean timerWrapperMethods() {
        return false;
    }

    @Value.Default
    public boolean weavingTimer() {
        return false;
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
    @JsonIgnore
    public String version() {
        return Versions.getVersion(this);
    }
}
