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
package org.glowroot.central.repo;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;
import javax.crypto.SecretKey;

import com.google.common.base.Joiner;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.protobuf.InvalidProtocolBufferException;

import org.glowroot.common.config.AgentRollupConfig;
import org.glowroot.common.config.CentralStorageConfig;
import org.glowroot.common.config.FatStorageConfig;
import org.glowroot.common.config.ImmutableCentralStorageConfig;
import org.glowroot.common.config.ImmutableFatStorageConfig;
import org.glowroot.common.config.ImmutableLdapConfig;
import org.glowroot.common.config.ImmutableSmtpConfig;
import org.glowroot.common.config.ImmutableWebConfig;
import org.glowroot.common.config.LdapConfig;
import org.glowroot.common.config.RoleConfig;
import org.glowroot.common.config.SmtpConfig;
import org.glowroot.common.config.StorageConfig;
import org.glowroot.common.config.UserConfig;
import org.glowroot.common.config.WebConfig;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.util.LazySecretKey;
import org.glowroot.common.util.Versions;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AdvancedConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertKind;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.GaugeConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.InstrumentationConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginProperty;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginProperty.Value.ValCase;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.TransactionConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.UiConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.UserRecordingConfig;

public class ConfigRepositoryImpl implements ConfigRepository {

    // TODO this needs to be in sync with agents, so have agents pick up value from central
    private static final long GAUGE_COLLECTION_INTERVAL_MILLIS =
            Long.getLong("glowroot.internal.gaugeCollectionIntervalMillis", 5000);

    private final CentralConfigDao centralConfigDao;
    private final AgentDao agentDao;
    private final UserDao userDao;
    private final RoleDao roleDao;

    private final ImmutableList<RollupConfig> rollupConfigs;

    private final LazySecretKey secretKey;

    private final Set<ConfigListener> configListeners = Sets.newCopyOnWriteArraySet();

    // TODO use optimistic locking with retry instead of synchronization in order to work across
    // cluster
    private final LoadingCache<String, Object> agentConfigLocks =
            CacheBuilder.newBuilder().weakValues().build(new CacheLoader<String, Object>() {
                @Override
                public Object load(String key) throws Exception {
                    return new Object();
                }
            });
    private final Object agentConfigLock = new Object();
    private final Object userConfigLock = new Object();
    private final Object roleConfigLock = new Object();
    private final Object webConfigLock = new Object();
    private final Object storageConfigLock = new Object();
    private final Object smtpConfigLock = new Object();
    private final Object ldapConfigLock = new Object();

    public ConfigRepositoryImpl(CentralConfigDao centralConfigDao, AgentDao agentDao,
            UserDao userDao, RoleDao roleDao) {
        this.centralConfigDao = centralConfigDao;
        this.agentDao = agentDao;
        this.userDao = userDao;
        this.roleDao = roleDao;
        rollupConfigs = ImmutableList.copyOf(RollupConfig.buildRollupConfigs());
        secretKey = new LazySecretKey(new File("secret"));
    }

    @Override
    public @Nullable TransactionConfig getTransactionConfig(String agentId)
            throws InvalidProtocolBufferException {
        AgentConfig agentConfig = agentDao.readAgentConfig(agentId);
        if (agentConfig == null) {
            return null;
        }
        return agentConfig.getTransactionConfig();
    }

    @Override
    public @Nullable UiConfig getUiConfig(String agentId) throws IOException {
        AgentConfig agentConfig = agentDao.readAgentConfig(agentId);
        if (agentConfig == null) {
            return null;
        }
        return agentConfig.getUiConfig();
    }

    @Override
    public @Nullable UserRecordingConfig getUserRecordingConfig(String agentId) throws IOException {
        AgentConfig agentConfig = agentDao.readAgentConfig(agentId);
        if (agentConfig == null) {
            return null;
        }
        return agentConfig.getUserRecordingConfig();
    }

    @Override
    public @Nullable AdvancedConfig getAdvancedConfig(String agentId) throws IOException {
        AgentConfig agentConfig = agentDao.readAgentConfig(agentId);
        if (agentConfig == null) {
            return null;
        }
        return agentConfig.getAdvancedConfig();
    }

    @Override
    public List<GaugeConfig> getGaugeConfigs(String agentId)
            throws InvalidProtocolBufferException {
        AgentConfig agentConfig = agentDao.readAgentConfig(agentId);
        if (agentConfig == null) {
            return ImmutableList.of();
        }
        return agentConfig.getGaugeConfigList();
    }

    @Override
    public GaugeConfig getGaugeConfig(String agentId, String version)
            throws InvalidProtocolBufferException {
        for (GaugeConfig gaugeConfig : getGaugeConfigs(agentId)) {
            if (Versions.getVersion(gaugeConfig).equals(version)) {
                return gaugeConfig;
            }
        }
        throw new IllegalStateException("Gauge config not found: " + version);
    }

    @Override
    public List<AlertConfig> getAlertConfigs(String agentId) throws InvalidProtocolBufferException {
        AgentConfig agentConfig = agentDao.readAgentConfig(agentId);
        if (agentConfig == null) {
            return ImmutableList.of();
        }
        return agentConfig.getAlertConfigList();
    }

    @Override
    public List<AlertConfig> getTransactionAlertConfigs(String agentId)
            throws InvalidProtocolBufferException {
        List<AlertConfig> configs = Lists.newArrayList();
        for (AlertConfig config : getAlertConfigs(agentId)) {
            if (config.getKind() == AlertKind.TRANSACTION) {
                configs.add(config);
            }
        }
        return configs;
    }

    @Override
    public List<AlertConfig> getGaugeAlertConfigs(String agentId)
            throws InvalidProtocolBufferException {
        List<AlertConfig> configs = Lists.newArrayList();
        for (AlertConfig config : getAlertConfigs(agentId)) {
            if (config.getKind() == AlertKind.GAUGE) {
                configs.add(config);
            }
        }
        return configs;
    }

    @Override
    public @Nullable AlertConfig getAlertConfig(String agentId, String version)
            throws InvalidProtocolBufferException {
        for (AlertConfig alertConfig : getAlertConfigs(agentId)) {
            if (Versions.getVersion(alertConfig).equals(version)) {
                return alertConfig;
            }
        }
        return null;
    }

    @Override
    public List<PluginConfig> getPluginConfigs(String agentId)
            throws InvalidProtocolBufferException {
        AgentConfig agentConfig = agentDao.readAgentConfig(agentId);
        if (agentConfig == null) {
            return ImmutableList.of();
        }
        return agentConfig.getPluginConfigList();
    }

    @Override
    public PluginConfig getPluginConfig(String agentId, String pluginId)
            throws InvalidProtocolBufferException {
        for (PluginConfig pluginConfig : getPluginConfigs(agentId)) {
            if (pluginConfig.getId().equals(pluginId)) {
                return pluginConfig;
            }
        }
        throw new IllegalStateException("Plugin config not found: " + pluginId);
    }

    @Override
    public List<InstrumentationConfig> getInstrumentationConfigs(String agentId)
            throws InvalidProtocolBufferException {
        AgentConfig agentConfig = agentDao.readAgentConfig(agentId);
        if (agentConfig == null) {
            return ImmutableList.of();
        }
        return agentConfig.getInstrumentationConfigList();
    }

    @Override
    public InstrumentationConfig getInstrumentationConfig(String agentId, String version)
            throws IOException {
        for (InstrumentationConfig config : getInstrumentationConfigs(agentId)) {
            if (Versions.getVersion(config).equals(version)) {
                return config;
            }
        }
        throw new IllegalStateException("Instrumentation config not found: " + version);
    }

    @Override
    public @Nullable AgentRollupConfig getAgentRollupConfig(String agentRollupId) {
        return agentDao.readAgentRollupConfig(agentRollupId);
    }

    @Override
    public List<UserConfig> getUserConfigs() {
        return userDao.read();
    }

    @Override
    public @Nullable UserConfig getUserConfig(String username) {
        return userDao.read(username);
    }

    @Override
    public @Nullable UserConfig getUserConfigCaseInsensitive(String username) {
        return userDao.readCaseInsensitive(username);
    }

    @Override
    public boolean namedUsersExist() {
        return userDao.namedUsersExist();
    }

    @Override
    public List<RoleConfig> getRoleConfigs() {
        return roleDao.read();
    }

    @Override
    public @Nullable RoleConfig getRoleConfig(String name) {
        return roleDao.read(name);
    }

    @Override
    public WebConfig getWebConfig() {
        WebConfig config = centralConfigDao.read(WEB_KEY, ImmutableWebConfig.class);
        if (config == null) {
            return ImmutableWebConfig.builder()
                    .bindAddress("0.0.0.0")
                    .build();
        }
        return config;
    }

    @Override
    public FatStorageConfig getFatStorageConfig() {
        throw new UnsupportedOperationException();
    }

    @Override
    public CentralStorageConfig getCentralStorageConfig() {
        CentralStorageConfig config =
                centralConfigDao.read(STORAGE_KEY, ImmutableCentralStorageConfig.class);
        if (config == null) {
            return ImmutableCentralStorageConfig.builder().build();
        }
        if (config.hasListIssues()) {
            return withCorrectedLists(config);
        }
        return config;
    }

    @Override
    public SmtpConfig getSmtpConfig() {
        SmtpConfig config = centralConfigDao.read(SMTP_KEY, ImmutableSmtpConfig.class);
        if (config == null) {
            return ImmutableSmtpConfig.builder().build();
        }
        return config;
    }

    @Override
    public LdapConfig getLdapConfig() {
        LdapConfig config = centralConfigDao.read(LDAP_KEY, ImmutableLdapConfig.class);
        if (config == null) {
            return ImmutableLdapConfig.builder().build();
        }
        return config;
    }

    @Override
    public void updateTransactionConfig(String agentId, TransactionConfig transactionConfig,
            String priorVersion) throws Exception {
        synchronized (agentConfigLocks.getUnchecked(agentId)) {
            AgentConfig agentConfig = agentDao.readAgentConfig(agentId);
            if (agentConfig == null) {
                throw new IllegalStateException("Agent config not found");
            }
            String existingVersion = Versions.getVersion(agentConfig.getTransactionConfig());
            if (!priorVersion.equals(existingVersion)) {
                throw new OptimisticLockException();
            }
            AgentConfig updatedAgentConfig = agentConfig.toBuilder()
                    .setTransactionConfig(transactionConfig)
                    .build();
            agentDao.storeAgentConfig(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            notifyConfigListeners(agentId);
        }
    }

    @Override
    public void insertGaugeConfig(String agentId, GaugeConfig gaugeConfig) throws Exception {
        synchronized (agentConfigLocks.getUnchecked(agentId)) {
            AgentConfig agentConfig = agentDao.readAgentConfig(agentId);
            if (agentConfig == null) {
                throw new IllegalStateException("Agent config not found");
            }
            checkGaugeDoesNotExist(gaugeConfig, agentConfig.getGaugeConfigList());
            AgentConfig updatedAgentConfig = agentConfig.toBuilder()
                    .addGaugeConfig(gaugeConfig)
                    .build();
            agentDao.storeAgentConfig(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            notifyConfigListeners(agentId);
        }
    }

    @Override
    public void updateGaugeConfig(String agentId, GaugeConfig gaugeConfig, String priorVersion)
            throws Exception {
        synchronized (agentConfigLocks.getUnchecked(agentId)) {
            AgentConfig agentConfig = agentDao.readAgentConfig(agentId);
            if (agentConfig == null) {
                throw new IllegalStateException("Agent config not found");
            }
            List<GaugeConfig> existingGaugeConfigs =
                    Lists.newArrayList(agentConfig.getGaugeConfigList());
            ListIterator<GaugeConfig> i = existingGaugeConfigs.listIterator();
            boolean found = false;
            while (i.hasNext()) {
                GaugeConfig loopConfig = i.next();
                if (Versions.getVersion(loopConfig).equals(priorVersion)) {
                    i.set(gaugeConfig);
                    found = true;
                    break;
                } else if (loopConfig.getMbeanObjectName()
                        .equals(gaugeConfig.getMbeanObjectName())) {
                    throw new DuplicateMBeanObjectNameException();
                }
            }
            if (!found) {
                throw new OptimisticLockException();
            }
            AgentConfig updatedAgentConfig = agentConfig.toBuilder()
                    .clearGaugeConfig()
                    .addAllGaugeConfig(existingGaugeConfigs)
                    .build();
            agentDao.storeAgentConfig(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            notifyConfigListeners(agentId);
        }
    }

    @Override
    public void deleteGaugeConfig(String agentId, String version) throws Exception {
        synchronized (agentConfigLocks.getUnchecked(agentId)) {
            AgentConfig agentConfig = agentDao.readAgentConfig(agentId);
            if (agentConfig == null) {
                throw new IllegalStateException("Agent config not found");
            }
            List<GaugeConfig> existingGaugeConfigs =
                    Lists.newArrayList(agentConfig.getGaugeConfigList());
            ListIterator<GaugeConfig> i = existingGaugeConfigs.listIterator();
            boolean found = false;
            while (i.hasNext()) {
                if (Versions.getVersion(i.next()).equals(version)) {
                    i.remove();
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new OptimisticLockException();
            }
            AgentConfig updatedAgentConfig = agentConfig.toBuilder()
                    .clearGaugeConfig()
                    .addAllGaugeConfig(existingGaugeConfigs)
                    .build();
            agentDao.storeAgentConfig(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            notifyConfigListeners(agentId);
        }
    }

    @Override
    public void insertAlertConfig(String agentId, AlertConfig alertConfig) throws Exception {
        synchronized (agentConfigLocks.getUnchecked(agentId)) {
            AgentConfig agentConfig = agentDao.readAgentConfig(agentId);
            if (agentConfig == null) {
                throw new IllegalStateException("Agent config not found");
            }
            checkAlertDoesNotExist(alertConfig, agentConfig.getAlertConfigList());
            AgentConfig updatedAgentConfig = agentConfig.toBuilder()
                    .addAlertConfig(alertConfig)
                    .build();
            agentDao.storeAgentConfig(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            notifyConfigListeners(agentId);
        }
    }

    @Override
    public void updateAlertConfig(String agentId, AlertConfig alertConfig, String priorVersion)
            throws Exception {
        synchronized (agentConfigLocks.getUnchecked(agentId)) {
            AgentConfig agentConfig = agentDao.readAgentConfig(agentId);
            if (agentConfig == null) {
                throw new IllegalStateException("Agent config not found");
            }
            List<AlertConfig> existingAlertConfigs =
                    Lists.newArrayList(agentConfig.getAlertConfigList());
            ListIterator<AlertConfig> i = existingAlertConfigs.listIterator();
            boolean found = false;
            while (i.hasNext()) {
                if (Versions.getVersion(i.next()).equals(priorVersion)) {
                    i.set(alertConfig);
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new OptimisticLockException();
            }
            AgentConfig updatedAgentConfig = agentConfig.toBuilder()
                    .clearAlertConfig()
                    .addAllAlertConfig(existingAlertConfigs)
                    .build();
            agentDao.storeAgentConfig(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            notifyConfigListeners(agentId);
        }
    }

    @Override
    public void deleteAlertConfig(String agentId, String version) throws Exception {
        synchronized (agentConfigLocks.getUnchecked(agentId)) {
            AgentConfig agentConfig = agentDao.readAgentConfig(agentId);
            if (agentConfig == null) {
                throw new IllegalStateException("Agent config not found");
            }
            List<AlertConfig> existingAlertConfigs =
                    Lists.newArrayList(agentConfig.getAlertConfigList());
            ListIterator<AlertConfig> i = existingAlertConfigs.listIterator();
            boolean found = false;
            while (i.hasNext()) {
                if (Versions.getVersion(i.next()).equals(version)) {
                    i.remove();
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new OptimisticLockException();
            }
            AgentConfig updatedAgentConfig = agentConfig.toBuilder()
                    .clearAlertConfig()
                    .addAllAlertConfig(existingAlertConfigs)
                    .build();
            agentDao.storeAgentConfig(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            notifyConfigListeners(agentId);
        }
    }

    @Override
    public void updateUiConfig(String agentId, UiConfig uiConfig, String priorVersion)
            throws Exception {
        synchronized (agentConfigLocks.getUnchecked(agentId)) {
            AgentConfig agentConfig = agentDao.readAgentConfig(agentId);
            if (agentConfig == null) {
                throw new IllegalStateException("Agent config not found");
            }
            String existingVersion = Versions.getVersion(agentConfig.getUiConfig());
            if (!priorVersion.equals(existingVersion)) {
                throw new OptimisticLockException();
            }
            AgentConfig updatedAgentConfig = agentConfig.toBuilder()
                    .setUiConfig(uiConfig)
                    .build();
            agentDao.storeAgentConfig(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            notifyConfigListeners(agentId);
        }
    }

    @Override
    public void updatePluginConfig(String agentId, String pluginId,
            List<PluginProperty> properties, String priorVersion) throws Exception {
        synchronized (agentConfigLocks.getUnchecked(agentId)) {
            AgentConfig agentConfig = agentDao.readAgentConfig(agentId);
            if (agentConfig == null) {
                throw new IllegalStateException("Agent config not found");
            }
            List<PluginConfig> pluginConfigs =
                    Lists.newArrayList(agentConfig.getPluginConfigList());
            ListIterator<PluginConfig> i = pluginConfigs.listIterator();
            boolean found = false;
            while (i.hasNext()) {
                PluginConfig pluginConfig = i.next();
                if (pluginConfig.getId().equals(pluginId)) {
                    String existingVersion = Versions.getVersion(pluginConfig);
                    if (!priorVersion.equals(existingVersion)) {
                        throw new OptimisticLockException();
                    }
                    i.set(buildPluginConfig(pluginConfig, properties));
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new IllegalStateException("Plugin config not found: " + pluginId);
            }
            AgentConfig updatedAgentConfig = agentConfig.toBuilder()
                    .clearPluginConfig()
                    .addAllPluginConfig(pluginConfigs)
                    .build();
            agentDao.storeAgentConfig(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            notifyConfigListeners(agentId);
        }
    }

    @Override
    public void insertInstrumentationConfig(String agentId,
            InstrumentationConfig instrumentationConfig) throws Exception {
        synchronized (agentConfigLocks.getUnchecked(agentId)) {
            AgentConfig agentConfig = agentDao.readAgentConfig(agentId);
            if (agentConfig == null) {
                throw new IllegalStateException("Agent config not found");
            }
            checkInstrumentationDoesNotExist(instrumentationConfig,
                    agentConfig.getInstrumentationConfigList());
            AgentConfig updatedAgentConfig = agentConfig.toBuilder()
                    .addInstrumentationConfig(instrumentationConfig)
                    .build();
            agentDao.storeAgentConfig(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            notifyConfigListeners(agentId);
        }
    }

    @Override
    public void updateInstrumentationConfig(String agentId,
            InstrumentationConfig instrumentationConfig, String priorVersion) throws Exception {
        synchronized (agentConfigLocks.getUnchecked(agentId)) {
            AgentConfig agentConfig = agentDao.readAgentConfig(agentId);
            if (agentConfig == null) {
                throw new IllegalStateException("Agent config not found");
            }
            List<InstrumentationConfig> existingInstrumentationConfigs =
                    Lists.newArrayList(agentConfig.getInstrumentationConfigList());
            ListIterator<InstrumentationConfig> i = existingInstrumentationConfigs.listIterator();
            boolean found = false;
            while (i.hasNext()) {
                if (Versions.getVersion(i.next()).equals(priorVersion)) {
                    i.set(instrumentationConfig);
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new OptimisticLockException();
            }
            AgentConfig updatedAgentConfig = agentConfig.toBuilder()
                    .clearInstrumentationConfig()
                    .addAllInstrumentationConfig(existingInstrumentationConfigs)
                    .build();
            agentDao.storeAgentConfig(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            notifyConfigListeners(agentId);
        }
    }

    @Override
    public void deleteInstrumentationConfigs(String agentId, List<String> versions)
            throws Exception {
        synchronized (agentConfigLocks.getUnchecked(agentId)) {
            AgentConfig agentConfig = agentDao.readAgentConfig(agentId);
            if (agentConfig == null) {
                throw new IllegalStateException("Agent config not found");
            }
            List<InstrumentationConfig> existingInstrumentationConfigs =
                    Lists.newArrayList(agentConfig.getInstrumentationConfigList());
            ListIterator<InstrumentationConfig> i = existingInstrumentationConfigs.listIterator();
            List<String> remainingVersions = Lists.newArrayList(versions);
            while (i.hasNext()) {
                String currVersion = Versions.getVersion(i.next());
                if (remainingVersions.contains(currVersion)) {
                    i.remove();
                    remainingVersions.remove(currVersion);
                    break;
                }
            }
            if (!remainingVersions.isEmpty()) {
                throw new OptimisticLockException();
            }
            AgentConfig updatedAgentConfig = agentConfig.toBuilder()
                    .clearInstrumentationConfig()
                    .addAllInstrumentationConfig(existingInstrumentationConfigs)
                    .build();
            agentDao.storeAgentConfig(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            notifyConfigListeners(agentId);
        }
    }

    @Override
    public void insertInstrumentationConfigs(String agentId,
            List<InstrumentationConfig> configs) throws Exception {
        synchronized (agentConfigLocks.getUnchecked(agentId)) {
            AgentConfig agentConfig = agentDao.readAgentConfig(agentId);
            if (agentConfig == null) {
                throw new IllegalStateException("Agent config not found");
            }
            AgentConfig.Builder builder = agentConfig.toBuilder();
            List<InstrumentationConfig> instrumentationConfigs =
                    agentConfig.getInstrumentationConfigList();
            for (InstrumentationConfig config : configs) {
                checkInstrumentationDoesNotExist(config, instrumentationConfigs);
                instrumentationConfigs.add(config);
            }
            AgentConfig updatedAgentConfig = builder
                    .clearInstrumentationConfig()
                    .addAllInstrumentationConfig(instrumentationConfigs)
                    .build();
            agentDao.storeAgentConfig(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            notifyConfigListeners(agentId);
        }
    }

    @Override
    public void updateUserRecordingConfig(String agentId, UserRecordingConfig userRecordingConfig,
            String priorVersion) throws Exception {
        synchronized (agentConfigLocks.getUnchecked(agentId)) {
            AgentConfig agentConfig = agentDao.readAgentConfig(agentId);
            if (agentConfig == null) {
                throw new IllegalStateException("Agent config not found");
            }
            String existingVersion = Versions.getVersion(agentConfig.getUserRecordingConfig());
            if (!priorVersion.equals(existingVersion)) {
                throw new OptimisticLockException();
            }
            AgentConfig updatedAgentConfig = agentConfig.toBuilder()
                    .setUserRecordingConfig(userRecordingConfig)
                    .build();
            agentDao.storeAgentConfig(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            notifyConfigListeners(agentId);
        }
    }

    @Override
    public void updateAdvancedConfig(String agentId, AdvancedConfig advancedConfig,
            String priorVersion) throws Exception {
        synchronized (agentConfigLocks.getUnchecked(agentId)) {
            AgentConfig agentConfig = agentDao.readAgentConfig(agentId);
            if (agentConfig == null) {
                throw new IllegalStateException("Agent config not found");
            }
            String existingVersion = Versions.getVersion(agentConfig.getAdvancedConfig());
            if (!priorVersion.equals(existingVersion)) {
                throw new OptimisticLockException();
            }
            AgentConfig updatedAgentConfig = agentConfig.toBuilder()
                    .setAdvancedConfig(advancedConfig)
                    .build();
            agentDao.storeAgentConfig(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            notifyConfigListeners(agentId);
        }
    }

    @Override
    public void updateAgentRollupConfig(AgentRollupConfig agentRollupConfig, String priorVersion)
            throws Exception {
        synchronized (agentConfigLock) {
            AgentRollupConfig existingConfig =
                    agentDao.readAgentRollupConfig(agentRollupConfig.id());
            if (existingConfig == null || !existingConfig.version().equals(priorVersion)) {
                throw new OptimisticLockException();
            }
            agentDao.update(agentRollupConfig);
        }
    }

    @Override
    public void deleteAgentRollupConfig(String agentRollupId) throws Exception {
        synchronized (agentConfigLock) {
            agentDao.delete(agentRollupId);
        }
    }

    @Override
    public void insertUserConfig(UserConfig userConfig) throws Exception {
        synchronized (userConfigLock) {
            // check for case-insensitive duplicate
            String username = userConfig.username();
            for (UserConfig loopUserConfig : userDao.read()) {
                if (loopUserConfig.username().equalsIgnoreCase(username)) {
                    throw new DuplicateUsernameException();
                }
            }
            userDao.insert(userConfig);
        }
    }

    @Override
    public void updateUserConfig(UserConfig userConfig, String priorVersion) throws Exception {
        synchronized (userConfigLock) {
            UserConfig existingConfig = userDao.read(userConfig.username());
            if (existingConfig == null || !existingConfig.version().equals(priorVersion)) {
                throw new OptimisticLockException();
            }
            userDao.insert(userConfig);
        }
    }

    @Override
    public void deleteUserConfig(String username) throws Exception {
        synchronized (userConfigLock) {
            boolean found = false;
            List<UserConfig> userConfigs = userDao.read();
            for (UserConfig loopUserConfig : userConfigs) {
                if (loopUserConfig.username().equalsIgnoreCase(username)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new UserNotFoundException();
            }
            if (getSmtpConfig().host().isEmpty() && userConfigs.size() == 1) {
                throw new CannotDeleteLastUserException();
            }
            userDao.delete(username);
        }
    }

    @Override
    public void insertRoleConfig(RoleConfig roleConfig) throws Exception {
        synchronized (roleConfigLock) {
            // check for case-insensitive duplicate
            String name = roleConfig.name();
            for (RoleConfig loopRoleConfig : roleDao.read()) {
                if (loopRoleConfig.name().equalsIgnoreCase(name)) {
                    throw new DuplicateRoleNameException();
                }
            }
            roleDao.insert(roleConfig);
        }
    }

    @Override
    public void updateRoleConfig(RoleConfig roleConfig, String priorVersion) throws Exception {
        synchronized (roleConfigLock) {
            boolean found = false;
            for (RoleConfig loopRoleConfig : roleDao.read()) {
                if (loopRoleConfig.version().equals(priorVersion)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new OptimisticLockException();
            }
            roleDao.insert(roleConfig);
        }
    }

    @Override
    public void deleteRoleConfig(String name) throws Exception {
        synchronized (roleConfigLock) {
            boolean found = false;
            List<RoleConfig> roleConfigs = roleDao.read();
            for (RoleConfig loopRoleConfig : roleConfigs) {
                if (loopRoleConfig.name().equalsIgnoreCase(name)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new RoleNotFoundException();
            }
            if (roleConfigs.size() == 1) {
                throw new CannotDeleteLastRoleException();
            }
            roleDao.delete(name);
        }
    }

    @Override
    public void updateWebConfig(WebConfig userInterfaceConfig,
            String priorVersion) throws Exception {
        synchronized (webConfigLock) {
            if (!getWebConfig().version().equals(priorVersion)) {
                throw new OptimisticLockException();
            }
            centralConfigDao.write(WEB_KEY, userInterfaceConfig);
        }
    }

    @Override
    public void updateFatStorageConfig(FatStorageConfig storageConfig, String priorVersion) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateCentralStorageConfig(CentralStorageConfig storageConfig, String priorVersion)
            throws Exception {
        synchronized (storageConfigLock) {
            if (!getCentralStorageConfig().version().equals(priorVersion)) {
                throw new OptimisticLockException();
            }
            centralConfigDao.write(STORAGE_KEY, storageConfig);
        }
    }

    @Override
    public void updateSmtpConfig(SmtpConfig smtpConfig, String priorVersion) throws Exception {
        synchronized (smtpConfigLock) {
            if (!getSmtpConfig().version().equals(priorVersion)) {
                throw new OptimisticLockException();
            }
            centralConfigDao.write(SMTP_KEY, smtpConfig);
        }
    }

    @Override
    public void updateLdapConfig(LdapConfig smtpConfig, String priorVersion) throws Exception {
        synchronized (ldapConfigLock) {
            if (!getLdapConfig().version().equals(priorVersion)) {
                throw new OptimisticLockException();
            }
            centralConfigDao.write(LDAP_KEY, smtpConfig);
        }
    }

    @Override
    public StorageConfig getStorageConfig() {
        return getCentralStorageConfig();
    }

    @Override
    public long getGaugeCollectionIntervalMillis() {
        return GAUGE_COLLECTION_INTERVAL_MILLIS;
    }

    @Override
    public ImmutableList<RollupConfig> getRollupConfigs() {
        return rollupConfigs;
    }

    // lazy create secret file only when needed
    @Override
    public SecretKey getSecretKey() throws Exception {
        return secretKey.get();
    }

    public @Nullable AgentConfig getAgentConfig(String agentId)
            throws InvalidProtocolBufferException {
        return agentDao.readAgentConfig(agentId);
    }

    public void addConfigListener(ConfigListener listener) {
        configListeners.add(listener);
    }

    // the updated config is not passed to the listeners to avoid the race condition of multiple
    // config updates being sent out of order, instead listeners must call get*Config() which will
    // never return the updates out of order (at worst it may return the most recent update twice
    // which is ok)
    private void notifyConfigListeners(String agentId) throws Exception {
        for (ConfigListener configListener : configListeners) {
            configListener.onChange(agentId);
        }
    }

    private PluginConfig buildPluginConfig(PluginConfig existingPluginConfig,
            List<PluginProperty> properties) {
        // TODO report checker framework issue that occurs without this suppression
        @SuppressWarnings("methodref.receiver.invalid")
        Map<String, PluginProperty> props =
                Maps.newHashMap(Maps.uniqueIndex(properties, PluginProperty::getName));
        PluginConfig.Builder builder = PluginConfig.newBuilder()
                .setId(existingPluginConfig.getId())
                .setName(existingPluginConfig.getName());
        for (PluginProperty existingProperty : existingPluginConfig.getPropertyList()) {
            PluginProperty prop = props.remove(existingProperty.getName());
            if (prop == null) {
                throw new IllegalStateException(
                        "Missing plugin property name: " + existingProperty.getName());
            }
            if (!isSameType(prop.getValue(), existingProperty.getValue())) {
                throw new IllegalStateException("Plugin property " + prop.getName()
                        + " has incorrect type: " + prop.getValue().getValCase());
            }
            builder.addProperty(existingProperty.toBuilder()
                    .setValue(prop.getValue()));
        }
        if (!props.isEmpty()) {
            throw new IllegalStateException(
                    "Unexpected property name(s): " + Joiner.on(", ").join(props.keySet()));
        }
        return builder.build();
    }

    private boolean isSameType(PluginProperty.Value left, PluginProperty.Value right) {
        if (left.getValCase() == ValCase.DVAL && right.getValCase() == ValCase.DVAL_NULL) {
            return true;
        }
        if (left.getValCase() == ValCase.DVAL_NULL && right.getValCase() == ValCase.DVAL) {
            return true;
        }
        return left.getValCase() == right.getValCase();
    }

    private void checkGaugeDoesNotExist(GaugeConfig instrumentationConfig,
            List<GaugeConfig> instrumentationConfigs) {
        String version = Versions.getVersion(instrumentationConfig);
        for (GaugeConfig config : instrumentationConfigs) {
            if (Versions.getVersion(config).equals(version)) {
                throw new IllegalStateException("This exact gauge already exists");
            }
        }
    }

    private void checkAlertDoesNotExist(AlertConfig instrumentationConfig,
            List<AlertConfig> instrumentationConfigs) {
        String version = Versions.getVersion(instrumentationConfig);
        for (AlertConfig config : instrumentationConfigs) {
            if (Versions.getVersion(config).equals(version)) {
                throw new IllegalStateException("This exact alert already exists");
            }
        }
    }

    private void checkInstrumentationDoesNotExist(InstrumentationConfig instrumentationConfig,
            List<InstrumentationConfig> instrumentationConfigs) {
        String version = Versions.getVersion(instrumentationConfig);
        for (InstrumentationConfig config : instrumentationConfigs) {
            if (Versions.getVersion(config).equals(version)) {
                throw new IllegalStateException("This exact instrumentation already exists");
            }
        }
    }

    private static CentralStorageConfig withCorrectedLists(CentralStorageConfig storageConfig) {
        FatStorageConfig defaultConfig = ImmutableFatStorageConfig.builder().build();
        ImmutableList<Integer> rollupExpirationHours =
                fix(storageConfig.rollupExpirationHours(), defaultConfig.rollupExpirationHours());
        return ImmutableCentralStorageConfig.builder()
                .copyFrom(storageConfig)
                .rollupExpirationHours(rollupExpirationHours)
                .build();
    }

    private static ImmutableList<Integer> fix(ImmutableList<Integer> thisList,
            List<Integer> defaultList) {
        if (thisList.size() >= defaultList.size()) {
            return thisList.subList(0, defaultList.size());
        }
        List<Integer> correctedList = Lists.newArrayList(thisList);
        for (int i = thisList.size(); i < defaultList.size(); i++) {
            correctedList.add(defaultList.get(i));
        }
        return ImmutableList.copyOf(correctedList);
    }

    public interface ConfigListener {

        // the new config is not passed to onChange so that the receiver has to get the latest,
        // this avoids race condition worries that two updates may get sent to the receiver in the
        // wrong order
        void onChange(String agentId) throws Exception;
    }
}
