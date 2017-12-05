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
package org.glowroot.agent.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import org.immutables.value.Value;

import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.SyntheticMonitorConfig.SyntheticMonitorKind;

@Value.Immutable
public abstract class SyntheticMonitorConfig {

    public abstract String id();

    public abstract SyntheticMonitorKind kind();

    // optional for type ping, required for type java
    @Value.Default
    @JsonInclude(Include.NON_EMPTY)
    public String display() {
        return "";
    }

    // === ping synthetic monitors ===

    @Value.Default
    @JsonInclude(Include.NON_EMPTY)
    public String pingUrl() {
        return "";
    }

    // === java synthetic monitors ===

    @Value.Default
    @JsonInclude(Include.NON_EMPTY)
    public String javaSource() {
        return "";
    }

    public static SyntheticMonitorConfig create(AgentConfig.SyntheticMonitorConfig config) {
        return ImmutableSyntheticMonitorConfig.builder()
                .id(config.getId())
                .kind(config.getKind())
                .display(config.getDisplay())
                .pingUrl(config.getPingUrl())
                .javaSource(config.getJavaSource())
                .build();
    }

    AgentConfig.SyntheticMonitorConfig toProto() {
        return AgentConfig.SyntheticMonitorConfig.newBuilder()
                .setId(id())
                .setDisplay(display())
                .setKind(kind())
                .setPingUrl(pingUrl())
                .setJavaSource(javaSource())
                .build();
    }
}
