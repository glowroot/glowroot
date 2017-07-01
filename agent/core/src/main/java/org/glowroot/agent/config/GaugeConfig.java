/*
 * Copyright 2014-2017 the original author or authors.
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
import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;

import org.glowroot.common.util.Styles;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;

@Value.Immutable
public abstract class GaugeConfig {

    public abstract String mbeanObjectName();
    public abstract ImmutableList<ImmutableMBeanAttribute> mbeanAttributes();

    public AgentConfig.GaugeConfig toProto() {
        AgentConfig.GaugeConfig.Builder builder = AgentConfig.GaugeConfig.newBuilder()
                .setMbeanObjectName(mbeanObjectName());
        for (MBeanAttribute mbeanAttribute : mbeanAttributes()) {
            builder.addMbeanAttribute(AgentConfig.MBeanAttribute.newBuilder()
                    .setName(mbeanAttribute.name())
                    .setCounter(mbeanAttribute.counter()));
        }
        return builder.build();
    }

    public static GaugeConfig create(AgentConfig.GaugeConfig config) {
        ImmutableGaugeConfig.Builder builder = ImmutableGaugeConfig.builder()
                .mbeanObjectName(config.getMbeanObjectName());
        for (AgentConfig.MBeanAttribute mbeanAttribute : config.getMbeanAttributeList()) {
            builder.addMbeanAttributes(ImmutableMBeanAttribute.builder()
                    .name(mbeanAttribute.getName())
                    .counter(mbeanAttribute.getCounter())
                    .build());
        }
        return builder.build();
    }

    @Value.Immutable
    @Styles.AllParameters
    public abstract static class MBeanAttribute {

        public abstract String name();

        @Value.Default
        @JsonInclude(Include.NON_EMPTY)
        public boolean counter() {
            return false;
        }
    }
}
