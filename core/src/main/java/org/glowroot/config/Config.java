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

import java.util.List;

import org.immutables.value.Json;
import org.immutables.value.Value;

@Value.Immutable
@Json.Marshaled
abstract class Config {

    @Value.Default
    @Json.Named("trace")
    TraceConfig traceConfig() {
        return ImmutableTraceConfig.builder().build();
    }

    @Value.Default
    @Json.Named("profiling")
    ProfilingConfig profilingConfig() {
        return ImmutableProfilingConfig.builder().build();
    }

    @Value.Default
    @Json.Named("userRecording")
    UserRecordingConfig userRecordingConfig() {
        return ImmutableUserRecordingConfig.builder().build();
    }

    @Value.Default
    @Json.Named("storage")
    StorageConfig storageConfig() {
        return ImmutableStorageConfig.builder().build();
    }

    @Value.Default
    @Json.Named("ui")
    UserInterfaceConfig userInterfaceConfig() {
        return ImmutableUserInterfaceConfig.builder().build();
    }

    @Value.Default
    @Json.Named("advanced")
    AdvancedConfig advancedConfig() {
        return ImmutableAdvancedConfig.builder().build();
    }

    @Json.Named("plugins")
    abstract List<PluginConfig> pluginConfigs();

    abstract List<Gauge> gauges();
    abstract List<CapturePoint> capturePoints();
}
