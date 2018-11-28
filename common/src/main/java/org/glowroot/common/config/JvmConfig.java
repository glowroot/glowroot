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

import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;

import org.glowroot.common.ConfigDefaults;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;

@Value.Immutable
public abstract class JvmConfig {

    @Value.Default
    public ImmutableList<String> maskSystemProperties() {
        return ConfigDefaults.JVM_MASK_SYSTEM_PROPERTIES;
    }

    @Value.Default
    public ImmutableList<String> maskMBeanAttributes() {
        return ConfigDefaults.JVM_MASK_MBEAN_ATTRIBUTES;
    }

    public AgentConfig.JvmConfig toProto() {
        return AgentConfig.JvmConfig.newBuilder()
                .addAllMaskSystemProperty(maskSystemProperties())
                .addAllMaskMbeanAttribute(maskMBeanAttributes())
                .build();
    }

    public static ImmutableJvmConfig create(AgentConfig.JvmConfig config) {
        return ImmutableJvmConfig.builder()
                .addAllMaskSystemProperties(config.getMaskSystemPropertyList())
                .addAllMaskMBeanAttributes(config.getMaskMbeanAttributeList())
                .build();
    }
}
