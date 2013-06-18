/**
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

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map.Entry;

import checkers.nullness.quals.Nullable;
import com.google.common.collect.Lists;

import io.informant.InformantModule;
import io.informant.container.config.CoarseProfilingConfig;
import io.informant.container.config.ConfigService;
import io.informant.container.config.FineProfilingConfig;
import io.informant.container.config.GeneralConfig;
import io.informant.container.config.PluginConfig;
import io.informant.container.config.PointcutConfig;
import io.informant.container.config.PointcutConfig.CaptureItem;
import io.informant.container.config.PointcutConfig.MethodModifier;
import io.informant.container.config.StorageConfig;
import io.informant.container.config.UserConfig;
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
        config.setWarnOnSpanOutsideTrace(coreConfig.isWarnOnSpanOutsideTrace());
        return config;
    }

    public String updateGeneralConfig(GeneralConfig config) throws Exception {
        io.informant.config.GeneralConfig updatedConfig =
                new io.informant.config.GeneralConfig(config.isEnabled(),
                        config.getStoreThresholdMillis(),
                        config.getStuckThresholdSeconds(),
                        config.getMaxSpans(),
                        config.isWarnOnSpanOutsideTrace());
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

    public UserConfig getUserConfig() {
        io.informant.config.UserConfig coreConfig = configService.getUserConfig();
        UserConfig config = new UserConfig(coreConfig.getVersion());
        config.setEnabled(coreConfig.isEnabled());
        config.setUserId(coreConfig.getUserId());
        config.setStoreThresholdMillis(coreConfig.getStoreThresholdMillis());
        config.setFineProfiling(coreConfig.isFineProfiling());
        return config;
    }

    public String updateUserConfig(UserConfig config) throws Exception {
        io.informant.config.UserConfig updatedConfig = new io.informant.config.UserConfig(
                config.isEnabled(), config.getUserId(), config.getStoreThresholdMillis(),
                config.isFineProfiling());
        return configService.updateUserConfig(updatedConfig, config.getVersion());
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

    public List<PointcutConfig> getPointcutConfigs() {
        List<PointcutConfig> configs = Lists.newArrayList();
        for (io.informant.config.PointcutConfig coreConfig : configService
                .getPointcutConfigs()) {
            configs.add(convertToCore(coreConfig));
        }
        return configs;
    }

    public String addPointcutConfig(PointcutConfig config) throws Exception {
        return configService.insertPointcutConfig(convertToCore(config));
    }

    public String updatePointcutConfig(String version, PointcutConfig config) throws Exception {
        return configService.updatePointcutConfig(version, convertToCore(config));
    }

    public void removePointcutConfig(String version) throws Exception {
        configService.deletePointcutConfig(version);
    }

    public void retransformClasses() throws Exception {
        throw new IllegalStateException(
                "Retransforming classes only works inside javaagent container");
    }

    public void compactData() throws SQLException {
        dataSource.compact();
    }

    public void resetAllConfig() throws IOException {
        configService.resetAllConfig();
    }

    private static PointcutConfig convertToCore(
            io.informant.config.PointcutConfig coreConfig) {
        List<CaptureItem> captureItems = Lists.newArrayList();
        for (io.informant.config.PointcutConfig.CaptureItem captureItem : coreConfig
                .getCaptureItems()) {
            captureItems.add(CaptureItem.valueOf(captureItem.name()));
        }
        List<MethodModifier> methodModifiers = Lists.newArrayList();
        for (io.informant.api.weaving.MethodModifier methodModifier : coreConfig
                .getMethodModifiers()) {
            methodModifiers.add(MethodModifier.valueOf(methodModifier.name()));
        }

        PointcutConfig config = new PointcutConfig(coreConfig.getVersion());
        config.setCaptureItems(captureItems);
        config.setTypeName(coreConfig.getTypeName());
        config.setMethodName(coreConfig.getMethodName());
        config.setMethodArgTypeNames(coreConfig.getMethodArgTypeNames());
        config.setMethodReturnTypeName(coreConfig.getMethodReturnTypeName());
        config.setMethodModifiers(methodModifiers);
        config.setMetricName(coreConfig.getMetricName());
        config.setSpanTemplate(coreConfig.getSpanTemplate());
        return config;
    }

    private static io.informant.config.PointcutConfig convertToCore(PointcutConfig config) {
        List<io.informant.config.PointcutConfig.CaptureItem> captureItems =
                Lists.newArrayList();
        for (CaptureItem captureItem : config.getCaptureItems()) {
            captureItems.add(io.informant.config.PointcutConfig.CaptureItem
                    .valueOf(captureItem.name()));
        }
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
        return new io.informant.config.PointcutConfig(captureItems, typeName, methodName,
                config.getMethodArgTypeNames(), methodReturnTypeName, methodModifiers,
                config.getMetricName(), config.getSpanTemplate(), config.getTraceGrouping());
    }
}
