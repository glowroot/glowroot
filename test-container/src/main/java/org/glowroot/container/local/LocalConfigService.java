/*
 * Copyright 2011-2013 the original author or authors.
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

import checkers.nullness.quals.Nullable;
import com.google.common.collect.Lists;

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
import org.glowroot.markers.ThreadSafe;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
class LocalConfigService implements ConfigService {

    private final org.glowroot.config.ConfigService configService;
    private final DataSource dataSource;
    private final LocalUiModule localUiModule;

    LocalConfigService(GlowrootModule glowrootModule) {
        configService = glowrootModule.getConfigModule().getConfigService();
        dataSource = glowrootModule.getStorageModule().getDataSource();
        localUiModule = glowrootModule.getUiModule();
    }

    public void setStoreThresholdMillis(int storeThresholdMillis) throws Exception {
        org.glowroot.config.GeneralConfig config = configService.getGeneralConfig();
        org.glowroot.config.GeneralConfig.Overlay overlay =
                org.glowroot.config.GeneralConfig.overlay(config);
        overlay.setStoreThresholdMillis(storeThresholdMillis);
        configService.updateGeneralConfig(overlay.build(), config.getVersion());
    }

    public GeneralConfig getGeneralConfig() {
        org.glowroot.config.GeneralConfig coreConfig = configService.getGeneralConfig();
        GeneralConfig config = new GeneralConfig(coreConfig.getVersion());
        config.setEnabled(coreConfig.isEnabled());
        config.setStoreThresholdMillis(coreConfig.getStoreThresholdMillis());
        config.setStuckThresholdSeconds(coreConfig.getStuckThresholdSeconds());
        config.setMaxSpans(coreConfig.getMaxSpans());
        return config;
    }

    public void updateGeneralConfig(GeneralConfig config) throws Exception {
        org.glowroot.config.GeneralConfig updatedConfig =
                new org.glowroot.config.GeneralConfig(config.isEnabled(),
                        config.getStoreThresholdMillis(),
                        config.getStuckThresholdSeconds(),
                        config.getMaxSpans());
        configService.updateGeneralConfig(updatedConfig, config.getVersion());
    }

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

    public void updateCoarseProfilingConfig(CoarseProfilingConfig config) throws Exception {
        org.glowroot.config.CoarseProfilingConfig updatedConfig =
                new org.glowroot.config.CoarseProfilingConfig(config.isEnabled(),
                        config.getInitialDelayMillis(), config.getIntervalMillis(),
                        config.getTotalSeconds());
        configService.updateCoarseProfilingConfig(updatedConfig, config.getVersion());
    }

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

    public void updateFineProfilingConfig(FineProfilingConfig config) throws Exception {
        org.glowroot.config.FineProfilingConfig updatedConfig =
                new org.glowroot.config.FineProfilingConfig(config.getTracePercentage(),
                        config.getIntervalMillis(), config.getTotalSeconds(),
                        config.getStoreThresholdMillis());
        configService.updateFineProfilingConfig(updatedConfig, config.getVersion());
    }

    public UserOverridesConfig getUserOverridesConfig() {
        org.glowroot.config.UserOverridesConfig coreConfig = configService.getUserOverridesConfig();
        UserOverridesConfig config = new UserOverridesConfig(coreConfig.getVersion());
        config.setUserId(coreConfig.getUserId());
        config.setStoreThresholdMillis(coreConfig.getStoreThresholdMillis());
        config.setFineProfiling(coreConfig.isFineProfiling());
        return config;
    }

    public void updateUserOverridesConfig(UserOverridesConfig config) throws Exception {
        org.glowroot.config.UserOverridesConfig updatedConfig =
                new org.glowroot.config.UserOverridesConfig(config.getUserId(),
                        config.getStoreThresholdMillis(), config.isFineProfiling());
        configService.updateUserOverridesConfig(updatedConfig, config.getVersion());
    }

    public StorageConfig getStorageConfig() {
        org.glowroot.config.StorageConfig coreConfig = configService.getStorageConfig();
        StorageConfig config = new StorageConfig(coreConfig.getVersion());
        config.setSnapshotExpirationHours(coreConfig.getSnapshotExpirationHours());
        config.setRollingSizeMb(coreConfig.getRollingSizeMb());
        return config;
    }

    public void updateStorageConfig(StorageConfig config) throws Exception {
        org.glowroot.config.StorageConfig updatedConfig =
                new org.glowroot.config.StorageConfig(config.getSnapshotExpirationHours(),
                        config.getRollingSizeMb());
        configService.updateStorageConfig(updatedConfig, config.getVersion());
    }

    public UserInterfaceConfig getUserInterfaceConfig() {
        org.glowroot.config.UserInterfaceConfig coreConfig = configService.getUserInterfaceConfig();
        UserInterfaceConfig config =
                new UserInterfaceConfig(coreConfig.isPasswordEnabled(), coreConfig.getVersion());
        config.setPort(coreConfig.getPort());
        config.setSessionTimeoutMinutes(coreConfig.getSessionTimeoutMinutes());
        return config;
    }

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

    public AdvancedConfig getAdvancedConfig() {
        org.glowroot.config.AdvancedConfig coreConfig = configService.getAdvancedConfig();
        AdvancedConfig config = new AdvancedConfig(coreConfig.getVersion());
        config.setGenerateMetricNameWrapperMethods(coreConfig.isGenerateMetricNameWrapperMethods());
        config.setWarnOnSpanOutsideTrace(coreConfig.isWarnOnSpanOutsideTrace());
        config.setWeavingDisabled(coreConfig.isWeavingDisabled());
        return config;
    }

    public void updateAdvancedConfig(AdvancedConfig config) throws Exception {
        org.glowroot.config.AdvancedConfig updatedConfig =
                new org.glowroot.config.AdvancedConfig(config.isGenerateMetricNameWrapperMethods(),
                        config.isWarnOnSpanOutsideTrace(),
                        config.isWeavingDisabled());
        configService.updateAdvancedConfig(updatedConfig, config.getVersion());
    }

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

    public List<PointcutConfig> getPointcutConfigs() {
        List<PointcutConfig> configs = Lists.newArrayList();
        for (org.glowroot.config.PointcutConfig coreConfig : configService
                .getPointcutConfigs()) {
            configs.add(convertToCore(coreConfig));
        }
        return configs;
    }

    public String addPointcutConfig(PointcutConfig config) throws Exception {
        return configService.insertPointcutConfig(convertToCore(config));
    }

    public void updatePointcutConfig(String version, PointcutConfig config)
            throws Exception {
        configService.updatePointcutConfig(version, convertToCore(config));
    }

    public void removePointcutConfig(String version) throws Exception {
        configService.deletePointcutConfig(version);
    }

    public void reweavePointcutConfigs() throws Exception {
        throw new UnsupportedOperationException("Retransforming classes only works inside"
                + " javaagent container");
    }

    public void compactData() throws Exception {
        dataSource.compact();
    }

    public void resetAllConfig() throws Exception {
        configService.resetAllConfig();
    }

    private static PointcutConfig convertToCore(
            org.glowroot.config.PointcutConfig coreConfig) {
        List<MethodModifier> methodModifiers = Lists.newArrayList();
        for (org.glowroot.api.weaving.MethodModifier methodModifier : coreConfig
                .getMethodModifiers()) {
            methodModifiers.add(MethodModifier.valueOf(methodModifier.name()));
        }

        PointcutConfig config = new PointcutConfig(coreConfig.getVersion());
        config.setMetric(coreConfig.isMetric());
        config.setSpan(coreConfig.isSpan());
        config.setTrace(coreConfig.isTrace());
        config.setTypeName(coreConfig.getTypeName());
        config.setMethodName(coreConfig.getMethodName());
        config.setMethodArgTypeNames(coreConfig.getMethodArgTypeNames());
        config.setMethodReturnTypeName(coreConfig.getMethodReturnTypeName());
        config.setMethodModifiers(methodModifiers);
        config.setMetricName(coreConfig.getMetricName());
        config.setSpanText(coreConfig.getSpanText());
        return config;
    }

    private static org.glowroot.config.PointcutConfig convertToCore(
            PointcutConfig config) {
        List<org.glowroot.api.weaving.MethodModifier> methodModifiers = Lists.newArrayList();
        for (MethodModifier methodModifier : config.getMethodModifiers()) {
            methodModifiers.add(org.glowroot.api.weaving.MethodModifier.valueOf(methodModifier
                    .name()));
        }
        String typeName = config.getTypeName();
        String methodName = config.getMethodName();
        String methodReturnTypeName = config.getMethodReturnTypeName();
        checkNotNull(typeName, "PointcutConfig typeName is null");
        checkNotNull(methodName, "PointcutConfig methodName is null");
        checkNotNull(methodReturnTypeName, "PointcutConfig methodReturnTypeName is null");
        return new org.glowroot.config.PointcutConfig(config.isMetric(), config.isSpan(),
                config.isTrace(), typeName, methodName, config.getMethodArgTypeNames(),
                methodReturnTypeName, methodModifiers, config.getMetricName(),
                config.getSpanText(), config.getTraceGrouping());
    }
}
