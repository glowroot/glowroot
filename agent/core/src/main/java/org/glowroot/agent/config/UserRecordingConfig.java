/*
 * Copyright 2012-2017 the original author or authors.
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

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;

import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.Proto.OptionalInt32;

@Value.Immutable
public abstract class UserRecordingConfig {

    @JsonInclude(Include.NON_EMPTY)
    public abstract ImmutableList<String> users();

    @JsonInclude(Include.NON_NULL)
    public abstract @Nullable Integer profilingIntervalMillis();

    public AgentConfig.UserRecordingConfig toProto() {
        AgentConfig.UserRecordingConfig.Builder builder =
                AgentConfig.UserRecordingConfig.newBuilder()
                        .addAllUser(users());
        Integer profilingIntervalMillis = profilingIntervalMillis();
        if (profilingIntervalMillis != null) {
            builder.setProfilingIntervalMillis(
                    OptionalInt32.newBuilder().setValue(profilingIntervalMillis));
        }
        return builder.build();
    }

    public static UserRecordingConfig create(AgentConfig.UserRecordingConfig config) {
        ImmutableUserRecordingConfig.Builder builder = ImmutableUserRecordingConfig.builder()
                .addAllUsers(config.getUserList());
        if (config.hasProfilingIntervalMillis()) {
            builder.profilingIntervalMillis(config.getProfilingIntervalMillis().getValue());
        }
        return builder.build();
    }
}
