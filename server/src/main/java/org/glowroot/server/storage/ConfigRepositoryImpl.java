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
package org.glowroot.server.storage;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import javax.annotation.Nullable;
import javax.crypto.SecretKey;

import com.google.common.base.Joiner;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import com.google.protobuf.InvalidProtocolBufferException;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.live.LiveJvmService.AgentNotConnectedException;
import org.glowroot.common.util.Versions;
import org.glowroot.server.DownstreamServiceImpl;
import org.glowroot.storage.config.AccessConfig;
import org.glowroot.storage.config.FatStorageConfig;
import org.glowroot.storage.config.ImmutableAccessConfig;
import org.glowroot.storage.config.ImmutableFatStorageConfig;
import org.glowroot.storage.config.ImmutableServerStorageConfig;
import org.glowroot.storage.config.ImmutableSmtpConfig;
import org.glowroot.storage.config.ServerStorageConfig;
import org.glowroot.storage.config.SmtpConfig;
import org.glowroot.storage.config.StorageConfig;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.util.Encryption;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AdvancedConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.GaugeConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.InstrumentationConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginProperty;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginProperty.Value.ValCase;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.TransactionConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.UiConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.UserRecordingConfig;

import static com.google.common.base.Preconditions.checkNotNull;

public class ConfigRepositoryImpl implements ConfigRepository {

    // TODO this needs to be in sync with agents, so have agents pick up value from server
    private static final long GAUGE_COLLECTION_INTERVAL_MILLIS =
            Long.getLong("glowroot.internal.gaugeCollectionIntervalMillis", 5000);

    private static final Logger logger = LoggerFactory.getLogger(ConfigRepositoryImpl.class);

    private final ServerConfigDao serverConfigDao;
    private final AgentDao agentDao;

    private final ImmutableList<RollupConfig> rollupConfigs;

    private final File secretFile;

    private volatile @MonotonicNonNull DownstreamServiceImpl downstreamService;

    // volatile not needed as access is guarded by secretFile
    private @MonotonicNonNull SecretKey secretKey;

    // TODO use optimistic locking with retry instead of synchronization in order to work across
    // cluster
    private final LoadingCache<String, Object> agentConfigLocks =
            CacheBuilder.newBuilder().weakValues().build(new CacheLoader<String, Object>() {
                @Override
                public Object load(String key) throws Exception {
                    return new Object();
                }
            });
    private final Object accessConfigLock = new Object();
    private final Object storageConfigLock = new Object();
    private final Object smtpConfigLock = new Object();

    public ConfigRepositoryImpl(ServerConfigDao serverConfigDao, AgentDao agentDao) {
        this.serverConfigDao = serverConfigDao;
        this.agentDao = agentDao;
        rollupConfigs = ImmutableList.copyOf(RollupConfig.buildRollupConfigs());
        secretFile = new File("secret");
    }

    public void setDownstreamService(DownstreamServiceImpl downstreamService) {
        this.downstreamService = downstreamService;
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
    public AccessConfig getAccessConfig() {
        AccessConfig config = serverConfigDao.read(ACCESS_KEY, ImmutableAccessConfig.class);
        if (config == null) {
            return ImmutableAccessConfig.builder().build();
        }
        return config;
    }

    @Override
    public ServerStorageConfig getServerStorageConfig() {
        ServerStorageConfig config =
                serverConfigDao.read(STORAGE_KEY, ImmutableServerStorageConfig.class);
        if (config == null) {
            return ImmutableServerStorageConfig.builder().build();
        }
        if (config.hasListIssues()) {
            return withCorrectedLists(config);
        }
        return config;
    }

    @Override
    public FatStorageConfig getFatStorageConfig() {
        throw new UnsupportedOperationException();
    }

    @Override
    public SmtpConfig getSmtpConfig() {
        SmtpConfig config = serverConfigDao.read(SMTP_KEY, ImmutableSmtpConfig.class);
        if (config == null) {
            return ImmutableSmtpConfig.builder().build();
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
            AgentConfig updatedAgentConfig = AgentConfig.newBuilder(agentConfig)
                    .setTransactionConfig(transactionConfig)
                    .build();
            agentDao.storeAgentConfig(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            sendUpdatedAgentConfig(agentId, updatedAgentConfig);
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
            AgentConfig updatedAgentConfig = AgentConfig.newBuilder(agentConfig)
                    .addGaugeConfig(gaugeConfig)
                    .build();
            agentDao.storeAgentConfig(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            sendUpdatedAgentConfig(agentId, updatedAgentConfig);
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
            AgentConfig updatedAgentConfig = AgentConfig.newBuilder(agentConfig)
                    .clearGaugeConfig()
                    .addAllGaugeConfig(existingGaugeConfigs)
                    .build();
            agentDao.storeAgentConfig(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            sendUpdatedAgentConfig(agentId, updatedAgentConfig);
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
            AgentConfig updatedAgentConfig = AgentConfig.newBuilder(agentConfig)
                    .clearGaugeConfig()
                    .addAllGaugeConfig(existingGaugeConfigs)
                    .build();
            agentDao.storeAgentConfig(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            sendUpdatedAgentConfig(agentId, updatedAgentConfig);
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
            AgentConfig updatedAgentConfig = AgentConfig.newBuilder(agentConfig)
                    .addAlertConfig(alertConfig)
                    .build();
            agentDao.storeAgentConfig(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            sendUpdatedAgentConfig(agentId, updatedAgentConfig);
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
            AgentConfig updatedAgentConfig = AgentConfig.newBuilder(agentConfig)
                    .clearAlertConfig()
                    .addAllAlertConfig(existingAlertConfigs)
                    .build();
            agentDao.storeAgentConfig(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            sendUpdatedAgentConfig(agentId, updatedAgentConfig);
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
            AgentConfig updatedAgentConfig = AgentConfig.newBuilder(agentConfig)
                    .clearAlertConfig()
                    .addAllAlertConfig(existingAlertConfigs)
                    .build();
            agentDao.storeAgentConfig(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            sendUpdatedAgentConfig(agentId, updatedAgentConfig);
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
            AgentConfig updatedAgentConfig = AgentConfig.newBuilder(agentConfig)
                    .setUiConfig(uiConfig)
                    .build();
            agentDao.storeAgentConfig(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            sendUpdatedAgentConfig(agentId, updatedAgentConfig);
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
            AgentConfig updatedAgentConfig = AgentConfig.newBuilder(agentConfig)
                    .clearPluginConfig()
                    .addAllPluginConfig(pluginConfigs)
                    .build();
            agentDao.storeAgentConfig(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            sendUpdatedAgentConfig(agentId, updatedAgentConfig);
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
            AgentConfig updatedAgentConfig = AgentConfig.newBuilder(agentConfig)
                    .addInstrumentationConfig(instrumentationConfig)
                    .build();
            agentDao.storeAgentConfig(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            sendUpdatedAgentConfig(agentId, updatedAgentConfig);
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
            AgentConfig updatedAgentConfig = AgentConfig.newBuilder(agentConfig)
                    .clearInstrumentationConfig()
                    .addAllInstrumentationConfig(existingInstrumentationConfigs)
                    .build();
            agentDao.storeAgentConfig(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            sendUpdatedAgentConfig(agentId, updatedAgentConfig);
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
            AgentConfig updatedAgentConfig = AgentConfig.newBuilder(agentConfig)
                    .clearInstrumentationConfig()
                    .addAllInstrumentationConfig(existingInstrumentationConfigs)
                    .build();
            agentDao.storeAgentConfig(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            sendUpdatedAgentConfig(agentId, updatedAgentConfig);
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
            AgentConfig.Builder builder = AgentConfig.newBuilder(agentConfig);
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
            sendUpdatedAgentConfig(agentId, updatedAgentConfig);
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
            AgentConfig updatedAgentConfig = AgentConfig.newBuilder(agentConfig)
                    .setUserRecordingConfig(userRecordingConfig)
                    .build();
            agentDao.storeAgentConfig(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            sendUpdatedAgentConfig(agentId, updatedAgentConfig);
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
            AgentConfig updatedAgentConfig = AgentConfig.newBuilder(agentConfig)
                    .setAdvancedConfig(advancedConfig)
                    .build();
            agentDao.storeAgentConfig(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            sendUpdatedAgentConfig(agentId, updatedAgentConfig);
        }
    }

    @Override
    public void updateAccessConfig(AccessConfig userInterfaceConfig,
            String priorVersion) throws Exception {
        synchronized (accessConfigLock) {
            if (!getAccessConfig().version().equals(priorVersion)) {
                throw new OptimisticLockException();
            }
            serverConfigDao.write(ACCESS_KEY, userInterfaceConfig);
        }
    }

    @Override
    public void updateServerStorageConfig(ServerStorageConfig storageConfig, String priorVersion)
            throws Exception {
        synchronized (storageConfigLock) {
            if (!getServerStorageConfig().version().equals(priorVersion)) {
                throw new OptimisticLockException();
            }
            serverConfigDao.write(STORAGE_KEY, storageConfig);
        }
    }

    @Override
    public void updateFatStorageConfig(FatStorageConfig storageConfig, String priorVersion) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateSmtpConfig(SmtpConfig smtpConfig, String priorVersion) throws Exception {
        synchronized (smtpConfigLock) {
            if (!getSmtpConfig().version().equals(priorVersion)) {
                throw new OptimisticLockException();
            }
            serverConfigDao.write(SMTP_KEY, smtpConfig);
        }
    }

    @Override
    public StorageConfig getStorageConfig() {
        return getServerStorageConfig();
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
        synchronized (secretFile) {
            if (secretKey == null) {
                if (secretFile.exists()) {
                    secretKey = Encryption.loadKey(secretFile);
                } else {
                    secretKey = Encryption.generateNewKey();
                    Files.write(secretKey.getEncoded(), secretFile);
                }
            }
            return secretKey;
        }
    }

    public void updateAgentConfig(String agentId, AgentConfig agentConfig) throws IOException {
        synchronized (agentConfigLocks.getUnchecked(agentId)) {
            agentDao.storeAgentConfig(agentId, agentConfig);
        }
    }

    private void sendUpdatedAgentConfig(String agentId, AgentConfig agentConfig) throws Exception {
        checkNotNull(downstreamService);
        try {
            downstreamService.updateAgentConfig(agentId, agentConfig);
        } catch (AgentNotConnectedException e) {
            logger.debug(e.getMessage(), e);
        }
    }

    private PluginConfig buildPluginConfig(PluginConfig existingPluginConfig,
            List<PluginProperty> properties) {
        // TODO submit checker framework issue
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
            builder.addProperty(PluginProperty.newBuilder(existingProperty)
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

    private static ServerStorageConfig withCorrectedLists(ServerStorageConfig storageConfig) {
        FatStorageConfig defaultConfig = ImmutableFatStorageConfig.builder().build();
        ImmutableList<Integer> rollupExpirationHours =
                fix(storageConfig.rollupExpirationHours(), defaultConfig.rollupExpirationHours());
        return ImmutableServerStorageConfig.builder()
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
}
