/*
 * Copyright 2011-2019 the original author or authors.
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

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.plugin.api.config.ConfigListener;
import org.glowroot.agent.util.JavaVersion;
import org.glowroot.common.config.AdvancedConfig;
import org.glowroot.common.config.AlertConfig;
import org.glowroot.common.config.GaugeConfig;
import org.glowroot.common.config.ImmutableAdvancedConfig;
import org.glowroot.common.config.ImmutableAlertConfig;
import org.glowroot.common.config.ImmutableGaugeConfig;
import org.glowroot.common.config.ImmutableInstrumentationConfig;
import org.glowroot.common.config.ImmutableJvmConfig;
import org.glowroot.common.config.ImmutableMBeanAttribute;
import org.glowroot.common.config.ImmutableSyntheticMonitorConfig;
import org.glowroot.common.config.ImmutableTransactionConfig;
import org.glowroot.common.config.ImmutableUiDefaultsConfig;
import org.glowroot.common.config.ImmutableUserRecordingConfig;
import org.glowroot.common.config.InstrumentationConfig;
import org.glowroot.common.config.JvmConfig;
import org.glowroot.common.config.PropertyValue;
import org.glowroot.common.config.PropertyValue.PropertyType;
import org.glowroot.common.config.SyntheticMonitorConfig;
import org.glowroot.common.config.TransactionConfig;
import org.glowroot.common.config.UiDefaultsConfig;
import org.glowroot.common.config.UserRecordingConfig;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;

public class ConfigService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigService.class);

    private static final long GAUGE_COLLECTION_INTERVAL_MILLIS =
            Long.getLong("glowroot.internal.gaugeCollectionIntervalMillis", 5000);

    private final ConfigFile configFile;

    private final ImmutableList<PluginDescriptor> pluginDescriptors;

    private final Set<ConfigListener> configListeners = Sets.newCopyOnWriteArraySet();
    private final Set<ConfigListener> pluginConfigListeners = Sets.newCopyOnWriteArraySet();

    private volatile TransactionConfig transactionConfig;
    private volatile JvmConfig jvmConfig;
    private volatile UiDefaultsConfig uiDefaultsConfig;
    private volatile UserRecordingConfig userRecordingConfig;
    private volatile AdvancedConfig advancedConfig;
    private volatile ImmutableList<GaugeConfig> gaugeConfigs;
    private volatile ImmutableList<SyntheticMonitorConfig> syntheticMonitorConfigs;
    private volatile ImmutableList<AlertConfig> alertConfigs;
    private volatile ImmutableList<PluginConfig> pluginConfigs;
    private volatile ImmutableList<InstrumentationConfig> instrumentationConfigs;

    // memory barrier is used to ensure memory visibility of config values
    private volatile boolean memoryBarrier;

    public static ConfigService create(List<File> confDirs, boolean configReadOnly,
            List<PluginDescriptor> pluginDescriptors) {
        ConfigService configService =
                new ConfigService(confDirs, configReadOnly, pluginDescriptors);
        // it's nice to update config.json on startup if it is missing some/all config properties so
        // that the file contents can be reviewed/updated/copied if desired
        try {
            configService.writeAll();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
        return configService;
    }

    private ConfigService(List<File> confDirs, boolean configReadOnly,
            List<PluginDescriptor> pluginDescriptors) {
        configFile = new ConfigFile(confDirs, configReadOnly);
        this.pluginDescriptors = ImmutableList.copyOf(pluginDescriptors);
        TransactionConfig transactionConfig =
                configFile.getConfig("transactions", ImmutableTransactionConfig.class);
        if (transactionConfig == null) {
            this.transactionConfig = ImmutableTransactionConfig.builder().build();
        } else {
            this.transactionConfig = transactionConfig;
        }
        JvmConfig jvmConfig = configFile.getConfig("jvm", ImmutableJvmConfig.class);
        if (jvmConfig == null) {
            this.jvmConfig = ImmutableJvmConfig.builder().build();
        } else {
            this.jvmConfig = jvmConfig;
        }
        UiDefaultsConfig uiDefaultsConfig =
                configFile.getConfig("uiDefaults", ImmutableUiDefaultsConfig.class);
        if (uiDefaultsConfig == null) {
            this.uiDefaultsConfig = ImmutableUiDefaultsConfig.builder().build();
        } else {
            this.uiDefaultsConfig = uiDefaultsConfig;
        }
        UserRecordingConfig userRecordingConfig =
                configFile.getConfig("userRecording", ImmutableUserRecordingConfig.class);
        if (userRecordingConfig == null) {
            this.userRecordingConfig = ImmutableUserRecordingConfig.builder().build();
        } else {
            this.userRecordingConfig = userRecordingConfig;
        }
        AdvancedConfig advancedConfig =
                configFile.getConfig("advanced", ImmutableAdvancedConfig.class);
        if (advancedConfig == null) {
            this.advancedConfig = ImmutableAdvancedConfig.builder().build();
        } else {
            this.advancedConfig = advancedConfig;
        }
        List<ImmutableGaugeConfig> gaugeConfigs =
                configFile.getConfig("gauges", new TypeReference<List<ImmutableGaugeConfig>>() {});
        if (gaugeConfigs == null) {
            this.gaugeConfigs = getDefaultGaugeConfigs();
        } else {
            this.gaugeConfigs = ImmutableList.<GaugeConfig>copyOf(gaugeConfigs);
        }
        List<ImmutableSyntheticMonitorConfig> syntheticMonitorConfigs = configFile.getConfig(
                "syntheticMonitors", new TypeReference<List<ImmutableSyntheticMonitorConfig>>() {});
        if (syntheticMonitorConfigs == null) {
            this.syntheticMonitorConfigs = ImmutableList.of();
        } else {
            this.syntheticMonitorConfigs =
                    ImmutableList.<SyntheticMonitorConfig>copyOf(syntheticMonitorConfigs);
        }
        List<ImmutableAlertConfig> alertConfigs =
                configFile.getConfig("alerts", new TypeReference<List<ImmutableAlertConfig>>() {});
        if (alertConfigs == null) {
            this.alertConfigs = ImmutableList.of();
        } else {
            this.alertConfigs = ImmutableList.<AlertConfig>copyOf(alertConfigs);
        }
        List<ImmutablePluginConfigTemp> pluginConfigs = configFile.getConfig("plugins",
                new TypeReference<List<ImmutablePluginConfigTemp>>() {});
        this.pluginConfigs = fixPluginConfigs(pluginConfigs, pluginDescriptors);

        List<ImmutableInstrumentationConfig> instrumentationConfigs = configFile.getConfig(
                "instrumentation", new TypeReference<List<ImmutableInstrumentationConfig>>() {});
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

    public UiDefaultsConfig getUiDefaultsConfig() {
        return uiDefaultsConfig;
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
        builder.setUiDefaultsConfig(uiDefaultsConfig.toProto());
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

    public void updateTransactionConfig(TransactionConfig config) throws IOException {
        configFile.writeConfig("transactions", config);
        transactionConfig = config;
        notifyConfigListeners();
    }

    public void updateGaugeConfigs(List<GaugeConfig> configs) throws IOException {
        configFile.writeConfig("gauges", configs);
        gaugeConfigs = ImmutableList.copyOf(configs);
        notifyConfigListeners();
    }

    public void updateJvmConfig(JvmConfig config) throws IOException {
        configFile.writeConfig("jvm", config);
        jvmConfig = config;
        notifyConfigListeners();
    }

    public void updateSyntheticMonitorConfigs(List<SyntheticMonitorConfig> configs)
            throws IOException {
        configFile.writeConfig("syntheticMonitors", configs);
        syntheticMonitorConfigs = ImmutableList.copyOf(configs);
        notifyConfigListeners();
    }

    public void updateAlertConfigs(List<AlertConfig> configs) throws IOException {
        configFile.writeConfig("alerts", configs);
        alertConfigs = ImmutableList.copyOf(configs);
        notifyConfigListeners();
    }

    public void updateUiDefaultsConfig(UiDefaultsConfig config) throws IOException {
        configFile.writeConfig("uiDefaults", config);
        uiDefaultsConfig = config;
        notifyConfigListeners();
    }

    public void updatePluginConfigs(List<PluginConfig> configs) throws IOException {
        // configs passed in are already sorted
        configFile.writeConfig("plugins", stripEmptyPluginConfigs(configs));
        pluginConfigs = ImmutableList.copyOf(configs);
        notifyAllPluginConfigListeners();
    }

    public void updateInstrumentationConfigs(List<InstrumentationConfig> configs)
            throws IOException {
        configFile.writeConfig("instrumentation", configs);
        instrumentationConfigs = ImmutableList.copyOf(configs);
        notifyConfigListeners();
    }

    public void updateUserRecordingConfig(UserRecordingConfig config) throws IOException {
        configFile.writeConfig("userRecording", config);
        userRecordingConfig = config;
        notifyConfigListeners();
    }

    public void updateAdvancedConfig(AdvancedConfig config) throws IOException {
        configFile.writeConfig("advanced", config);
        advancedConfig = config;
        notifyConfigListeners();
    }

    public void updateAllConfig(AllConfig config) throws IOException {
        Map<String, Object> configs = Maps.newHashMap();
        configs.put("transactions", config.transaction());
        configs.put("jvm", config.jvm());
        configs.put("uiDefaults", config.uiDefaults());
        configs.put("userRecording", config.userRecording());
        configs.put("advanced", config.advanced());
        configs.put("gauges", config.gauges());
        configs.put("syntheticMonitors", config.syntheticMonitors());
        configs.put("alerts", config.alerts());
        configs.put("plugins", stripEmptyPluginConfigs(config.plugins()));
        configs.put("instrumentation", config.instrumentation());
        configFile.writeAllConfigs(configs);
        this.transactionConfig = config.transaction();
        this.jvmConfig = config.jvm();
        this.uiDefaultsConfig = config.uiDefaults();
        this.userRecordingConfig = config.userRecording();
        this.advancedConfig = config.advanced();
        this.gaugeConfigs = ImmutableList.copyOf(config.gauges());
        this.syntheticMonitorConfigs = ImmutableList.copyOf(config.syntheticMonitors());
        this.alertConfigs = ImmutableList.copyOf(config.alerts());
        this.pluginConfigs = ImmutableList.copyOf(config.plugins());
        this.instrumentationConfigs = ImmutableList.copyOf(config.instrumentation());
        notifyConfigListeners();
        notifyAllPluginConfigListeners();
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
    public void initConfigForTests() throws IOException {
        transactionConfig = ImmutableTransactionConfig.copyOf(transactionConfig)
                .withSlowThresholdMillis(0);
        writeAll();
        notifyConfigListeners();
    }

    @OnlyUsedByTests
    public void resetConfigForTests() throws IOException {
        transactionConfig = ImmutableTransactionConfig.builder()
                .slowThresholdMillis(0) // default for tests
                .build();
        jvmConfig = ImmutableJvmConfig.builder().build();
        uiDefaultsConfig = ImmutableUiDefaultsConfig.builder().build();
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
        Map<String, Object> configs = Maps.newHashMap();
        configs.put("transactions", transactionConfig);
        configs.put("jvm", jvmConfig);
        configs.put("uiDefaults", uiDefaultsConfig);
        configs.put("userRecording", userRecordingConfig);
        configs.put("advanced", advancedConfig);
        configs.put("gauges", gaugeConfigs);
        configs.put("syntheticMonitors", syntheticMonitorConfigs);
        configs.put("alerts", alertConfigs);
        configs.put("plugins", stripEmptyPluginConfigs(pluginConfigs));
        configs.put("instrumentation", instrumentationConfigs);
        configFile.writeAllConfigsOnStartup(configs);
    }

    private static List<PluginConfig> stripEmptyPluginConfigs(List<PluginConfig> configs) {
        List<PluginConfig> nonEmptyConfigs = Lists.newArrayList();
        for (PluginConfig config : configs) {
            if (!config.properties().isEmpty()) {
                nonEmptyConfigs.add(config);
            }
        }
        return nonEmptyConfigs;
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

    public static ImmutableList<PluginConfig> fixPluginConfigs(
            @Nullable List<ImmutablePluginConfigTemp> filePluginConfigs,
            List<PluginDescriptor> pluginDescriptors) {

        Map<String, PluginConfigTemp> filePluginConfigMap = Maps.newHashMap();
        if (filePluginConfigs != null) {
            for (ImmutablePluginConfigTemp pluginConfig : filePluginConfigs) {
                filePluginConfigMap.put(pluginConfig.id(), pluginConfig);
            }
        }

        List<PluginConfig> accuratePluginConfigs = Lists.newArrayList();
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
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
            return PropertyDescriptor.getDefaultValue(propertyType);
        }
        if (PropertyDescriptor.isValidType(value, propertyType)) {
            return propertyValue;
        } else if (value instanceof String && propertyType == PropertyType.LIST) {
            // handle upgrading from comma-separated string properties to list properties
            return new PropertyValue(
                    Splitter.on(',').trimResults().omitEmptyStrings().splitToList((String) value));
        } else {
            logger.warn("invalid value for plugin property: {}", propertyName);
            return PropertyDescriptor.getDefaultValue(propertyType);
        }
    }

    @Value.Immutable
    interface PluginConfigTemp {
        String id();
        Map<String, PropertyValue> properties();
    }
}
