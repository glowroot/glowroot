/*
 * Copyright 2015-2016 the original author or authors.
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
package org.glowroot.agent.it.harness.impl;

import java.util.List;
import java.util.ListIterator;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import org.glowroot.agent.it.harness.ConfigService;
import org.glowroot.agent.util.JavaVersion;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AdvancedConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.GaugeConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.InstrumentationConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.MBeanAttribute;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginProperty;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.TransactionConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.UserRecordingConfig;
import org.glowroot.wire.api.model.Proto.OptionalInt32;

class ConfigServiceImpl implements ConfigService {

    private final GrpcServerWrapper server;
    private final boolean reweavable;

    ConfigServiceImpl(GrpcServerWrapper server, boolean reweavable) {
        this.server = server;
        this.reweavable = reweavable;
    }

    @Override
    public void updateTransactionConfig(TransactionConfig config) throws Exception {
        AgentConfig agentConfig = server.getAgentConfig();
        server.updateAgentConfig(agentConfig.toBuilder()
                .setTransactionConfig(config)
                .build());
    }

    @Override
    public void setPluginProperty(String pluginId, String propertyName, boolean propertyValue)
            throws Exception {
        updatePluginConfig(pluginId, propertyName,
                PluginProperty.Value.newBuilder().setBval(propertyValue).build());
    }

    @Override
    public void setPluginProperty(String pluginId, String propertyName,
            @Nullable Double propertyValue) throws Exception {
        if (propertyValue == null) {
            updatePluginConfig(pluginId, propertyName,
                    PluginProperty.Value.newBuilder().setDvalNull(true).build());
        } else {
            updatePluginConfig(pluginId, propertyName,
                    PluginProperty.Value.newBuilder().setDval(propertyValue).build());
        }
    }

    @Override
    public void setPluginProperty(String pluginId, String propertyName, String propertyValue)
            throws Exception {
        updatePluginConfig(pluginId, propertyName,
                PluginProperty.Value.newBuilder().setSval(propertyValue).build());
    }

    @Override
    public int updateInstrumentationConfigs(List<InstrumentationConfig> configs) throws Exception {
        AgentConfig agentConfig = server.getAgentConfig();
        server.updateAgentConfig(agentConfig.toBuilder()
                .clearInstrumentationConfig()
                .addAllInstrumentationConfig(configs)
                .build());
        if (reweavable) {
            return server.reweave();
        } else {
            return 0;
        }
    }

    @Override
    public void updateUserRecordingConfig(UserRecordingConfig config) throws Exception {
        AgentConfig agentConfig = server.getAgentConfig();
        server.updateAgentConfig(agentConfig.toBuilder()
                .setUserRecordingConfig(config)
                .build());
    }

    @Override
    public void updateAdvancedConfig(AdvancedConfig config) throws Exception {
        AgentConfig agentConfig = server.getAgentConfig();
        server.updateAgentConfig(agentConfig.toBuilder()
                .setAdvancedConfig(config)
                .build());
    }

    void setSlowThresholdToZero() throws Exception {
        AgentConfig agentConfig = server.getAgentConfig();
        server.updateAgentConfig(agentConfig.toBuilder()
                .setTransactionConfig(agentConfig.getTransactionConfig().toBuilder()
                        .setSlowThresholdMillis(of(0)))
                .build());
    }

    void resetConfig() throws Exception {
        AgentConfig.Builder builder = AgentConfig.newBuilder()
                .setTransactionConfig(getDefaultTransactionConfig())
                .setAdvancedConfig(getDefaultAdvancedConfig());
        builder.addAllGaugeConfig(getDefaultGaugeConfigs());
        for (PluginConfig pluginConfig : server.getAgentConfig().getPluginConfigList()) {
            PluginConfig.Builder pluginConfigBuilder = PluginConfig.newBuilder()
                    .setId(pluginConfig.getId());
            for (PluginProperty pluginProperty : pluginConfig.getPropertyList()) {
                pluginConfigBuilder.addProperty(pluginProperty.toBuilder()
                        .setValue(pluginProperty.getDefault()));
            }
            builder.addPluginConfig(pluginConfigBuilder.build());
        }
        server.updateAgentConfig(builder.build());
    }

    private void updatePluginConfig(String pluginId, String name, PluginProperty.Value value)
            throws Exception {
        PluginConfig pluginConfig = getPluginConfig(pluginId);
        List<PluginProperty> properties = Lists.newArrayList(pluginConfig.getPropertyList());
        ListIterator<PluginProperty> i = properties.listIterator();
        boolean found = false;
        while (i.hasNext()) {
            PluginProperty existingPluginProperty = i.next();
            if (existingPluginProperty.getName().equals(name)) {
                i.set(existingPluginProperty.toBuilder()
                        .setValue(value)
                        .build());
                found = true;
                break;
            }
        }
        if (!found) {
            throw new IllegalStateException("Could not find plugin property with name: " + name);
        }
        updatePluginConfig(pluginConfig.toBuilder()
                .clearProperty()
                .addAllProperty(properties)
                .build());
    }

    private PluginConfig getPluginConfig(String pluginId) throws InterruptedException {
        AgentConfig agentConfig = server.getAgentConfig();
        for (PluginConfig pluginConfig : agentConfig.getPluginConfigList()) {
            if (pluginConfig.getId().equals(pluginId)) {
                return pluginConfig;
            }
        }
        throw new IllegalStateException("Could not find plugin with id: " + pluginId);
    }

    private void updatePluginConfig(PluginConfig config) throws Exception {
        AgentConfig agentConfig = server.getAgentConfig();
        List<PluginConfig> pluginConfigs = Lists.newArrayList(agentConfig.getPluginConfigList());
        ListIterator<PluginConfig> i = pluginConfigs.listIterator();
        boolean found = false;
        while (i.hasNext()) {
            if (i.next().getId().equals(config.getId())) {
                i.set(config);
                found = true;
                break;
            }
        }
        if (!found) {
            throw new IllegalStateException("Could not find plugin with id: " + config.getId());
        }
        server.updateAgentConfig(agentConfig.toBuilder()
                .clearPluginConfig()
                .addAllPluginConfig(pluginConfigs)
                .build());
    }

    private static TransactionConfig getDefaultTransactionConfig() {
        // TODO this needs to be kept in sync with default values
        return TransactionConfig.newBuilder()
                .setProfilingIntervalMillis(of(1000))
                .setSlowThresholdMillis(of(0))
                .setCaptureThreadStats(true)
                .build();
    }

    private static AdvancedConfig getDefaultAdvancedConfig() {
        // TODO this needs to be kept in sync with default values
        return AdvancedConfig.newBuilder()
                .setWeavingTimer(false)
                .setImmediatePartialStoreThresholdSeconds(of(60))
                .setMaxAggregateTransactionsPerType(of(500))
                .setMaxAggregateQueriesPerType(of(500))
                .setMaxTraceEntriesPerTransaction(of(2000))
                .setMaxStackTraceSamplesPerTransaction(of(10000))
                .setMbeanGaugeNotFoundDelaySeconds(of(60))
                .build();
    }

    private static List<GaugeConfig> getDefaultGaugeConfigs() {
        // TODO this needs to be kept in sync with default values
        List<GaugeConfig> defaultGaugeConfigs = Lists.newArrayList();
        defaultGaugeConfigs.add(GaugeConfig.newBuilder()
                .setMbeanObjectName("java.lang:type=Memory")
                .addMbeanAttribute(MBeanAttribute.newBuilder()
                        .setName("HeapMemoryUsage.used"))
                .build());
        defaultGaugeConfigs.add(GaugeConfig.newBuilder()
                .setMbeanObjectName("java.lang:type=GarbageCollector,name=*")
                .addMbeanAttribute(MBeanAttribute.newBuilder()
                        .setName("CollectionCount")
                        .setCounter(true))
                .addMbeanAttribute(MBeanAttribute.newBuilder()
                        .setName("CollectionTime")
                        .setCounter(true))
                .build());
        defaultGaugeConfigs.add(GaugeConfig.newBuilder()
                .setMbeanObjectName("java.lang:type=MemoryPool,name=*")
                .addMbeanAttribute(MBeanAttribute.newBuilder()
                        .setName("Usage.used"))
                .build());
        GaugeConfig.Builder operatingSystemMBean = GaugeConfig.newBuilder()
                .setMbeanObjectName("java.lang:type=OperatingSystem")
                .addMbeanAttribute(MBeanAttribute.newBuilder()
                        .setName("FreePhysicalMemorySize"));
        if (!JavaVersion.isJava6()) {
            // these are only available since 1.7
            operatingSystemMBean.addMbeanAttribute(MBeanAttribute.newBuilder()
                    .setName("ProcessCpuLoad"));
            operatingSystemMBean.addMbeanAttribute(MBeanAttribute.newBuilder()
                    .setName("SystemCpuLoad"));
        }
        defaultGaugeConfigs.add(operatingSystemMBean.build());
        return ImmutableList.copyOf(defaultGaugeConfigs);
    }

    private static OptionalInt32 of(int value) {
        return OptionalInt32.newBuilder().setValue(value).build();
    }
}
