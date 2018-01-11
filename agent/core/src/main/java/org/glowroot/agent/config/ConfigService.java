/*
 * Copyright 2011-2017 the original author or authors.
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

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.config.PropertyValue.PropertyType;
import org.glowroot.agent.plugin.api.config.ConfigListener;
import org.glowroot.agent.util.JavaVersion;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;

public class ConfigService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigService.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private static final long GAUGE_COLLECTION_INTERVAL_MILLIS =
            Long.getLong("glowroot.internal.gaugeCollectionIntervalMillis", 5000);

    private final ConfigFile configFile;
    private final AdminFile adminFile;

    private final ImmutableList<PluginDescriptor> pluginDescriptors;

    private final Set<ConfigListener> configListeners = Sets.newCopyOnWriteArraySet();
    private final Set<ConfigListener> pluginConfigListeners = Sets.newCopyOnWriteArraySet();

    private volatile TransactionConfig transactionConfig;
    private volatile JvmConfig jvmConfig;
    private volatile UiConfig uiConfig;
    private volatile UserRecordingConfig userRecordingConfig;
    private volatile AdvancedConfig advancedConfig;
    private volatile ImmutableList<GaugeConfig> gaugeConfigs;
    private volatile ImmutableList<SyntheticMonitorConfig> syntheticMonitorConfigs;
    private volatile ImmutableList<AlertConfig> alertConfigs;
    private volatile ImmutableList<PluginConfig> pluginConfigs;
    private volatile ImmutableList<InstrumentationConfig> instrumentationConfigs;

    // memory barrier is used to ensure memory visibility of config values
    private volatile boolean memoryBarrier;

    public static ConfigService create(File confDir, List<PluginDescriptor> pluginDescriptors) {
        ConfigService configService = new ConfigService(confDir, pluginDescriptors);
        // it's nice to update config.json on startup if it is missing some/all config
        // properties so that the file contents can be reviewed/updated/copied if desired
        try {
            configService.writeAll();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
        return configService;
    }

    private ConfigService(File confDir, List<PluginDescriptor> pluginDescriptors) {
        configFile = new ConfigFile(new File(confDir, "config.json"));
        adminFile = new AdminFile(new File(confDir, "admin.json"));
        this.pluginDescriptors = ImmutableList.copyOf(pluginDescriptors);
        TransactionConfig transactionConfig =
                configFile.getConfigNode("transactions", ImmutableTransactionConfig.class, mapper);
        if (transactionConfig == null) {
            this.transactionConfig = ImmutableTransactionConfig.builder().build();
        } else {
            this.transactionConfig = transactionConfig;
        }
        JvmConfig jvmConfig = configFile.getConfigNode("jvm", ImmutableJvmConfig.class, mapper);
        if (jvmConfig == null) {
            this.jvmConfig = ImmutableJvmConfig.builder().build();
        } else {
            this.jvmConfig = jvmConfig;
        }
        UiConfig uiConfig = configFile.getConfigNode("ui", ImmutableUiConfig.class, mapper);
        if (uiConfig == null) {
            this.uiConfig = ImmutableUiConfig.builder().build();
        } else {
            this.uiConfig = uiConfig;
        }
        UserRecordingConfig userRecordingConfig =
                configFile.getConfigNode("userRecording", ImmutableUserRecordingConfig.class,
                        mapper);
        if (userRecordingConfig == null) {
            this.userRecordingConfig = ImmutableUserRecordingConfig.builder().build();
        } else {
            this.userRecordingConfig = userRecordingConfig;
        }
        AdvancedConfig advancedConfig =
                configFile.getConfigNode("advanced", ImmutableAdvancedConfig.class, mapper);
        if (advancedConfig == null) {
            this.advancedConfig = ImmutableAdvancedConfig.builder().build();
        } else {
            this.advancedConfig = advancedConfig;
        }
        List<ImmutableGaugeConfig> gaugeConfigs = configFile.getConfigNode("gauges",
                new TypeReference<List<ImmutableGaugeConfig>>() {}, mapper);
        if (gaugeConfigs == null) {
            this.gaugeConfigs = getDefaultGaugeConfigs();
        } else {
            this.gaugeConfigs = ImmutableList.<GaugeConfig>copyOf(gaugeConfigs);
        }
        List<ImmutableSyntheticMonitorConfig> syntheticMonitorConfigs =
                configFile.getConfigNode("syntheticMonitors",
                        new TypeReference<List<ImmutableSyntheticMonitorConfig>>() {}, mapper);
        if (syntheticMonitorConfigs == null) {
            this.syntheticMonitorConfigs = ImmutableList.of();
        } else {
            this.syntheticMonitorConfigs =
                    ImmutableList.<SyntheticMonitorConfig>copyOf(syntheticMonitorConfigs);
        }
        List<ImmutableAlertConfig> alertConfigs = configFile.getConfigNode("alerts",
                new TypeReference<List<ImmutableAlertConfig>>() {}, mapper);
        if (alertConfigs == null) {
            this.alertConfigs = ImmutableList.of();
        } else {
            this.alertConfigs = ImmutableList.<AlertConfig>copyOf(alertConfigs);
        }
        List<ImmutablePluginConfigTemp> pluginConfigs = configFile.getConfigNode("plugins",
                new TypeReference<List<ImmutablePluginConfigTemp>>() {}, mapper);
        this.pluginConfigs = fixPluginConfigs(pluginConfigs, pluginDescriptors);

        List<ImmutableInstrumentationConfig> instrumentationConfigs =
                configFile.getConfigNode("instrumentation",
                        new TypeReference<List<ImmutableInstrumentationConfig>>() {}, mapper);
        if (instrumentationConfigs == null) {
            this.instrumentationConfigs = ImmutableList.of();
        } else {
            this.instrumentationConfigs =
                    ImmutableList.<InstrumentationConfig>copyOf(instrumentationConfigs);
        }

        for (InstrumentationConfig instrumentationConfig : this.instrumentationConfigs) {
            instrumentationConfig.logValidationErrorsIfAny();
        }
    }

    public TransactionConfig getTransactionConfig() {
        return transactionConfig;
    }

    public JvmConfig getJvmConfig() {
        return jvmConfig;
    }

    public UiConfig getUiConfig() {
        return uiConfig;
    }

    public UserRecordingConfig getUserRecordingConfig() {
        return userRecordingConfig;
    }

    public AdvancedConfig getAdvancedConfig() {
        return advancedConfig;
    }

    public List<GaugeConfig> getGaugeConfigs() {
        return gaugeConfigs;
    }

    public ImmutableList<AlertConfig> getAlertConfigs() {
        return alertConfigs;
    }

    public ImmutableList<PluginConfig> getPluginConfigs() {
        return pluginConfigs;
    }

    public @Nullable PluginConfig getPluginConfig(String pluginId) {
        for (PluginConfig pluginConfig : pluginConfigs) {
            if (pluginId.equals(pluginConfig.id())) {
                return pluginConfig;
            }
        }
        return null;
    }

    public List<InstrumentationConfig> getInstrumentationConfigs() {
        return instrumentationConfigs;
    }

    public long getGaugeCollectionIntervalMillis() {
        return GAUGE_COLLECTION_INTERVAL_MILLIS;
    }

    public AgentConfig getAgentConfig() {
        AgentConfig.Builder builder = AgentConfig.newBuilder()
                .setTransactionConfig(transactionConfig.toProto());
        for (GaugeConfig gaugeConfig : gaugeConfigs) {
            builder.addGaugeConfig(gaugeConfig.toProto());
        }
        builder.setJvmConfig(jvmConfig.toProto());
        for (SyntheticMonitorConfig syntheticMonitorConfig : syntheticMonitorConfigs) {
            builder.addSyntheticMonitorConfig(syntheticMonitorConfig.toProto());
        }
        for (AlertConfig alertConfig : alertConfigs) {
            builder.addAlertConfig(alertConfig.toProto());
        }
        builder.setUiConfig(uiConfig.toProto());
        for (PluginConfig pluginConfig : pluginConfigs) {
            builder.addPluginConfig(pluginConfig.toProto());
        }
        for (InstrumentationConfig instrumentationConfig : instrumentationConfigs) {
            builder.addInstrumentationConfig(instrumentationConfig.toProto());
        }
        builder.setUserRecordingConfig(userRecordingConfig.toProto());
        builder.setAdvancedConfig(advancedConfig.toProto());
        return builder.build();
    }

    public void addConfigListener(ConfigListener listener) {
        configListeners.add(listener);
        listener.onChange();
    }

    public void addPluginConfigListener(ConfigListener listener) {
        pluginConfigListeners.add(listener);
    }

    public void updateTransactionConfig(TransactionConfig updatedConfig) throws IOException {
        configFile.writeConfig("transactions", updatedConfig, mapper);
        transactionConfig = updatedConfig;
        notifyConfigListeners();
    }

    public void updateGaugeConfigs(List<GaugeConfig> updatedConfigs) throws IOException {
        configFile.writeConfig("gauges", updatedConfigs, mapper);
        gaugeConfigs = ImmutableList.copyOf(updatedConfigs);
        notifyConfigListeners();
    }

    public void updateJvmConfig(JvmConfig updatedConfig) throws IOException {
        configFile.writeConfig("jvm", updatedConfig, mapper);
        jvmConfig = updatedConfig;
        notifyConfigListeners();
    }

    public void updateSyntheticMonitorConfigs(List<SyntheticMonitorConfig> updatedConfigs)
            throws IOException {
        configFile.writeConfig("syntheticMonitors", updatedConfigs, mapper);
        syntheticMonitorConfigs = ImmutableList.copyOf(updatedConfigs);
        notifyConfigListeners();
    }

    public void updateAlertConfigs(List<AlertConfig> updatedConfigs) throws IOException {
        configFile.writeConfig("alerts", updatedConfigs, mapper);
        alertConfigs = ImmutableList.copyOf(updatedConfigs);
        notifyConfigListeners();
    }

    public void updateUiConfig(UiConfig updatedConfig) throws IOException {
        configFile.writeConfig("ui", updatedConfig, mapper);
        uiConfig = updatedConfig;
        notifyConfigListeners();
    }

    public void updatePluginConfigs(List<PluginConfig> updatedConfigs) throws IOException {
        ImmutableList<PluginConfig> sortedConfigs =
                new PluginConfigOrdering().immutableSortedCopy(updatedConfigs);
        configFile.writeConfig("plugins", sortedConfigs, mapper);
        pluginConfigs = sortedConfigs;
        notifyAllPluginConfigListeners();
    }

    public void updateInstrumentationConfigs(List<InstrumentationConfig> updatedConfigs)
            throws IOException {
        configFile.writeConfig("instrumentation", updatedConfigs, mapper);
        instrumentationConfigs = ImmutableList.copyOf(updatedConfigs);
        notifyConfigListeners();
    }

    public void updateUserRecordingConfig(UserRecordingConfig updatedConfig) throws IOException {
        configFile.writeConfig("userRecording", updatedConfig, mapper);
        userRecordingConfig = updatedConfig;
        notifyConfigListeners();
    }

    public void updateAdvancedConfig(AdvancedConfig updatedConfig) throws IOException {
        configFile.writeConfig("advanced", updatedConfig, mapper);
        advancedConfig = updatedConfig;
        notifyConfigListeners();
    }

    public <T extends /*@NonNull*/ Object> /*@Nullable*/ T getAdminConfig(String key,
            Class<T> clazz) {
        return adminFile.getAdminNode(key, clazz, mapper);
    }

    public <T extends /*@NonNull*/ Object> /*@Nullable*/ T getAdminConfig(String key,
            TypeReference<T> typeReference) {
        return adminFile.getAdminNode(key, typeReference, mapper);
    }

    public void updateAdminConfig(String key, Object config) throws IOException {
        adminFile.writeAdmin(key, config, mapper);
    }

    public void updateAdminConfigs(Map<String, Object> configs) throws IOException {
        adminFile.writeAdmin(configs, mapper);
    }

    public boolean readMemoryBarrier() {
        return memoryBarrier;
    }

    public void writeMemoryBarrier() {
        memoryBarrier = true;
    }

    // the updated config is not passed to the listeners to avoid the race condition of multiple
    // config updates being sent out of order, instead listeners must call get*Config() which will
    // never return the updates out of order (at worst it may return the most recent update twice
    // which is ok)
    private void notifyConfigListeners() {
        for (ConfigListener configListener : configListeners) {
            configListener.onChange();
        }
    }

    private void notifyAllPluginConfigListeners() {
        for (ConfigListener listener : pluginConfigListeners) {
            listener.onChange();
        }
        writeMemoryBarrier();
    }

    @OnlyUsedByTests
    public void setSlowThresholdToZero() throws IOException {
        transactionConfig = ImmutableTransactionConfig.copyOf(transactionConfig)
                .withSlowThresholdMillis(0);
        writeAll();
        notifyConfigListeners();
    }

    @OnlyUsedByTests
    public void resetConfig() throws IOException {
        transactionConfig = ImmutableTransactionConfig.builder()
                .slowThresholdMillis(0)
                .build();
        jvmConfig = ImmutableJvmConfig.builder().build();
        uiConfig = ImmutableUiConfig.builder().build();
        userRecordingConfig = ImmutableUserRecordingConfig.builder().build();
        advancedConfig = ImmutableAdvancedConfig.builder().build();
        gaugeConfigs = getDefaultGaugeConfigs();
        syntheticMonitorConfigs = ImmutableList.of();
        alertConfigs = ImmutableList.of();
        pluginConfigs =
                fixPluginConfigs(ImmutableList.<ImmutablePluginConfigTemp>of(), pluginDescriptors);
        instrumentationConfigs = ImmutableList.of();
        writeAll();
        notifyConfigListeners();
        notifyAllPluginConfigListeners();
    }

    private void writeAll() throws IOException {
        // linked hash map to preserve ordering when writing to config file
        Map<String, Object> configs = Maps.newLinkedHashMap();
        configs.put("transactions", transactionConfig);
        configs.put("jvm", jvmConfig);
        configs.put("ui", uiConfig);
        configs.put("userRecording", userRecordingConfig);
        configs.put("advanced", advancedConfig);
        configs.put("gauges", gaugeConfigs);
        configs.put("syntheticMonitors", syntheticMonitorConfigs);
        configs.put("alerts", alertConfigs);
        configs.put("plugins", pluginConfigs);
        configs.put("instrumentation", instrumentationConfigs);
        configFile.writeConfig(configs, mapper);
    }

    private static ImmutableList<GaugeConfig> getDefaultGaugeConfigs() {
        List<GaugeConfig> defaultGaugeConfigs = Lists.newArrayList();
        defaultGaugeConfigs.add(ImmutableGaugeConfig.builder()
                .mbeanObjectName("java.lang:type=Memory")
                .addMbeanAttributes(ImmutableMBeanAttribute.of("HeapMemoryUsage.used", false))
                .build());
        defaultGaugeConfigs.add(ImmutableGaugeConfig.builder()
                .mbeanObjectName("java.lang:type=GarbageCollector,name=*")
                .addMbeanAttributes(ImmutableMBeanAttribute.of("CollectionCount", true))
                .addMbeanAttributes(ImmutableMBeanAttribute.of("CollectionTime", true))
                .build());
        defaultGaugeConfigs.add(ImmutableGaugeConfig.builder()
                .mbeanObjectName("java.lang:type=MemoryPool,name=*")
                .addMbeanAttributes(ImmutableMBeanAttribute.of("Usage.used", false))
                .build());
        ImmutableGaugeConfig.Builder operatingSystemMBean = ImmutableGaugeConfig.builder()
                .mbeanObjectName("java.lang:type=OperatingSystem")
                .addMbeanAttributes(ImmutableMBeanAttribute.of("FreePhysicalMemorySize", false));
        if (!JavaVersion.isJava6()) {
            // these are only available since 1.7
            operatingSystemMBean
                    .addMbeanAttributes(ImmutableMBeanAttribute.of("ProcessCpuLoad", false));
            operatingSystemMBean
                    .addMbeanAttributes(ImmutableMBeanAttribute.of("SystemCpuLoad", false));
        }
        defaultGaugeConfigs.add(operatingSystemMBean.build());
        return ImmutableList.copyOf(defaultGaugeConfigs);
    }

    private static ImmutableList<PluginConfig> fixPluginConfigs(
            @Nullable List<ImmutablePluginConfigTemp> filePluginConfigs,
            List<PluginDescriptor> pluginDescriptors) {

        // sorted by id for writing to config file
        List<PluginDescriptor> sortedPluginDescriptors =
                new PluginDescriptorOrdering().immutableSortedCopy(pluginDescriptors);

        Map<String, PluginConfigTemp> filePluginConfigMap = Maps.newHashMap();
        if (filePluginConfigs != null) {
            for (ImmutablePluginConfigTemp pluginConfig : filePluginConfigs) {
                filePluginConfigMap.put(pluginConfig.id(), pluginConfig);
            }
        }

        List<PluginConfig> accuratePluginConfigs = Lists.newArrayList();
        for (PluginDescriptor pluginDescriptor : sortedPluginDescriptors) {
            PluginConfigTemp filePluginConfig = filePluginConfigMap.get(pluginDescriptor.id());
            ImmutablePluginConfig.Builder builder = ImmutablePluginConfig.builder()
                    .pluginDescriptor(pluginDescriptor);
            for (PropertyDescriptor propertyDescriptor : pluginDescriptor.properties()) {
                builder.putProperties(propertyDescriptor.name(),
                        getPropertyValue(filePluginConfig, propertyDescriptor));
            }
            accuratePluginConfigs.add(builder.build());
        }
        return ImmutableList.copyOf(accuratePluginConfigs);
    }

    private static PropertyValue getPropertyValue(@Nullable PluginConfigTemp pluginConfig,
            PropertyDescriptor propertyDescriptor) {
        if (pluginConfig == null) {
            return propertyDescriptor.getValidatedNonNullDefaultValue();
        }
        PropertyValue propertyValue = getValidatedPropertyValue(pluginConfig.properties(),
                propertyDescriptor.name(), propertyDescriptor.type());
        if (propertyValue == null) {
            return propertyDescriptor.getValidatedNonNullDefaultValue();
        }
        return propertyValue;
    }

    private static @Nullable PropertyValue getValidatedPropertyValue(
            Map<String, PropertyValue> properties, String propertyName, PropertyType propertyType) {
        PropertyValue propertyValue = properties.get(propertyName);
        if (propertyValue == null) {
            return null;
        }
        Object value = propertyValue.value();
        if (value == null) {
            return PropertyValue.getDefaultValue(propertyType);
        }
        if (PropertyDescriptor.isValidType(value, propertyType)) {
            return propertyValue;
        } else {
            logger.warn("invalid value for plugin property: {}", propertyName);
            return PropertyValue.getDefaultValue(propertyType);
        }
    }

    private static class PluginDescriptorOrdering extends Ordering<PluginDescriptor> {
        @Override
        public int compare(PluginDescriptor left, PluginDescriptor right) {
            return left.id().compareToIgnoreCase(right.id());
        }
    }

    private static class PluginConfigOrdering extends Ordering<PluginConfig> {
        @Override
        public int compare(PluginConfig left, PluginConfig right) {
            return left.id().compareToIgnoreCase(right.id());
        }
    }

    @Value.Immutable
    interface PluginConfigTemp {
        String id();
        Map<String, PropertyValue> properties();
    }
}
