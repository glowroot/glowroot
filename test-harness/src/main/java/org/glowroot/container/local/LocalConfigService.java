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
import org.glowroot.container.config.CoarseProfilingConfig;
import org.glowroot.container.config.ConfigService;
import org.glowroot.container.config.FineProfilingConfig;
import org.glowroot.container.config.GeneralConfig;
import org.glowroot.container.config.PluginConfig;
import org.glowroot.container.config.PointcutConfig;
import org.glowroot.container.config.PointcutConfig.MethodModifier;
import org.glowroot.container.config.StorageConfig;
import org.glowroot.container.config.UserInterfaceConfig;
import org.glowroot.container.config.UserOverridesConfig;
import org.glowroot.local.store.DataSource;
import org.glowroot.local.ui.LocalUiModule;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.nullToEmpty;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
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
    public GeneralConfig getGeneralConfig() {
        org.glowroot.config.GeneralConfig coreConfig = configService.getGeneralConfig();
        GeneralConfig config = new GeneralConfig(coreConfig.getVersion());
        config.setEnabled(coreConfig.isEnabled());
        config.setStoreThresholdMillis(coreConfig.getStoreThresholdMillis());
        config.setStuckThresholdSeconds(coreConfig.getStuckThresholdSeconds());
        config.setMaxSpans(coreConfig.getMaxSpans());
        return config;
    }

    @Override
    public void updateGeneralConfig(GeneralConfig config) throws Exception {
        org.glowroot.config.GeneralConfig updatedConfig =
                new org.glowroot.config.GeneralConfig(config.isEnabled(),
                        config.getStoreThresholdMillis(),
                        config.getStuckThresholdSeconds(),
                        config.getMaxSpans());
        configService.updateGeneralConfig(updatedConfig, config.getVersion());
    }

    @Override
    public CoarseProfilingConfig getCoarseProfilingConfig() {
        org.glowroot.config.CoarseProfilingConfig coreConfig =
                configService.getCoarseProfilingConfig();
        CoarseProfilingConfig config = new CoarseProfilingConfig(coreConfig.getVersion());
        config.setEnabled(coreConfig.isEnabled());
        config.setInitialDelayMillis(coreConfig.getInitialDelayMillis());
        config.setIntervalMillis(coreConfig.getIntervalMillis());
        config.setTotalSeconds(coreConfig.getTotalSeconds());
        return config;
    }

    @Override
    public void updateCoarseProfilingConfig(CoarseProfilingConfig config) throws Exception {
        org.glowroot.config.CoarseProfilingConfig updatedConfig =
                new org.glowroot.config.CoarseProfilingConfig(config.isEnabled(),
                        config.getInitialDelayMillis(), config.getIntervalMillis(),
                        config.getTotalSeconds());
        configService.updateCoarseProfilingConfig(updatedConfig, config.getVersion());
    }

    @Override
    public FineProfilingConfig getFineProfilingConfig() {
        org.glowroot.config.FineProfilingConfig coreConfig =
                configService.getFineProfilingConfig();
        FineProfilingConfig config = new FineProfilingConfig(coreConfig.getVersion());
        config.setTracePercentage(coreConfig.getTracePercentage());
        config.setIntervalMillis(coreConfig.getIntervalMillis());
        config.setTotalSeconds(coreConfig.getTotalSeconds());
        config.setStoreThresholdMillis(coreConfig.getStoreThresholdMillis());
        return config;
    }

    @Override
    public void updateFineProfilingConfig(FineProfilingConfig config) throws Exception {
        org.glowroot.config.FineProfilingConfig updatedConfig =
                new org.glowroot.config.FineProfilingConfig(config.getTracePercentage(),
                        config.getIntervalMillis(), config.getTotalSeconds(),
                        config.getStoreThresholdMillis());
        configService.updateFineProfilingConfig(updatedConfig, config.getVersion());
    }

    @Override
    public UserOverridesConfig getUserOverridesConfig() {
        org.glowroot.config.UserOverridesConfig coreConfig = configService.getUserOverridesConfig();
        UserOverridesConfig config = new UserOverridesConfig(coreConfig.getVersion());
        config.setUser(coreConfig.getUser());
        config.setStoreThresholdMillis(coreConfig.getStoreThresholdMillis());
        config.setFineProfiling(coreConfig.isFineProfiling());
        return config;
    }

    @Override
    public void updateUserOverridesConfig(UserOverridesConfig config) throws Exception {
        org.glowroot.config.UserOverridesConfig updatedConfig =
                new org.glowroot.config.UserOverridesConfig(config.getUser(),
                        config.getStoreThresholdMillis(), config.isFineProfiling());
        configService.updateUserOverridesConfig(updatedConfig, config.getVersion());
    }

    @Override
    public StorageConfig getStorageConfig() {
        org.glowroot.config.StorageConfig coreConfig = configService.getStorageConfig();
        StorageConfig config = new StorageConfig(coreConfig.getVersion());
        config.setTraceExpirationHours(coreConfig.getTraceExpirationHours());
        config.setCappedDatabaseSizeMb(coreConfig.getCappedDatabaseSizeMb());
        return config;
    }

    @Override
    public void updateStorageConfig(StorageConfig config) throws Exception {
        org.glowroot.config.StorageConfig updatedConfig =
                new org.glowroot.config.StorageConfig(config.getTraceExpirationHours(),
                        config.getCappedDatabaseSizeMb());
        configService.updateStorageConfig(updatedConfig, config.getVersion());
    }

    @Override
    public UserInterfaceConfig getUserInterfaceConfig() {
        org.glowroot.config.UserInterfaceConfig coreConfig = configService.getUserInterfaceConfig();
        UserInterfaceConfig config =
                new UserInterfaceConfig(coreConfig.isPasswordEnabled(), coreConfig.getVersion());
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
    public List<PointcutConfig> getPointcutConfigs() {
        List<PointcutConfig> configs = Lists.newArrayList();
        for (org.glowroot.config.PointcutConfig coreConfig : configService
                .getPointcutConfigs()) {
            configs.add(convertFromCore(coreConfig));
        }
        return configs;
    }

    @Override
    public String addPointcutConfig(PointcutConfig config) throws Exception {
        return configService.insertPointcutConfig(convertToCore(config));
    }

    @Override
    public void updatePointcutConfig(String version, PointcutConfig config) throws Exception {
        configService.updatePointcutConfig(version, convertToCore(config));
    }

    @Override
    public void removePointcutConfig(String version) throws Exception {
        configService.deletePointcutConfig(version);
    }

    @Override
    public AdvancedConfig getAdvancedConfig() {
        org.glowroot.config.AdvancedConfig coreConfig = configService.getAdvancedConfig();
        AdvancedConfig config = new AdvancedConfig(coreConfig.getVersion());
        config.setTraceMetricWrapperMethodsDisabled(
                coreConfig.isTraceMetricWrapperMethodsDisabled());
        config.setWarnOnSpanOutsideTrace(coreConfig.isWarnOnSpanOutsideTrace());
        config.setWeavingDisabled(coreConfig.isWeavingDisabled());
        return config;
    }

    @Override
    public void updateAdvancedConfig(AdvancedConfig config) throws Exception {
        org.glowroot.config.AdvancedConfig updatedConfig =
                new org.glowroot.config.AdvancedConfig(
                        config.isTraceMetricWrapperMethodsDisabled(),
                        config.isWarnOnSpanOutsideTrace(), config.isWeavingDisabled());
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

    void setStoreThresholdMillis(int storeThresholdMillis) throws Exception {
        org.glowroot.config.GeneralConfig config = configService.getGeneralConfig();
        org.glowroot.config.GeneralConfig.Overlay overlay =
                org.glowroot.config.GeneralConfig.overlay(config);
        overlay.setStoreThresholdMillis(storeThresholdMillis);
        configService.updateGeneralConfig(overlay.build(), config.getVersion());
    }

    private static PointcutConfig convertFromCore(org.glowroot.config.PointcutConfig coreConfig) {
        List<MethodModifier> methodModifiers = Lists.newArrayList();
        for (org.glowroot.api.weaving.MethodModifier methodModifier : coreConfig
                .getMethodModifiers()) {
            methodModifiers.add(MethodModifier.valueOf(methodModifier.name()));
        }
        PointcutConfig config = new PointcutConfig(coreConfig.getVersion());
        config.setType(coreConfig.getType());
        config.setMethodName(coreConfig.getMethodName());
        config.setMethodArgTypes(coreConfig.getMethodArgTypes());
        config.setMethodReturnType(coreConfig.getMethodReturnType());
        config.setMethodModifiers(methodModifiers);
        config.setTraceMetric(coreConfig.getTraceMetric());
        config.setSpanText(coreConfig.getSpanText());
        config.setSpanStackTraceThresholdMillis(coreConfig.getSpanStackTraceThresholdMillis());
        config.setSpanIgnoreSameNested(coreConfig.isSpanIgnoreSameNested());
        config.setTransactionName(coreConfig.getTransactionName());
        config.setBackground(coreConfig.isBackground());
        config.setEnabledProperty(coreConfig.getEnabledProperty());
        config.setSpanEnabledProperty(coreConfig.getSpanEnabledProperty());
        return config;
    }

    private static org.glowroot.config.PointcutConfig convertToCore(PointcutConfig config) {
        List<org.glowroot.api.weaving.MethodModifier> methodModifiers = Lists.newArrayList();
        for (MethodModifier methodModifier : config.getMethodModifiers()) {
            methodModifiers.add(
                    org.glowroot.api.weaving.MethodModifier.valueOf(methodModifier.name()));
        }
        String typeName = config.getType();
        String methodName = config.getMethodName();
        String methodReturnTypeName = config.getMethodReturnType();
        checkNotNull(typeName, "PointcutConfig typeName is null");
        checkNotNull(methodName, "PointcutConfig methodName is null");
        checkNotNull(methodReturnTypeName, "PointcutConfig methodReturnTypeName is null");
        return new org.glowroot.config.PointcutConfig(typeName, methodName,
                config.getMethodArgTypes(), methodReturnTypeName, methodModifiers,
                nullToEmpty(config.getTraceMetric()), nullToEmpty(config.getSpanText()),
                config.getSpanStackTraceThresholdMillis(), config.isSpanIgnoreSameNested(),
                nullToEmpty(config.getTransactionName()), config.isBackground(),
                nullToEmpty(config.getEnabledProperty()),
                nullToEmpty(config.getSpanEnabledProperty()));
    }
}
