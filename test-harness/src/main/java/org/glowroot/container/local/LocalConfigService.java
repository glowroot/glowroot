/*
 * Copyright 2011-2014 the original author or authors.
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
package org.glowroot.container.local;

import java.util.List;
import java.util.Map.Entry;

import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.GlowrootModule;
import org.glowroot.config.UserInterfaceConfig.Overlay;
import org.glowroot.container.config.AdvancedConfig;
import org.glowroot.container.config.CapturePoint;
import org.glowroot.container.config.CapturePoint.CaptureKind;
import org.glowroot.container.config.CapturePoint.MethodModifier;
import org.glowroot.container.config.ConfigService;
import org.glowroot.container.config.MBeanGauge;
import org.glowroot.container.config.PluginConfig;
import org.glowroot.container.config.ProfilingConfig;
import org.glowroot.container.config.StorageConfig;
import org.glowroot.container.config.TraceConfig;
import org.glowroot.container.config.UserInterfaceConfig;
import org.glowroot.container.config.UserRecordingConfig;
import org.glowroot.local.store.DataSource;
import org.glowroot.local.ui.LocalUiModule;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.nullToEmpty;
import static org.glowroot.container.common.ObjectMappers.nullToEmpty;

class LocalConfigService implements ConfigService {

    private final org.glowroot.config.ConfigService configService;
    private final DataSource dataSource;
    private final LocalUiModule localUiModule;

    LocalConfigService(GlowrootModule glowrootModule) {
        configService = glowrootModule.getConfigModule().getConfigService();
        dataSource = glowrootModule.getStorageModule().getDataSource();
        localUiModule = glowrootModule.getUiModule();
    }

    @Override
    public void setPluginProperty(String pluginId, String propertyName,
            @Nullable Object propertyValue) throws Exception {
        PluginConfig config = getPluginConfig(pluginId);
        if (config == null) {
            throw new IllegalStateException("Plugin not found for pluginId: " + pluginId);
        }
        config.setProperty(propertyName, propertyValue);
        updatePluginConfig(pluginId, config);
    }

    @Override
    public TraceConfig getTraceConfig() {
        org.glowroot.config.TraceConfig coreConfig = configService.getTraceConfig();
        TraceConfig config = new TraceConfig(coreConfig.getVersion());
        config.setEnabled(coreConfig.isEnabled());
        config.setStoreThresholdMillis(coreConfig.getStoreThresholdMillis());
        config.setOutlierProfilingEnabled(coreConfig.isOutlierProfilingEnabled());
        config.setOutlierProfilingInitialDelayMillis(
                coreConfig.getOutlierProfilingInitialDelayMillis());
        config.setOutlierProfilingIntervalMillis(coreConfig.getOutlierProfilingIntervalMillis());
        return config;
    }

    @Override
    public void updateTraceConfig(TraceConfig config) throws Exception {
        org.glowroot.config.TraceConfig updatedConfig =
                new org.glowroot.config.TraceConfig(config.isEnabled(),
                        config.getStoreThresholdMillis(), config.isOutlierProfilingEnabled(),
                        config.getOutlierProfilingInitialDelayMillis(),
                        config.getOutlierProfilingIntervalMillis());
        configService.updateTraceConfig(updatedConfig, config.getVersion());
    }

    @Override
    public ProfilingConfig getProfilingConfig() {
        org.glowroot.config.ProfilingConfig coreConfig =
                configService.getProfilingConfig();
        ProfilingConfig config = new ProfilingConfig(coreConfig.getVersion());
        config.setEnabled(coreConfig.isEnabled());
        config.setIntervalMillis(coreConfig.getIntervalMillis());
        return config;
    }

    @Override
    public void updateProfilingConfig(ProfilingConfig config) throws Exception {
        org.glowroot.config.ProfilingConfig updatedConfig =
                new org.glowroot.config.ProfilingConfig(config.isEnabled(),
                        config.getIntervalMillis());
        configService.updateProfilingConfig(updatedConfig, config.getVersion());
    }

    @Override
    public UserRecordingConfig getUserRecordingConfig() {
        org.glowroot.config.UserRecordingConfig coreConfig = configService.getUserRecordingConfig();
        UserRecordingConfig config = new UserRecordingConfig(coreConfig.getVersion());
        config.setEnabled(coreConfig.isEnabled());
        config.setUser(coreConfig.getUser());
        config.setProfileIntervalMillis(coreConfig.getProfileIntervalMillis());
        return config;
    }

    @Override
    public void updateUserRecordingConfig(UserRecordingConfig config) throws Exception {
        org.glowroot.config.UserRecordingConfig updatedConfig =
                new org.glowroot.config.UserRecordingConfig(config.isEnabled(), config.getUser(),
                        config.getProfileIntervalMillis());
        configService.updateUserRecordingConfig(updatedConfig, config.getVersion());
    }

    @Override
    public StorageConfig getStorageConfig() {
        org.glowroot.config.StorageConfig coreConfig = configService.getStorageConfig();
        StorageConfig config = new StorageConfig(coreConfig.getVersion());
        config.setAggregateExpirationHours(coreConfig.getAggregateExpirationHours());
        config.setTraceExpirationHours(coreConfig.getTraceExpirationHours());
        config.setCappedDatabaseSizeMb(coreConfig.getCappedDatabaseSizeMb());
        return config;
    }

    @Override
    public void updateStorageConfig(StorageConfig config) throws Exception {
        org.glowroot.config.StorageConfig updatedConfig =
                new org.glowroot.config.StorageConfig(config.getAggregateExpirationHours(),
                        config.getTraceExpirationHours(), config.getCappedDatabaseSizeMb());
        configService.updateStorageConfig(updatedConfig, config.getVersion());
    }

    @Override
    public UserInterfaceConfig getUserInterfaceConfig() {
        org.glowroot.config.UserInterfaceConfig coreConfig = configService.getUserInterfaceConfig();
        UserInterfaceConfig config =
                new UserInterfaceConfig(coreConfig.isPasswordEnabled(), coreConfig.getVersion());
        config.setDefaultTransactionType(coreConfig.getDefaultTransactionType());
        config.setPort(coreConfig.getPort());
        config.setSessionTimeoutMinutes(coreConfig.getSessionTimeoutMinutes());
        return config;
    }

    @Override
    public void updateUserInterfaceConfig(UserInterfaceConfig config) throws Exception {
        // need to use overlay in order to preserve existing passwordHash
        org.glowroot.config.UserInterfaceConfig coreConfig = configService.getUserInterfaceConfig();
        Overlay overlay = org.glowroot.config.UserInterfaceConfig.overlay(coreConfig);
        overlay.setPort(config.getPort());
        overlay.setDefaultTransactionType(config.getDefaultTransactionType());
        overlay.setSessionTimeoutMinutes(config.getSessionTimeoutMinutes());
        overlay.setCurrentPassword(config.getCurrentPassword());
        overlay.setNewPassword(config.getNewPassword());
        org.glowroot.config.UserInterfaceConfig updatedCoreConfig;
        try {
            updatedCoreConfig = overlay.build();
        } catch (org.glowroot.config.UserInterfaceConfig.CurrentPasswordIncorrectException e) {
            throw new CurrentPasswordIncorrectException();
        }
        // lastly deal with ui port change
        if (coreConfig.getPort() != updatedCoreConfig.getPort()) {
            try {
                localUiModule.changeHttpServerPort(updatedCoreConfig.getPort());
            } catch (org.glowroot.local.ui.HttpServer.PortChangeFailedException e) {
                throw new PortChangeFailedException();
            }
        }
        configService.updateUserInterfaceConfig(updatedCoreConfig, config.getVersion());
    }

    @Override
    public List<MBeanGauge> getMBeanGauges() {
        List<MBeanGauge> configs = Lists.newArrayList();
        for (org.glowroot.config.MBeanGauge coreConfig : configService
                .getMBeanGaugesNeverShaded()) {
            configs.add(convertFromCore(coreConfig));
        }
        return configs;
    }

    @Override
    public String addMBeanGauge(MBeanGauge config) throws Exception {
        return configService.insertMBeanGauge(convertToCore(config));
    }

    @Override
    public void updateMBeanGauge(String version, MBeanGauge config) throws Exception {
        configService.updateMBeanGauge(version, convertToCore(config));
    }

    @Override
    public void removeMBeanGauge(String version) throws Exception {
        configService.deleteMBeanGauge(version);
    }

    @Override
    public AdvancedConfig getAdvancedConfig() {
        org.glowroot.config.AdvancedConfig coreConfig = configService.getAdvancedConfig();
        AdvancedConfig config = new AdvancedConfig(coreConfig.getVersion());
        config.setMetricWrapperMethods(coreConfig.isMetricWrapperMethods());
        config.setImmediatePartialStoreThresholdSeconds(
                coreConfig.getImmediatePartialStoreThresholdSeconds());
        config.setMaxTraceEntriesPerTransaction(coreConfig.getMaxTraceEntriesPerTransaction());
        config.setMaxStackTraceSamplesPerTransaction(
                coreConfig.getMaxStackTraceSamplesPerTransaction());
        config.setCaptureThreadInfo(coreConfig.isCaptureThreadInfo());
        config.setCaptureGcInfo(coreConfig.isCaptureGcInfo());
        config.setMBeanGaugeNotFoundDelaySeconds(coreConfig.getMBeanGaugeNotFoundDelaySeconds());
        config.setInternalQueryTimeoutSeconds(coreConfig.getInternalQueryTimeoutSeconds());
        return config;
    }

    @Override
    public void updateAdvancedConfig(AdvancedConfig config) throws Exception {
        org.glowroot.config.AdvancedConfig updatedConfig =
                new org.glowroot.config.AdvancedConfig(config.isMetricWrapperMethods(),
                        config.getImmediatePartialStoreThresholdSeconds(),
                        config.getMaxTraceEntriesPerTransaction(),
                        config.getMaxStackTraceSamplesPerTransaction(),
                        config.isCaptureThreadInfo(), config.isCaptureGcInfo(),
                        config.getMBeanGaugeNotFoundDelaySeconds(),
                        config.getInternalQueryTimeoutSeconds());
        configService.updateAdvancedConfig(updatedConfig, config.getVersion());
    }

    @Override
    @Nullable
    public PluginConfig getPluginConfig(String pluginId) {
        org.glowroot.config.PluginConfig coreConfig = configService.getPluginConfig(pluginId);
        if (coreConfig == null) {
            return null;
        }
        PluginConfig config = new PluginConfig(coreConfig.getId(), coreConfig.getVersion());
        config.setEnabled(coreConfig.isEnabled());
        for (Entry<String, /*@Nullable*/Object> entry : coreConfig.getProperties().entrySet()) {
            config.setProperty(entry.getKey(), entry.getValue());
        }
        return config;
    }

    @Override
    public void updatePluginConfig(String pluginId, PluginConfig config) throws Exception {
        org.glowroot.config.PluginConfig pluginConfig = configService.getPluginConfig(pluginId);
        if (pluginConfig == null) {
            throw new IllegalArgumentException("Plugin for id not found: " + pluginId);
        }
        org.glowroot.config.PluginConfig.Builder updatedConfig =
                org.glowroot.config.PluginConfig.builder(pluginConfig);
        updatedConfig.enabled(config.isEnabled());
        for (Entry<String, /*@Nullable*/Object> entry : config.getProperties().entrySet()) {
            updatedConfig.setProperty(entry.getKey(), entry.getValue());
        }
        configService.updatePluginConfig(updatedConfig.build(), config.getVersion());
    }

    @Override
    public List<CapturePoint> getCapturePoints() {
        List<CapturePoint> configs = Lists.newArrayList();
        for (org.glowroot.config.CapturePoint coreConfig : configService
                .getCapturePointsNeverShaded()) {
            configs.add(convertFromCore(coreConfig));
        }
        return configs;
    }

    @Override
    public String addCapturePoint(CapturePoint config) throws Exception {
        return configService.insertCapturePoint(convertToCore(config));
    }

    @Override
    public void updateCapturePoint(String version, CapturePoint config) throws Exception {
        configService.updateCapturePoint(version, convertToCore(config));
    }

    @Override
    public void removeCapturePoint(String version) throws Exception {
        configService.deleteCapturePoint(version);
    }

    @Override
    public int reweavePointcuts() throws Exception {
        throw new UnsupportedOperationException("Retransforming classes only works inside"
                + " javaagent container");
    }

    @Override
    public void compactData() throws Exception {
        dataSource.compact();
    }

    void resetAllConfig() throws Exception {
        configService.resetAllConfig();
    }

    void setTraceStoreThresholdMillis(int traceStoreThresholdMillis) throws Exception {
        org.glowroot.config.TraceConfig config = configService.getTraceConfig();
        org.glowroot.config.TraceConfig.Overlay overlay =
                org.glowroot.config.TraceConfig.overlay(config);
        overlay.setStoreThresholdMillis(traceStoreThresholdMillis);
        configService.updateTraceConfig(overlay.build(), config.getVersion());
    }

    private static CapturePoint convertFromCore(org.glowroot.config.CapturePoint coreConfig) {
        List<MethodModifier> methodModifiers = Lists.newArrayList();
        for (org.glowroot.api.weaving.MethodModifier methodModifier : coreConfig
                .getMethodModifiers()) {
            methodModifiers.add(MethodModifier.valueOf(methodModifier.name()));
        }
        CapturePoint config = new CapturePoint(coreConfig.getVersion());
        config.setClassName(coreConfig.getClassName());
        config.setMethodName(coreConfig.getMethodName());
        config.setMethodParameterTypes(coreConfig.getMethodParameterTypes());
        config.setMethodReturnType(coreConfig.getMethodReturnType());
        config.setMethodModifiers(methodModifiers);
        config.setCaptureKind(CaptureKind.valueOf(coreConfig.getCaptureKind().name()));
        config.setMetricName(coreConfig.getMetricName());
        config.setTraceEntryTemplate(coreConfig.getTraceEntryTemplate());
        config.setTraceEntryStackThresholdMillis(coreConfig.getTraceEntryStackThresholdMillis());
        config.setTraceEntryCaptureSelfNested(coreConfig.isTraceEntryCaptureSelfNested());
        config.setTransactionType(coreConfig.getTransactionType());
        config.setTransactionNameTemplate(coreConfig.getTransactionNameTemplate());
        config.setTraceStoreThresholdMillis(coreConfig.getTraceStoreThresholdMillis());
        config.setTransactionUserTemplate(coreConfig.getTransactionUserTemplate());
        config.setTransactionCustomAttributeTemplates(
                coreConfig.getTransactionCustomAttributeTemplates());
        config.setEnabledProperty(coreConfig.getEnabledProperty());
        config.setTraceEntryEnabledProperty(coreConfig.getTraceEntryEnabledProperty());
        return config;
    }

    private static org.glowroot.config.CapturePoint convertToCore(CapturePoint config) {
        List<org.glowroot.api.weaving.MethodModifier> methodModifiers = Lists.newArrayList();
        for (MethodModifier methodModifier : config.getMethodModifiers()) {
            methodModifiers.add(
                    org.glowroot.api.weaving.MethodModifier.valueOf(methodModifier.name()));
        }
        String className = config.getClassName();
        String methodName = config.getMethodName();
        String methodReturnTypeName = config.getMethodReturnType();
        CaptureKind captureKind = config.getCaptureKind();
        checkNotNull(className, "CapturePoint className is null");
        checkNotNull(methodName, "CapturePoint methodName is null");
        checkNotNull(methodReturnTypeName, "CapturePoint methodReturnTypeName is null");
        checkNotNull(captureKind, "CapturePoint captureKind is null");
        return new org.glowroot.config.CapturePoint(className, methodName,
                config.getMethodParameterTypes(), methodReturnTypeName, methodModifiers,
                org.glowroot.config.CapturePoint.CaptureKind.valueOf(captureKind.name()),
                nullToEmpty(config.getMetricName()),
                nullToEmpty(config.getTraceEntryTemplate()),
                config.getTraceEntryStackThresholdMillis(), config.isTraceEntryCaptureSelfNested(),
                nullToEmpty(config.getTransactionType()),
                nullToEmpty(config.getTransactionNameTemplate()),
                config.getTraceStoreThresholdMillis(),
                nullToEmpty(config.getTransactionUserTemplate()),
                nullToEmpty(config.getTransactionCustomAttributeTemplates()),
                nullToEmpty(config.getEnabledProperty()),
                nullToEmpty(config.getTraceEntryEnabledProperty()));
    }

    private static MBeanGauge convertFromCore(org.glowroot.config.MBeanGauge coreConfig) {
        MBeanGauge config = new MBeanGauge(coreConfig.getVersion());
        config.setName(coreConfig.getName());
        config.setMBeanObjectName(coreConfig.getMBeanObjectName());
        config.setMBeanAttributeNames(coreConfig.getMBeanAttributeNamesNeverShaded());
        return config;
    }

    private static org.glowroot.config.MBeanGauge convertToCore(MBeanGauge config) {
        String metricName = config.getName();
        String mbeanObjectName = config.getMBeanObjectName();
        List<String> mbeanAttributeName = config.getMBeanAttributeNames();
        checkNotNull(metricName, "MBeanGauge metricName is null");
        checkNotNull(mbeanObjectName, "MBeanGauge mbeanObjectName is null");
        checkNotNull(mbeanAttributeName, "MBeanGauge mbeanAttributeName is null");
        return new org.glowroot.config.MBeanGauge(metricName, mbeanObjectName, mbeanAttributeName);
    }
}
