/*
 * Copyright 2017-2018 the original author or authors.
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
package org.glowroot.common.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import org.immutables.value.Value;

import org.glowroot.common.util.Versions;

@Value.Immutable
public abstract class EmbeddedAdminGeneralConfig {

    private static final String DEFAULT_AGENT_DISPLAY_NAME;

    static {
        // only look at system property, b/c only want to have default agent display name if there
        // are multiple agent.id values for a given installation
        DEFAULT_AGENT_DISPLAY_NAME = System.getProperty("glowroot.agent.id", "");
    }

    @Value.Default
    @JsonInclude(Include.NON_EMPTY)
    public String agentDisplayName() {
        return "";
    }

    @Value.Derived
    @JsonIgnore
    public String agentDisplayNameOrDefault() {
        String agentDisplayName = agentDisplayName();
        return agentDisplayName.isEmpty() ? DEFAULT_AGENT_DISPLAY_NAME : agentDisplayName;
    }

    @Value.Derived
    @JsonIgnore
    public String version() {
        return Versions.getJsonVersion(this);
    }

    public static String defaultAgentDisplayName() {
        return DEFAULT_AGENT_DISPLAY_NAME;
    }
}
