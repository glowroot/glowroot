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
package io.informant.container.local;

import java.util.List;
import java.util.Map.Entry;

import checkers.nullness.quals.Nullable;
import com.google.common.collect.Lists;

import io.informant.InformantModule;
import io.informant.container.config.AdhocPointcutConfig;
import io.informant.container.config.AdhocPointcutConfig.MethodModifier;
import io.informant.container.config.CoarseProfilingConfig;
import io.informant.container.config.ConfigService;
import io.informant.container.config.FineProfilingConfig;
import io.informant.container.config.GeneralConfig;
import io.informant.container.config.PluginConfig;
import io.informant.container.config.StorageConfig;
import io.informant.container.config.UserOverridesConfig;
import io.informant.local.store.DataSource;
import io.informant.markers.ThreadSafe;

import static io.informant.common.Nullness.assertNonNull;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
class LocalConfigService implements ConfigService {

    private final io.informant.config.ConfigService configService;
    private final DataSource dataSource;

    LocalConfigService(InformantModule informantModule) {
        configService = informantModule.getConfigModule().getConfigService();
        dataSource = informantModule.getStorageModule().getDataSource();
    }

    public void setStoreThresholdMillis(int storeThresholdMillis) throws Exception {
        io.informant.config.GeneralConfig config = configService.getGeneralConfig();
        io.informant.config.GeneralConfig.Overlay overlay =
                io.informant.config.GeneralConfig.overlay(config);
        overlay.setStoreThresholdMillis(storeThresholdMillis);
        configService.updateGeneralConfig(overlay.build(), config.getVersion());
    }

    public GeneralConfig getGeneralConfig() {
        io.informant.config.GeneralConfig coreConfig = configService.getGeneralConfig();
        GeneralConfig config = new GeneralConfig(coreConfig.getVersion());
        config.setEnabled(coreConfig.isEnabled());
        config.setStoreThresholdMillis(coreConfig.getStoreThresholdMillis());
        config.setStuckThresholdSeconds(coreConfig.getStuckThresholdSeconds());
        config.setMaxSpans(coreConfig.getMaxSpans());
        config.setGenerateMetricNameWrapperMethods(coreConfig.isGenerateMetricNameWrapperMethods());
        config.setWarnOnSpanOutsideTrace(coreConfig.isWarnOnSpanOutsideTrace());
        config.setWeavingDisabled(coreConfig.isWeavingDisabled());
        return config;
    }

    public String updateGeneralConfig(GeneralConfig config) throws Exception {
        io.informant.config.GeneralConfig updatedConfig =
                new io.informant.config.GeneralConfig(config.isEnabled(),
                        config.getStoreThresholdMillis(),
                        config.getStuckThresholdSeconds(),
                        config.getMaxSpans(),
                        config.isGenerateMetricNameWrapperMethods(),
                        config.isWarnOnSpanOutsideTrace(),
                        config.isWeavingDisabled());
        return configService.updateGeneralConfig(updatedConfig, config.getVersion());
    }

    public CoarseProfilingConfig getCoarseProfilingConfig() {
        io.informant.config.CoarseProfilingConfig coreConfig =
                configService.getCoarseProfilingConfig();
        CoarseProfilingConfig config = new CoarseProfilingConfig(coreConfig.getVersion());
        config.setEnabled(coreConfig.isEnabled());
        config.setInitialDelayMillis(coreConfig.getInitialDelayMillis());
        config.setIntervalMillis(coreConfig.getIntervalMillis());
        config.setTotalSeconds(coreConfig.getTotalSeconds());
        return config;
    }

    public String updateCoarseProfilingConfig(CoarseProfilingConfig config) throws Exception {
        io.informant.config.CoarseProfilingConfig updatedConfig =
                new io.informant.config.CoarseProfilingConfig(config.isEnabled(),
                        config.getInitialDelayMillis(), config.getIntervalMillis(),
                        config.getTotalSeconds());
        return configService.updateCoarseProfilingConfig(updatedConfig, config.getVersion());
    }

    public FineProfilingConfig getFineProfilingConfig() {
        io.informant.config.FineProfilingConfig coreConfig =
                configService.getFineProfilingConfig();
        FineProfilingConfig config = new FineProfilingConfig(coreConfig.getVersion());
        config.setEnabled(coreConfig.isEnabled());
        config.setTracePercentage(coreConfig.getTracePercentage());
        config.setIntervalMillis(coreConfig.getIntervalMillis());
        config.setTotalSeconds(coreConfig.getTotalSeconds());
        config.setStoreThresholdMillis(coreConfig.getStoreThresholdMillis());
        return config;
    }

    public String updateFineProfilingConfig(FineProfilingConfig config) throws Exception {
        io.informant.config.FineProfilingConfig updatedConfig =
                new io.informant.config.FineProfilingConfig(config.isEnabled(),
                        config.getTracePercentage(), config.getIntervalMillis(),
                        config.getTotalSeconds(), config.getStoreThresholdMillis());
        return configService.updateFineProfilingConfig(updatedConfig, config.getVersion());
    }

    public UserOverridesConfig getUserOverridesConfig() {
        io.informant.config.UserOverridesConfig coreConfig = configService.getUserOverridesConfig();
        UserOverridesConfig config = new UserOverridesConfig(coreConfig.getVersion());
        config.setEnabled(coreConfig.isEnabled());
        config.setUserId(coreConfig.getUserId());
        config.setStoreThresholdMillis(coreConfig.getStoreThresholdMillis());
        config.setFineProfiling(coreConfig.isFineProfiling());
        return config;
    }

    public String updateUserOverridesConfig(UserOverridesConfig config) throws Exception {
        io.informant.config.UserOverridesConfig updatedConfig =
                new io.informant.config.UserOverridesConfig(config.isEnabled(), config.getUserId(),
                        config.getStoreThresholdMillis(), config.isFineProfiling());
        return configService.updateUserOverridesConfig(updatedConfig, config.getVersion());
    }

    public StorageConfig getStorageConfig() {
        io.informant.config.StorageConfig coreConfig = configService.getStorageConfig();
        StorageConfig config = new StorageConfig(coreConfig.getVersion());
        config.setSnapshotExpirationHours(coreConfig.getSnapshotExpirationHours());
        config.setRollingSizeMb(coreConfig.getRollingSizeMb());
        return config;
    }

    public String updateStorageConfig(StorageConfig config) throws Exception {
        io.informant.config.StorageConfig updatedConfig =
                new io.informant.config.StorageConfig(config.getSnapshotExpirationHours(),
                        config.getRollingSizeMb());
        return configService.updateStorageConfig(updatedConfig, config.getVersion());
    }

    @Nullable
    public PluginConfig getPluginConfig(String pluginId) {
        io.informant.config.PluginConfig coreConfig = configService.getPluginConfig(pluginId);
        if (coreConfig == null) {
            return null;
        }
        PluginConfig config = new PluginConfig(coreConfig.getGroupId(),
                coreConfig.getArtifactId(),
                coreConfig.getVersion());
        config.setEnabled(coreConfig.isEnabled());
        for (Entry<String, /*@Nullable*/Object> entry : coreConfig.getProperties().entrySet()) {
            config.setProperty(entry.getKey(), entry.getValue());
        }
        return config;
    }

    public String updatePluginConfig(String pluginId, PluginConfig config) throws Exception {
        io.informant.config.PluginConfig pluginConfig = configService.getPluginConfig(pluginId);
        if (pluginConfig == null) {
            throw new IllegalArgumentException("Plugin for id not found: " + pluginId);
        }
        io.informant.config.PluginConfig.Builder updatedConfig =
                io.informant.config.PluginConfig.builder(pluginConfig);
        updatedConfig.enabled(config.isEnabled());
        for (Entry<String, /*@Nullable*/Object> entry : config.getProperties().entrySet()) {
            updatedConfig.setProperty(entry.getKey(), entry.getValue());
        }
        return configService.updatePluginConfig(updatedConfig.build(), config.getVersion());
    }

    public List<AdhocPointcutConfig> getAdhocPointcutConfigs() {
        List<AdhocPointcutConfig> configs = Lists.newArrayList();
        for (io.informant.config.AdhocPointcutConfig coreConfig : configService
                .getAdhocPointcutConfigs()) {
            configs.add(convertToCore(coreConfig));
        }
        return configs;
    }

    public String addAdhocPointcutConfig(AdhocPointcutConfig config) throws Exception {
        return configService.insertAdhocPointcutConfig(convertToCore(config));
    }

    public String updateAdhocPointcutConfig(String version, AdhocPointcutConfig config)
            throws Exception {
        return configService.updateAdhocPointcutConfig(version, convertToCore(config));
    }

    public void removeAdhocPointcutConfig(String version) throws Exception {
        configService.deleteAdhocPointcutConfig(version);
    }

    public void reweaveAdhocPointcuts() throws Exception {
        throw new IllegalStateException(
                "Retransforming classes only works inside javaagent container");
    }

    public void compactData() throws Exception {
        dataSource.compact();
    }

    public void resetAllConfig() throws Exception {
        configService.resetAllConfig();
    }

    private static AdhocPointcutConfig convertToCore(
            io.informant.config.AdhocPointcutConfig coreConfig) {
        List<MethodModifier> methodModifiers = Lists.newArrayList();
        for (io.informant.api.weaving.MethodModifier methodModifier : coreConfig
                .getMethodModifiers()) {
            methodModifiers.add(MethodModifier.valueOf(methodModifier.name()));
        }

        AdhocPointcutConfig config = new AdhocPointcutConfig(coreConfig.getVersion());
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

    private static io.informant.config.AdhocPointcutConfig convertToCore(
            AdhocPointcutConfig config) {
        List<io.informant.api.weaving.MethodModifier> methodModifiers = Lists.newArrayList();
        for (MethodModifier methodModifier : config.getMethodModifiers()) {
            methodModifiers.add(io.informant.api.weaving.MethodModifier.valueOf(methodModifier
                    .name()));
        }
        String typeName = config.getTypeName();
        String methodName = config.getMethodName();
        String methodReturnTypeName = config.getMethodReturnTypeName();
        assertNonNull(typeName, "Config typeName is null");
        assertNonNull(methodName, "Config methodName is null");
        assertNonNull(methodReturnTypeName, "Config methodReturnTypeName is null");
        return new io.informant.config.AdhocPointcutConfig(config.isMetric(), config.isSpan(),
                config.isTrace(), typeName, methodName, config.getMethodArgTypeNames(),
                methodReturnTypeName, methodModifiers, config.getMetricName(),
                config.getSpanText(), config.getTraceGrouping());
    }
}
