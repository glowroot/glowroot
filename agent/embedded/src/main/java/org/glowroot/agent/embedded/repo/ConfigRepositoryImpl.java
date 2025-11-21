/*
 * Copyright 2011-2023 the original author or authors.
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
package org.glowroot.agent.embedded.repo;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.agent.config.AllConfig;
import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.config.PluginCache;
import org.glowroot.agent.config.PluginConfig;
import org.glowroot.agent.config.PluginDescriptor;
import org.glowroot.agent.embedded.config.AdminConfigService;
import org.glowroot.common.config.AdvancedConfig;
import org.glowroot.common.config.AlertConfig;
import org.glowroot.common.config.GaugeConfig;
import org.glowroot.common.config.ImmutableAlertConfig;
import org.glowroot.common.config.InstrumentationConfig;
import org.glowroot.common.config.JvmConfig;
import org.glowroot.common.config.TransactionConfig;
import org.glowroot.common.config.UiDefaultsConfig;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.common.util.Versions;
import org.glowroot.common2.config.AllCentralAdminConfig;
import org.glowroot.common2.config.AllEmbeddedAdminConfig;
import org.glowroot.common2.config.CentralAdminGeneralConfig;
import org.glowroot.common2.config.CentralStorageConfig;
import org.glowroot.common2.config.CentralWebConfig;
import org.glowroot.common2.config.EmbeddedAdminGeneralConfig;
import org.glowroot.common2.config.EmbeddedStorageConfig;
import org.glowroot.common2.config.EmbeddedWebConfig;
import org.glowroot.common2.config.HealthchecksIoConfig;
import org.glowroot.common2.config.HttpProxyConfig;
import org.glowroot.common2.config.ImmutableAllEmbeddedAdminConfig;
import org.glowroot.common2.config.ImmutableRoleConfig;
import org.glowroot.common2.config.ImmutableUserConfig;
import org.glowroot.common2.config.LdapConfig;
import org.glowroot.common2.config.PagerDutyConfig;
import org.glowroot.common2.config.PagerDutyConfig.PagerDutyIntegrationKey;
import org.glowroot.common2.config.RoleConfig;
import org.glowroot.common2.config.SlackConfig;
import org.glowroot.common2.config.SlackConfig.SlackWebhook;
import org.glowroot.common2.config.SmtpConfig;
import org.glowroot.common2.config.StorageConfig;
import org.glowroot.common2.config.UserConfig;
import org.glowroot.common2.config.WebConfig;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.common2.repo.ConfigRepository;
import org.glowroot.common2.repo.ConfigValidation;
import org.glowroot.common2.repo.util.LazySecretKey;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;

import static com.google.common.base.Preconditions.checkState;

public class ConfigRepositoryImpl implements ConfigRepository {

    private final ConfigService configService;
    private final AdminConfigService adminConfigService;
    private final PluginCache pluginCache;
    private final boolean configReadOnly;

    private final ImmutableList<RollupConfig> rollupConfigs;

    private final LazySecretKey lazySecretKey;

    private final Object writeLock = new Object();

    public ConfigRepositoryImpl(List<File> confDirs, boolean configReadOnly,
            @Nullable Integer webPortOverride, ConfigService configService,
            PluginCache pluginCache) {
        this.configService = configService;
        this.adminConfigService =
                AdminConfigService.create(confDirs, configReadOnly, webPortOverride);
        this.pluginCache = pluginCache;
        this.configReadOnly = configReadOnly;
        rollupConfigs = ImmutableList.copyOf(RollupConfig.buildRollupConfigs());
        lazySecretKey = new LazySecretKeyImpl(confDirs);
    }

    @Override
    public AgentConfig.GeneralConfig getGeneralConfig(String agentRollupId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public AgentConfig.TransactionConfig getTransactionConfig(String agentId) {
        return configService.getTransactionConfig().toProto();
    }

    @Override
    public AgentConfig.JvmConfig getJvmConfig(String agentRollupId) {
        return configService.getJvmConfig().toProto();
    }

    @Override
    public AgentConfig.UiDefaultsConfig getUiDefaultsConfig(String agentRollupId) {
        return configService.getUiDefaultsConfig().toProto();
    }

    @Override
    public CompletionStage<AgentConfig.AdvancedConfig> getAdvancedConfig(String agentRollupId) {
        return CompletableFuture.completedFuture(configService.getAdvancedConfig().toProto());
    }

    @Override
    public List<AgentConfig.GaugeConfig> getGaugeConfigs(String agentId) {
        List<AgentConfig.GaugeConfig> gaugeConfigs = Lists.newArrayList();
        for (GaugeConfig gaugeConfig : configService.getGaugeConfigs()) {
            gaugeConfigs.add(gaugeConfig.toProto());
        }
        return gaugeConfigs;
    }

    @Override
    public AgentConfig. /*@Nullable*/ GaugeConfig getGaugeConfig(String agentId, String version) {
        for (GaugeConfig gaugeConfig : configService.getGaugeConfigs()) {
            AgentConfig.GaugeConfig config = gaugeConfig.toProto();
            if (Versions.getVersion(config).equals(version)) {
                return config;
            }
        }
        return null;
    }

    @Override
    public CompletionStage<List<AgentConfig.SyntheticMonitorConfig>> getSyntheticMonitorConfigs(
            String agentRollupId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletionStage<AgentConfig. /*@Nullable*/ SyntheticMonitorConfig> getSyntheticMonitorConfig(
            String agentRollupId, String syntheticMonitorId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletionStage<List<AgentConfig.AlertConfig>> getAlertConfigs(String agentRollupId) {
        List<AgentConfig.AlertConfig> configs = Lists.newArrayList();
        for (AlertConfig config : configService.getAlertConfigs()) {
            configs.add(config.toProto());
        }
        return CompletableFuture.completedFuture(configs);
    }

    @Override
    public CompletionStage<List<AgentConfig.AlertConfig>> getAlertConfigsNonBlocking(String agentRollupId) {
        return getAlertConfigs(agentRollupId);
    }

    @Override
    public CompletionStage<AgentConfig. /*@Nullable*/ AlertConfig> getAlertConfig(String agentRollupId,
            String alertVersion) {
        for (AlertConfig alertConfig : configService.getAlertConfigs()) {
            AgentConfig.AlertConfig config = alertConfig.toProto();
            if (Versions.getVersion(config).equals(alertVersion)) {
                return CompletableFuture.completedFuture(config);
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public List<AgentConfig.PluginConfig> getPluginConfigs(String agentId) {
        List<AgentConfig.PluginConfig> configs = Lists.newArrayList();
        for (PluginConfig config : configService.getPluginConfigs()) {
            configs.add(config.toProto());
        }
        return configs;
    }

    @Override
    public AgentConfig. /*@Nullable*/ PluginConfig getPluginConfig(String agentId,
            String pluginId) {
        PluginConfig pluginConfig = configService.getPluginConfig(pluginId);
        if (pluginConfig == null) {
            return null;
        }
        return pluginConfig.toProto();
    }

    @Override
    public List<AgentConfig.InstrumentationConfig> getInstrumentationConfigs(String agentId) {
        List<AgentConfig.InstrumentationConfig> configs = Lists.newArrayList();
        for (InstrumentationConfig config : configService.getInstrumentationConfigs()) {
            configs.add(config.toProto());
        }
        return configs;
    }

    @Override
    public AgentConfig. /*@Nullable*/ InstrumentationConfig getInstrumentationConfig(String agentId,
            String version) {
        for (InstrumentationConfig config : configService.getInstrumentationConfigs()) {
            AgentConfig.InstrumentationConfig protoConfig = config.toProto();
            if (Versions.getVersion(protoConfig).equals(version)) {
                return protoConfig;
            }
        }
        return null;
    }

    @Override
    public AgentConfig getAllConfig(String agentId) {
        return configService.getAgentConfig();
    }

    @Override
    public EmbeddedAdminGeneralConfig getEmbeddedAdminGeneralConfig() {
        return adminConfigService.getEmbeddedAdminGeneralConfig();
    }

    @Override
    public CompletionStage<CentralAdminGeneralConfig> getCentralAdminGeneralConfig() {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletionStage<List<UserConfig>> getUserConfigs() {
        return CompletableFuture.completedFuture(adminConfigService.getUserConfigs());
    }

    @Override
    public CompletionStage<UserConfig> getUserConfig(String username) {
        for (UserConfig config : getUserConfigs().toCompletableFuture().join()) {
            if (config.username().equals(username)) {
                return CompletableFuture.completedFuture(config);
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<UserConfig> getUserConfigCaseInsensitive(String username) {
        for (UserConfig config : getUserConfigs().toCompletableFuture().join()) {
            if (config.username().equalsIgnoreCase(username)) {
                return CompletableFuture.completedFuture(config);
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Boolean> namedUsersExist() {
        for (UserConfig config : getUserConfigs().toCompletableFuture().join()) {
            if (!config.username().equalsIgnoreCase("anonymous")) {
                return CompletableFuture.completedFuture(true);
            }
        }
        return CompletableFuture.completedFuture(false);
    }

    @Override
    public CompletionStage<List<RoleConfig>> getRoleConfigs() {
        return CompletableFuture.completedFuture(adminConfigService.getRoleConfigs());
    }

    @Override
    public CompletionStage<RoleConfig> getRoleConfig(String name) {
        for (RoleConfig config : getRoleConfigs().toCompletableFuture().join()) {
            if (config.name().equals(name)) {
                return CompletableFuture.completedFuture(config);
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<WebConfig> getWebConfig() {
        return CompletableFuture.completedFuture(getEmbeddedWebConfig());
    }

    @Override
    public EmbeddedWebConfig getEmbeddedWebConfig() {
        return adminConfigService.getEmbeddedWebConfig();
    }

    @Override
    public CompletionStage<CentralWebConfig> getCentralWebConfig() {
        throw new UnsupportedOperationException();
    }

    @Override
    public StorageConfig getStorageConfig() {
        return getEmbeddedStorageConfig();
    }

    @Override
    public EmbeddedStorageConfig getEmbeddedStorageConfig() {
        return adminConfigService.getEmbeddedStorageConfig();
    }

    @Override
    public CompletionStage<CentralStorageConfig> getCentralStorageConfig() {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletionStage<SmtpConfig> getSmtpConfig() {
        return CompletableFuture.completedFuture(adminConfigService.getSmtpConfig());
    }

    @Override
    public CompletionStage<HttpProxyConfig> getHttpProxyConfig() {
        return CompletableFuture.completedFuture(adminConfigService.getHttpProxyConfig());
    }

    @Override
    public CompletionStage<LdapConfig> getLdapConfig() {
        return CompletableFuture.completedFuture(adminConfigService.getLdapConfig());
    }

    @Override
    public CompletionStage<PagerDutyConfig> getPagerDutyConfig() {
        return CompletableFuture.completedFuture(adminConfigService.getPagerDutyConfig());
    }

    @Override
    public CompletionStage<SlackConfig> getSlackConfig() {
        return CompletableFuture.completedFuture(adminConfigService.getSlackConfig());
    }

    @Override
    public HealthchecksIoConfig getHealthchecksIoConfig() {
        return adminConfigService.getHealthchecksIoConfig();
    }

    @Override
    public AllEmbeddedAdminConfig getAllEmbeddedAdminConfig() {
        return adminConfigService.getAllAdminConfig();
    }

    @Override
    public CompletionStage<AllCentralAdminConfig> getAllCentralAdminConfig() {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletionStage<Boolean> isConfigReadOnly(String agentId) {
        return CompletableFuture.completedFuture(configReadOnly);
    }

    @Override
    public CompletionStage<?> updateGeneralConfig(String agentId, AgentConfig.GeneralConfig protoConfig,
                                    String priorVersion, CassandraProfile profile) throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletionStage<?> updateTransactionConfig(String agentId, AgentConfig.TransactionConfig protoConfig,
            String priorVersion, CassandraProfile profile) throws Exception {
        TransactionConfig config = TransactionConfig.create(protoConfig);
        synchronized (writeLock) {
            String currVersion =
                    Versions.getVersion(configService.getTransactionConfig().toProto());
            checkVersionsEqual(currVersion, priorVersion);
            configService.updateTransactionConfig(config);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> insertGaugeConfig(String agentId, AgentConfig.GaugeConfig protoConfig, CassandraProfile profile)
            throws Exception {
        GaugeConfig config = GaugeConfig.create(protoConfig);
        synchronized (writeLock) {
            List<GaugeConfig> configs = Lists.newArrayList(configService.getGaugeConfigs());
            // check for duplicate mbeanObjectName
            for (GaugeConfig loopConfig : configs) {
                if (loopConfig.mbeanObjectName().equals(protoConfig.getMbeanObjectName())) {
                    throw new DuplicateMBeanObjectNameException();
                }
            }
            // no need to check for exact match since redundant with dup mbean object name check
            configs.add(config);
            configService.updateGaugeConfigs(configs);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> updateGaugeConfig(String agentId, AgentConfig.GaugeConfig protoConfig,
            String priorVersion, CassandraProfile profile) throws Exception {
        GaugeConfig config = GaugeConfig.create(protoConfig);
        synchronized (writeLock) {
            List<GaugeConfig> configs = Lists.newArrayList(configService.getGaugeConfigs());
            boolean found = false;
            for (ListIterator<GaugeConfig> i = configs.listIterator(); i.hasNext();) {
                GaugeConfig loopConfig = i.next();
                String loopVersion = Versions.getVersion(loopConfig.toProto());
                if (loopVersion.equals(priorVersion)) {
                    i.set(config);
                    found = true;
                } else if (loopConfig.mbeanObjectName().equals(protoConfig.getMbeanObjectName())) {
                    throw new DuplicateMBeanObjectNameException();
                }
                // no need to check for exact match since redundant with dup mbean object name check
            }
            if (!found) {
                throw new OptimisticLockException();
            }
            configService.updateGaugeConfigs(configs);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> deleteGaugeConfig(String agentId, String version, CassandraProfile profile) throws Exception {
        synchronized (writeLock) {
            List<GaugeConfig> configs = Lists.newArrayList(configService.getGaugeConfigs());
            boolean found = false;
            for (ListIterator<GaugeConfig> i = configs.listIterator(); i.hasNext();) {
                GaugeConfig loopConfig = i.next();
                String loopVersion = Versions.getVersion(loopConfig.toProto());
                if (loopVersion.equals(version)) {
                    i.remove();
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new OptimisticLockException();
            }
            configService.updateGaugeConfigs(configs);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> updateJvmConfig(String agentId, AgentConfig.JvmConfig protoConfig,
            String priorVersion, CassandraProfile profile) throws Exception {
        JvmConfig config = JvmConfig.create(protoConfig);
        synchronized (writeLock) {
            String currVersion =
                    Versions.getVersion(configService.getJvmConfig().toProto());
            checkVersionsEqual(currVersion, priorVersion);
            configService.updateJvmConfig(config);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> insertSyntheticMonitorConfig(String agentRollupId,
            AgentConfig.SyntheticMonitorConfig config, CassandraProfile profile) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletionStage<?> updateSyntheticMonitorConfig(String agentRollupId,
            AgentConfig.SyntheticMonitorConfig config, String priorVersion, CassandraProfile profile) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletionStage<?> deleteSyntheticMonitorConfig(String agentRollupId, String syntheticMonitorId, CassandraProfile profile) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletionStage<?> insertAlertConfig(String agentRollupId, AgentConfig.AlertConfig protoConfig, CassandraProfile profile)
            throws Exception {
        String version = Versions.getVersion(protoConfig);
        ImmutableAlertConfig config = AlertConfig.create(protoConfig);
        synchronized (writeLock) {
            List<AlertConfig> configs =
                    Lists.newArrayList(configService.getAlertConfigs());
            // check for exact duplicate
            for (AlertConfig loopConfig : configs) {
                if (Versions.getVersion(loopConfig.toProto()).equals(version)) {
                    throw new IllegalStateException("This exact alert already exists");
                }
            }
            configs.add(config);
            configService.updateAlertConfigs(configs);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> updateAlertConfig(String agentRollupId, AgentConfig.AlertConfig config,
            String priorVersion, CassandraProfile profile) {
        synchronized (writeLock) {
            List<AlertConfig> configs = Lists.newArrayList(configService.getAlertConfigs());
            boolean found = false;
            for (ListIterator<AlertConfig> i = configs.listIterator(); i.hasNext();) {
                AlertConfig loopConfig = i.next();
                String loopVersion = Versions.getVersion(loopConfig.toProto());
                if (loopVersion.equals(priorVersion)) {
                    i.set(AlertConfig.create(config));
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new OptimisticLockException();
            }
            try {
                configService.updateAlertConfigs(configs);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> deleteAlertConfig(String agentRollupId, String version, CassandraProfile profile) {
        synchronized (writeLock) {
            List<AlertConfig> configs = Lists.newArrayList(configService.getAlertConfigs());
            boolean found = false;
            for (ListIterator<AlertConfig> i = configs.listIterator(); i.hasNext();) {
                AlertConfig loopConfig = i.next();
                String loopVersion = Versions.getVersion(loopConfig.toProto());
                if (version.equals(loopVersion)) {
                    i.remove();
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new OptimisticLockException();
            }
            try {
                configService.updateAlertConfigs(configs);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> updateUiDefaultsConfig(String agentId, AgentConfig.UiDefaultsConfig protoConfig,
            String priorVersion, CassandraProfile profile) throws Exception {
        UiDefaultsConfig config = UiDefaultsConfig.create(protoConfig);
        synchronized (writeLock) {
            String currVersion = Versions.getVersion(configService.getUiDefaultsConfig().toProto());
            checkVersionsEqual(currVersion, priorVersion);
            configService.updateUiDefaultsConfig(config);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> updatePluginConfig(String agentId, AgentConfig.PluginConfig protoConfig,
            String priorVersion, CassandraProfile profile) throws Exception {
        PluginDescriptor pluginDescriptor = getPluginDescriptor(protoConfig.getId());
        PluginConfig config = PluginConfig.create(pluginDescriptor, protoConfig.getPropertyList());
        synchronized (writeLock) {
            List<PluginConfig> configs = Lists.newArrayList(configService.getPluginConfigs());
            boolean found = false;
            for (ListIterator<PluginConfig> i = configs.listIterator(); i.hasNext();) {
                PluginConfig loopPluginConfig = i.next();
                if (protoConfig.getId().equals(loopPluginConfig.id())) {
                    String loopVersion = Versions.getVersion(loopPluginConfig.toProto());
                    checkVersionsEqual(loopVersion, priorVersion);
                    i.set(config);
                    found = true;
                    break;
                }
            }
            checkState(found, "Plugin config not found: %s", protoConfig.getId());
            configService.updatePluginConfigs(configs);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> insertInstrumentationConfig(String agentId,
            AgentConfig.InstrumentationConfig protoConfig, CassandraProfile profile) throws Exception {
        InstrumentationConfig config = InstrumentationConfig.create(protoConfig);
        synchronized (writeLock) {
            List<InstrumentationConfig> configs =
                    Lists.newArrayList(configService.getInstrumentationConfigs());
            if (configs.contains(config)) {
                throw new IllegalStateException("This exact instrumentation already exists");
            }
            configs.add(config);
            configService.updateInstrumentationConfigs(configs);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> updateInstrumentationConfig(String agentId,
            AgentConfig.InstrumentationConfig protoConfig, String priorVersion, CassandraProfile profile) throws Exception {
        InstrumentationConfig config = InstrumentationConfig.create(protoConfig);
        synchronized (writeLock) {
            List<InstrumentationConfig> configs =
                    Lists.newArrayList(configService.getInstrumentationConfigs());
            boolean found = false;
            for (ListIterator<InstrumentationConfig> i = configs.listIterator(); i.hasNext();) {
                InstrumentationConfig loopConfig = i.next();
                String loopVersion = Versions.getVersion(loopConfig.toProto());
                if (loopVersion.equals(priorVersion)) {
                    i.set(config);
                    found = true;
                } else if (loopConfig.equals(config)) {
                    throw new IllegalStateException("This exact instrumentation already exists");
                }
            }
            if (!found) {
                throw new OptimisticLockException();
            }
            configService.updateInstrumentationConfigs(configs);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> deleteInstrumentationConfigs(String agentId, List<String> versions, CassandraProfile profile)
            throws Exception {
        synchronized (writeLock) {
            List<InstrumentationConfig> configs =
                    Lists.newArrayList(configService.getInstrumentationConfigs());
            List<String> remainingVersions = Lists.newArrayList(versions);
            for (ListIterator<InstrumentationConfig> i = configs.listIterator(); i.hasNext();) {
                InstrumentationConfig loopConfig = i.next();
                String loopVersion = Versions.getVersion(loopConfig.toProto());
                if (remainingVersions.contains(loopVersion)) {
                    i.remove();
                    remainingVersions.remove(loopVersion);
                }
            }
            if (!remainingVersions.isEmpty()) {
                throw new OptimisticLockException();
            }
            configService.updateInstrumentationConfigs(configs);
        }
        return CompletableFuture.completedFuture(null);
    }

    // ignores any instrumentation configs that are duplicates of existing instrumentation configs
    @Override
    public CompletionStage<?> insertInstrumentationConfigs(String agentId,
            List<AgentConfig.InstrumentationConfig> protoConfigs, CassandraProfile profile) throws Exception {
        List<InstrumentationConfig> configs = Lists.newArrayList();
        for (AgentConfig.InstrumentationConfig instrumentationConfig : protoConfigs) {
            InstrumentationConfig config = InstrumentationConfig.create(instrumentationConfig);
            configs.add(config);
        }
        synchronized (writeLock) {
            List<InstrumentationConfig> existingConfigs =
                    Lists.newArrayList(configService.getInstrumentationConfigs());
            for (InstrumentationConfig config : configs) {
                if (!existingConfigs.contains(config)) {
                    existingConfigs.add(config);
                }
            }
            configService.updateInstrumentationConfigs(existingConfigs);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> updateAdvancedConfig(String agentId, AgentConfig.AdvancedConfig protoConfig,
            String priorVersion, CassandraProfile profile) throws Exception {
        AdvancedConfig config = AdvancedConfig.create(protoConfig);
        synchronized (writeLock) {
            String currVersion = Versions.getVersion(configService.getAdvancedConfig().toProto());
            checkVersionsEqual(currVersion, priorVersion);
            configService.updateAdvancedConfig(config);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> updateAllConfig(String agentId, AgentConfig config, @Nullable String priorVersion, CassandraProfile profile)
            throws Exception {
        ConfigValidation.validatePartOne(config);
        Set<String> validPluginIds = Sets.newHashSet();
        for (PluginDescriptor pluginDescriptor : pluginCache.pluginDescriptors()) {
            validPluginIds.add(pluginDescriptor.id());
        }
        ConfigValidation.validatePartTwo(config, validPluginIds);
        synchronized (writeLock) {
            AgentConfig existingAgentConfig = configService.getAgentConfig();
            if (priorVersion != null
                    && !priorVersion.equals(Versions.getVersion(existingAgentConfig))) {
                throw new OptimisticLockException();
            }
            configService
                    .updateAllConfig(AllConfig.create(config, pluginCache.pluginDescriptors()));
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> updateEmbeddedAdminGeneralConfig(EmbeddedAdminGeneralConfig config,
            String priorVersion, CassandraProfile profile) throws Exception {
        synchronized (writeLock) {
            String currVersion = adminConfigService.getEmbeddedAdminGeneralConfig().version();
            checkVersionsEqual(currVersion, priorVersion);
            adminConfigService.updateEmbeddedAdminGeneralConfig(config);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> updateCentralAdminGeneralConfig(CentralAdminGeneralConfig config,
            String priorVersion, CassandraProfile profile) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletionStage<?> insertUserConfig(UserConfig config, CassandraProfile profile) throws Exception {
        synchronized (writeLock) {
            List<UserConfig> configs = Lists.newArrayList(adminConfigService.getUserConfigs());
            // check for case-insensitive duplicate
            String username = config.username();
            for (UserConfig loopConfig : configs) {
                if (loopConfig.username().equalsIgnoreCase(username)) {
                    throw new DuplicateUsernameException();
                }
            }
            configs.add(ImmutableUserConfig.copyOf(config));
            adminConfigService.updateUserConfigs(configs);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> updateUserConfig(UserConfig config, String priorVersion, CassandraProfile profile) throws Exception {
        synchronized (writeLock) {
            List<UserConfig> configs = Lists.newArrayList(adminConfigService.getUserConfigs());
            String username = config.username();
            boolean found = false;
            for (ListIterator<UserConfig> i = configs.listIterator(); i.hasNext();) {
                UserConfig loopConfig = i.next();
                if (loopConfig.username().equals(username)) {
                    if (loopConfig.version().equals(priorVersion)) {
                        i.set(ImmutableUserConfig.copyOf(config));
                        found = true;
                        break;
                    } else {
                        throw new OptimisticLockException();
                    }
                }
            }
            if (!found) {
                throw new UserNotFoundException();
            }
            adminConfigService.updateUserConfigs(configs);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> deleteUserConfig(String username, CassandraProfile profile) throws Exception {
        synchronized (writeLock) {
            List<UserConfig> configs = Lists.newArrayList(adminConfigService.getUserConfigs());
            boolean found = false;
            for (ListIterator<UserConfig> i = configs.listIterator(); i.hasNext();) {
                UserConfig loopConfig = i.next();
                if (loopConfig.username().equals(username)) {
                    i.remove();
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new UserNotFoundException();
            }
            if (getLdapConfig().toCompletableFuture().join().host().isEmpty() && configs.isEmpty()) {
                throw new CannotDeleteLastUserException();
            }
            adminConfigService.updateUserConfigs(configs);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> insertRoleConfig(RoleConfig config, CassandraProfile profile) throws Exception {
        synchronized (writeLock) {
            List<RoleConfig> configs = Lists.newArrayList(adminConfigService.getRoleConfigs());
            // check for case-insensitive duplicate
            String name = config.name();
            for (RoleConfig loopConfig : configs) {
                if (loopConfig.name().equalsIgnoreCase(name)) {
                    throw new DuplicateRoleNameException();
                }
            }
            configs.add(ImmutableRoleConfig.copyOf(config));
            adminConfigService.updateRoleConfigs(configs);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> updateRoleConfig(RoleConfig config, String priorVersion, CassandraProfile profile) throws Exception {
        synchronized (writeLock) {
            List<RoleConfig> configs = Lists.newArrayList(adminConfigService.getRoleConfigs());
            String name = config.name();
            boolean found = false;
            for (ListIterator<RoleConfig> i = configs.listIterator(); i.hasNext();) {
                RoleConfig loopConfig = i.next();
                if (loopConfig.name().equals(name)) {
                    if (loopConfig.version().equals(priorVersion)) {
                        i.set(ImmutableRoleConfig.copyOf(config));
                        found = true;
                        break;
                    } else {
                        throw new OptimisticLockException();
                    }
                }
            }
            if (!found) {
                throw new RoleNotFoundException();
            }
            adminConfigService.updateRoleConfigs(configs);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> deleteRoleConfig(String name, CassandraProfile profile) {
        synchronized (writeLock) {
            List<RoleConfig> configs = Lists.newArrayList(adminConfigService.getRoleConfigs());
            boolean found = false;
            for (ListIterator<RoleConfig> i = configs.listIterator(); i.hasNext();) {
                RoleConfig loopConfig = i.next();
                if (loopConfig.name().equals(name)) {
                    i.remove();
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new RoleNotFoundException();
            }
            if (configs.isEmpty()) {
                throw new CannotDeleteLastRoleException();
            }
            adminConfigService.updateRoleConfigs(configs);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void updateEmbeddedWebConfig(EmbeddedWebConfig config, String priorVersion)
            throws Exception {
        synchronized (writeLock) {
            String currVersion = adminConfigService.getEmbeddedWebConfig().version();
            checkVersionsEqual(currVersion, priorVersion);
            adminConfigService.updateEmbeddedWebConfig(config);

        }
    }

    @Override
    public CompletionStage<?> updateCentralWebConfig(CentralWebConfig config, String priorVersion) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateEmbeddedStorageConfig(EmbeddedStorageConfig config, String priorVersion)
            throws Exception {
        synchronized (writeLock) {
            String currVersion = adminConfigService.getEmbeddedStorageConfig().version();
            checkVersionsEqual(currVersion, priorVersion);
            adminConfigService.updateEmbeddedStorageConfig(config);
        }
    }

    @Override
    public CompletionStage<?> updateCentralStorageConfig(CentralStorageConfig config, String priorVersion) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletionStage<?> updateSmtpConfig(SmtpConfig config, String priorVersion) throws Exception {
        synchronized (writeLock) {
            String currVersion = adminConfigService.getSmtpConfig().version();
            checkVersionsEqual(currVersion, priorVersion);
            adminConfigService.updateSmtpConfig(config);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> updateHttpProxyConfig(HttpProxyConfig config, String priorVersion)
            throws Exception {
        synchronized (writeLock) {
            String currVersion = adminConfigService.getHttpProxyConfig().version();
            checkVersionsEqual(currVersion, priorVersion);
            adminConfigService.updateHttpProxyConfig(config);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> updateLdapConfig(LdapConfig config, String priorVersion) throws Exception {
        synchronized (writeLock) {
            String currVersion = adminConfigService.getLdapConfig().version();
            checkVersionsEqual(currVersion, priorVersion);
            adminConfigService.updateLdapConfig(config);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> updatePagerDutyConfig(PagerDutyConfig config, String priorVersion)
            throws Exception {
        synchronized (writeLock) {
            String currVersion = adminConfigService.getPagerDutyConfig().version();
            checkVersionsEqual(currVersion, priorVersion);
            validatePagerDutyConfig(config);
            adminConfigService.updatePagerDutyConfig(config);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> updateSlackConfig(SlackConfig config, String priorVersion) throws Exception {
        synchronized (writeLock) {
            String currVersion = adminConfigService.getSlackConfig().version();
            checkVersionsEqual(currVersion, priorVersion);
            validateSlackConfig(config);
            adminConfigService.updateSlackConfig(config);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void updateHealthchecksIoConfig(HealthchecksIoConfig config, String priorVersion)
            throws Exception {
        synchronized (writeLock) {
            String currVersion = adminConfigService.getHealthchecksIoConfig().version();
            checkVersionsEqual(currVersion, priorVersion);
            adminConfigService.updateHealthchecksIoConfig(config);
        }
    }

    @Override
    public void updateAllEmbeddedAdminConfig(AllEmbeddedAdminConfig config,
            @Nullable String priorVersion) throws Exception {
        synchronized (writeLock) {
            AllEmbeddedAdminConfig existingConfig = adminConfigService.getAllAdminConfig();
            String currVersion = existingConfig.version();
            if (priorVersion != null) {
                checkVersionsEqual(currVersion, priorVersion);
            }
            Set<String> usernames = Sets.newHashSet();
            for (UserConfig userConfig : config.users()) {
                if (!usernames.add(userConfig.username())) {
                    throw new DuplicateUsernameException();
                }
            }
            Set<String> roleNames = Sets.newHashSet();
            for (RoleConfig roleConfig : config.roles()) {
                if (!roleNames.add(roleConfig.name())) {
                    throw new DuplicateRoleNameException();
                }
            }
            if (config.ldap().host().isEmpty() && config.users().isEmpty()) {
                throw new CannotDeleteLastUserException();
            }
            if (config.roles().isEmpty()) {
                throw new CannotDeleteLastRoleException();
            }
            validatePagerDutyConfig(config.pagerDuty());
            validateSlackConfig(config.slack());
            Map<String, String> existingUserPasswordHashes = Maps.newHashMap();
            for (UserConfig userConfig : existingConfig.users()) {
                String passwordHash = userConfig.passwordHash();
                if (!passwordHash.isEmpty()) {
                    existingUserPasswordHashes.put(userConfig.username(), passwordHash);
                }
            }
            List<UserConfig> userConfigs = Lists.newArrayList();
            for (UserConfig userConfig : config.users()) {
                String passwordHash = userConfig.passwordHash();
                if (passwordHash.isEmpty() && !userConfig.ldap()
                        && !userConfig.username().equalsIgnoreCase("anonymous")) {
                    String existingUserPasswordHash =
                            existingUserPasswordHashes.get(userConfig.username());
                    if (existingUserPasswordHash == null) {
                        throw new IllegalStateException(
                                "No existing password for user: " + userConfig.username());
                    }
                    userConfig = ImmutableUserConfig.copyOf(userConfig)
                            .withPasswordHash(existingUserPasswordHash);
                }
                userConfigs.add(userConfig);
            }
            config = ImmutableAllEmbeddedAdminConfig.copyOf(config)
                    .withUsers(userConfigs);
            adminConfigService.updateAllAdminConfig(config);
        }
    }

    @Override
    public void updateAllCentralAdminConfig(AllCentralAdminConfig config,
            @Nullable String priorVersion) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getGaugeCollectionIntervalMillis() {
        return configService.getGaugeCollectionIntervalMillis();
    }

    @Override
    public ImmutableList<RollupConfig> getRollupConfigs() {
        return rollupConfigs;
    }

    @Override
    public LazySecretKey getLazySecretKey() {
        return lazySecretKey;
    }

    private PluginDescriptor getPluginDescriptor(String pluginId) {
        for (PluginDescriptor pluginDescriptor : pluginCache.pluginDescriptors()) {
            if (pluginDescriptor.id().equals(pluginId)) {
                return pluginDescriptor;
            }
        }
        throw new IllegalStateException("Could not find plugin descriptor: " + pluginId);
    }

    @OnlyUsedByTests
    public void resetAdminConfigForTests() throws IOException {
        adminConfigService.resetAdminConfigForTests();
    }

    private static void checkVersionsEqual(String version, String priorVersion)
            throws OptimisticLockException {
        if (!version.equals(priorVersion)) {
            throw new OptimisticLockException();
        }
    }

    private static void validatePagerDutyConfig(PagerDutyConfig config) throws Exception {
        Set<String> integrationKeys = Sets.newHashSet();
        Set<String> integrationDisplays = Sets.newHashSet();
        for (PagerDutyIntegrationKey integrationKey : config.integrationKeys()) {
            if (!integrationKeys.add(integrationKey.key())) {
                throw new DuplicatePagerDutyIntegrationKeyException();
            }
            if (!integrationDisplays.add(integrationKey.display())) {
                throw new DuplicatePagerDutyIntegrationKeyDisplayException();
            }
        }
    }

    private static void validateSlackConfig(SlackConfig config) throws Exception {
        Set<String> webhookUrls = Sets.newHashSet();
        Set<String> webhookDisplays = Sets.newHashSet();
        for (SlackWebhook webhook : config.webhooks()) {
            if (!webhookUrls.add(webhook.url())) {
                throw new DuplicateSlackWebhookUrlException();
            }
            if (!webhookDisplays.add(webhook.display())) {
                throw new DuplicateSlackWebhookDisplayException();
            }
        }
    }
}
