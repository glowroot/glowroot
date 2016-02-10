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
package org.glowroot.central.storage;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import javax.annotation.Nullable;
import javax.crypto.SecretKey;

import com.fasterxml.jackson.core.JsonProcessingException;
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

import org.glowroot.central.DownstreamServiceImpl;
import org.glowroot.common.live.LiveJvmService.AgentNotConnectedException;
import org.glowroot.common.util.Versions;
import org.glowroot.storage.config.AlertConfig;
import org.glowroot.storage.config.ImmutableSmtpConfig;
import org.glowroot.storage.config.ImmutableStorageConfig;
import org.glowroot.storage.config.ImmutableUserInterfaceConfig;
import org.glowroot.storage.config.SmtpConfig;
import org.glowroot.storage.config.StorageConfig;
import org.glowroot.storage.config.UserInterfaceConfig;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.util.Encryption;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AdvancedConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.GaugeConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.InstrumentationConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginProperty;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginProperty.Value.ValCase;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.TransactionConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.UserRecordingConfig;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

public class ConfigRepositoryImpl implements ConfigRepository {

    // TODO this needs to be in sync with agents, so have agents pick up value from central
    private static final long GAUGE_COLLECTION_INTERVAL_MILLIS =
            Long.getLong("glowroot.internal.gaugeCollectionIntervalMillis", 5000);

    private static final Logger logger = LoggerFactory.getLogger(ConfigRepositoryImpl.class);

    private final ServerDao serverDao;
    private final CentralConfigDao centralConfigDao;
    private final AlertConfigDao alertConfigDao;

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
    private final Object userInterfaceConfigLock = new Object();
    private final Object smtpConfigLock = new Object();
    private final Object alertConfigLock = new Object();

    public ConfigRepositoryImpl(ServerDao serverDao, CentralConfigDao centralConfigDao,
            AlertConfigDao alertConfigDao) {
        this.serverDao = serverDao;
        this.centralConfigDao = centralConfigDao;
        this.alertConfigDao = alertConfigDao;
        rollupConfigs = ImmutableList.copyOf(RollupConfig.buildRollupConfigs());
        secretFile = new File("secret");
    }

    public void setDownstreamService(DownstreamServiceImpl downstreamService) {
        this.downstreamService = downstreamService;
    }

    @Override
    public UserInterfaceConfig getUserInterfaceConfig() {
        UserInterfaceConfig config =
                centralConfigDao.read(UI_KEY, ImmutableUserInterfaceConfig.class);
        if (config == null) {
            return ImmutableUserInterfaceConfig.builder().build();
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
    public List<AlertConfig> getAlertConfigs(String serverId) throws JsonProcessingException {
        List<AlertConfig> configs = alertConfigDao.readAlerts(serverId);
        if (configs == null) {
            return ImmutableList.of();
        }
        return ImmutableList.<AlertConfig>copyOf(configs);
    }

    @Override
    public @Nullable AlertConfig getAlertConfig(String serverId, String version)
            throws JsonProcessingException {
        for (AlertConfig alertConfig : getAlertConfigs(serverId)) {
            if (alertConfig.version().equals(version)) {
                return alertConfig;
            }
        }
        return null;
    }

    @Override
    public void updateUserInterfaceConfig(UserInterfaceConfig userInterfaceConfig,
            String priorVersion) throws Exception {
        synchronized (userInterfaceConfigLock) {
            if (!getUserInterfaceConfig().version().equals(priorVersion)) {
                throw new OptimisticLockException();
            }
            centralConfigDao.write(UI_KEY, userInterfaceConfig);
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
    public void insertAlertConfig(String serverId, AlertConfig alertConfig)
            throws JsonProcessingException {
        synchronized (alertConfigLock) {
            List<AlertConfig> alertConfigs = Lists.newArrayList(getAlertConfigs(serverId));
            checkAlertDoesNotExist(alertConfig, alertConfigs);
            alertConfigs.add(alertConfig);
            alertConfigDao.write(serverId, alertConfigs);
        }
    }

    @Override
    public void updateAlertConfig(String serverId, AlertConfig alertConfig, String priorVersion)
            throws JsonProcessingException {
        synchronized (alertConfigLock) {
            List<AlertConfig> configs = Lists.newArrayList(getAlertConfigs(serverId));
            boolean found = false;
            for (ListIterator<AlertConfig> i = configs.listIterator(); i.hasNext();) {
                if (priorVersion.equals(i.next().version())) {
                    i.set(alertConfig);
                    found = true;
                    break;
                }
            }
            checkState(found, "Alert config not found: %s", priorVersion);
            alertConfigDao.write(serverId, configs);
        }
    }

    @Override
    public void deleteAlertConfig(String serverId, String version) throws JsonProcessingException {
        synchronized (alertConfigLock) {
            List<AlertConfig> configs = Lists.newArrayList(getAlertConfigs(serverId));
            boolean found = false;
            for (ListIterator<AlertConfig> i = configs.listIterator(); i.hasNext();) {
                if (version.equals(i.next().version())) {
                    i.remove();
                    found = true;
                    break;
                }
            }
            checkState(found, "Alert config not found: %s", version);
            alertConfigDao.write(serverId, configs);
        }
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

    @Override
    public TransactionConfig getTransactionConfig(String serverId)
            throws InvalidProtocolBufferException {
        AgentConfig agentConfig = serverDao.readAgentConfig(serverId);
        if (agentConfig == null) {
            throw new IllegalStateException("Agent config not found");
        }
        return agentConfig.getTransactionConfig();
    }

    @Override
    public UserRecordingConfig getUserRecordingConfig(String serverId) throws IOException {
        AgentConfig agentConfig = serverDao.readAgentConfig(serverId);
        if (agentConfig == null) {
            throw new IllegalStateException("Agent config not found");
        }
        return agentConfig.getUserRecordingConfig();
    }

    @Override
    public AdvancedConfig getAdvancedConfig(String serverId) throws IOException {
        AgentConfig agentConfig = serverDao.readAgentConfig(serverId);
        if (agentConfig == null) {
            throw new IllegalStateException("Agent config not found");
        }
        return agentConfig.getAdvancedConfig();
    }

    @Override
    public List<PluginConfig> getPluginConfigs(String serverId)
            throws InvalidProtocolBufferException {
        AgentConfig agentConfig = serverDao.readAgentConfig(serverId);
        if (agentConfig == null) {
            throw new IllegalStateException("Agent config not found");
        }
        return agentConfig.getPluginConfigList();
    }

    @Override
    public PluginConfig getPluginConfig(String serverId, String pluginId)
            throws InvalidProtocolBufferException {
        for (PluginConfig pluginConfig : getPluginConfigs(serverId)) {
            if (pluginConfig.getId().equals(pluginId)) {
                return pluginConfig;
            }
        }
        throw new IllegalStateException("Plugin config not found: " + pluginId);
    }

    @Override
    public List<GaugeConfig> getGaugeConfigs(String serverId)
            throws InvalidProtocolBufferException {
        AgentConfig agentConfig = serverDao.readAgentConfig(serverId);
        if (agentConfig == null) {
            throw new IllegalStateException("Agent config not found");
        }
        return agentConfig.getGaugeConfigList();
    }

    @Override
    public GaugeConfig getGaugeConfig(String serverId, String version)
            throws InvalidProtocolBufferException {
        for (GaugeConfig gaugeConfig : getGaugeConfigs(serverId)) {
            if (Versions.getVersion(gaugeConfig).equals(version)) {
                return gaugeConfig;
            }
        }
        throw new IllegalStateException("Gauge config not found: " + version);
    }

    @Override
    public List<InstrumentationConfig> getInstrumentationConfigs(String serverId)
            throws InvalidProtocolBufferException {
        AgentConfig agentConfig = serverDao.readAgentConfig(serverId);
        if (agentConfig == null) {
            throw new IllegalStateException("Agent config not found");
        }
        return agentConfig.getInstrumentationConfigList();
    }

    @Override
    public InstrumentationConfig getInstrumentationConfig(String serverId, String version)
            throws IOException {
        for (InstrumentationConfig config : getAgentConfig(serverId)
                .getInstrumentationConfigList()) {
            if (Versions.getVersion(config).equals(version)) {
                return config;
            }
        }
        throw new IllegalStateException("Instrumentation config not found: " + version);
    }

    @Override
    public StorageConfig getStorageConfig() {
        // this is needed for access to StorageConfig.rollupExpirationHours()
        return ImmutableStorageConfig.builder().build();
    }

    public void updateAgentConfig(String serverId, AgentConfig agentConfig) throws IOException {
        synchronized (agentConfigLocks.getUnchecked(serverId)) {
            serverDao.storeAgentConfig(serverId, agentConfig);
        }
    }

    @Override
    public void updateTransactionConfig(String serverId, TransactionConfig transactionConfig,
            String priorVersion) throws Exception {
        synchronized (agentConfigLocks.getUnchecked(serverId)) {
            AgentConfig agentConfig = serverDao.readAgentConfig(serverId);
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
            serverDao.storeAgentConfig(serverId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            sendUpdatedAgentConfig(serverId, updatedAgentConfig);
        }
    }

    @Override
    public void updateUserRecordingConfig(String serverId, UserRecordingConfig userRecordingConfig,
            String priorVersion) throws Exception {
        synchronized (agentConfigLocks.getUnchecked(serverId)) {
            AgentConfig agentConfig = serverDao.readAgentConfig(serverId);
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
            serverDao.storeAgentConfig(serverId, agentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            sendUpdatedAgentConfig(serverId, updatedAgentConfig);
        }
    }

    @Override
    public void updateAdvancedConfig(String serverId, AdvancedConfig advancedConfig,
            String priorVersion) throws Exception {
        synchronized (agentConfigLocks.getUnchecked(serverId)) {
            AgentConfig agentConfig = serverDao.readAgentConfig(serverId);
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
            serverDao.storeAgentConfig(serverId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            sendUpdatedAgentConfig(serverId, updatedAgentConfig);
        }
    }

    @Override
    public void updatePluginConfig(String serverId, String pluginId,
            List<PluginProperty> properties, String priorVersion) throws Exception {
        synchronized (agentConfigLocks.getUnchecked(serverId)) {
            AgentConfig agentConfig = serverDao.readAgentConfig(serverId);
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
            serverDao.storeAgentConfig(serverId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            sendUpdatedAgentConfig(serverId, updatedAgentConfig);
        }
    }

    @Override
    public void insertGaugeConfig(String serverId, GaugeConfig gaugeConfig) throws Exception {
        synchronized (agentConfigLocks.getUnchecked(serverId)) {
            AgentConfig agentConfig = serverDao.readAgentConfig(serverId);
            if (agentConfig == null) {
                throw new IllegalStateException("Agent config not found");
            }
            // check for duplicate mbeanObjectName
            for (GaugeConfig loopConfig : agentConfig.getGaugeConfigList()) {
                if (loopConfig.getMbeanObjectName().equals(gaugeConfig.getMbeanObjectName())) {
                    throw new DuplicateMBeanObjectNameException();
                }
            }
            AgentConfig updatedAgentConfig = AgentConfig.newBuilder(agentConfig)
                    .addGaugeConfig(gaugeConfig)
                    .build();
            serverDao.storeAgentConfig(serverId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            sendUpdatedAgentConfig(serverId, updatedAgentConfig);
        }
    }

    @Override
    public void updateGaugeConfig(String serverId, GaugeConfig gaugeConfig, String priorVersion)
            throws Exception {
        synchronized (agentConfigLocks.getUnchecked(serverId)) {
            AgentConfig agentConfig = serverDao.readAgentConfig(serverId);
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
            serverDao.storeAgentConfig(serverId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            sendUpdatedAgentConfig(serverId, updatedAgentConfig);
        }
    }

    @Override
    public void deleteGaugeConfig(String serverId, String version) throws Exception {
        synchronized (agentConfigLocks.getUnchecked(serverId)) {
            AgentConfig agentConfig = serverDao.readAgentConfig(serverId);
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
            serverDao.storeAgentConfig(serverId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            sendUpdatedAgentConfig(serverId, updatedAgentConfig);
        }
    }

    @Override
    public void insertInstrumentationConfig(String serverId,
            InstrumentationConfig instrumentationConfig) throws Exception {
        synchronized (agentConfigLocks.getUnchecked(serverId)) {
            AgentConfig agentConfig = serverDao.readAgentConfig(serverId);
            if (agentConfig == null) {
                throw new IllegalStateException("Agent config not found");
            }
            checkInstrumentationDoesNotExist(instrumentationConfig, agentConfig);
            AgentConfig updatedAgentConfig = AgentConfig.newBuilder(agentConfig)
                    .addInstrumentationConfig(instrumentationConfig)
                    .build();
            serverDao.storeAgentConfig(serverId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            sendUpdatedAgentConfig(serverId, updatedAgentConfig);
        }
    }

    @Override
    public void updateInstrumentationConfig(String serverId,
            InstrumentationConfig instrumentationConfig, String priorVersion) throws Exception {
        synchronized (agentConfigLocks.getUnchecked(serverId)) {
            AgentConfig agentConfig = serverDao.readAgentConfig(serverId);
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
            serverDao.storeAgentConfig(serverId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            sendUpdatedAgentConfig(serverId, updatedAgentConfig);
        }
    }

    @Override
    public void deleteInstrumentationConfig(String serverId, String version) throws Exception {
        synchronized (agentConfigLocks.getUnchecked(serverId)) {
            AgentConfig agentConfig = serverDao.readAgentConfig(serverId);
            if (agentConfig == null) {
                throw new IllegalStateException("Agent config not found");
            }
            List<InstrumentationConfig> existingInstrumentationConfigs =
                    Lists.newArrayList(agentConfig.getInstrumentationConfigList());
            ListIterator<InstrumentationConfig> i = existingInstrumentationConfigs.listIterator();
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
                    .clearInstrumentationConfig()
                    .addAllInstrumentationConfig(existingInstrumentationConfigs)
                    .build();
            serverDao.storeAgentConfig(serverId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            sendUpdatedAgentConfig(serverId, updatedAgentConfig);
        }
    }

    @Override
    public void updateStorageConfig(StorageConfig storageConfig, String priorVersion) {
        throw new UnsupportedOperationException();
    }

    public AgentConfig getAgentConfig(String serverId) throws IOException {
        return AgentConfig.newBuilder()
                .setTransactionConfig(getTransactionConfig(serverId))
                .setUserRecordingConfig(getUserRecordingConfig(serverId))
                .setAdvancedConfig(getAdvancedConfig(serverId))
                .addAllGaugeConfig(getGaugeConfigs(serverId))
                .addAllInstrumentationConfig(getInstrumentationConfigs(serverId))
                .build();
    }

    private void sendUpdatedAgentConfig(String serverId, AgentConfig agentConfig) throws Exception {
        checkNotNull(downstreamService);
        try {
            downstreamService.updateAgentConfig(serverId, agentConfig);
        } catch (AgentNotConnectedException e) {
            logger.debug(e.getMessage(), e);
        }
    }

    private PluginConfig buildPluginConfig(PluginConfig existingPluginConfig,
            List<PluginProperty> properties) {
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

    private void checkInstrumentationDoesNotExist(InstrumentationConfig instrumentationConfig,
            AgentConfig agentConfig) {
        String version = Versions.getVersion(instrumentationConfig);
        for (InstrumentationConfig existingInstrumentationConfig : agentConfig
                .getInstrumentationConfigList()) {
            if (Versions.getVersion(existingInstrumentationConfig).equals(version)) {
                throw new IllegalStateException("This exact instrumentation already exists");
            }
        }
    }

    private void checkAlertDoesNotExist(AlertConfig alertConfig, List<AlertConfig> agentConfigs) {
        String version = Versions.getJsonVersion(alertConfig);
        for (AlertConfig existingAlertConfig : agentConfigs) {
            if (Versions.getJsonVersion(existingAlertConfig).equals(version)) {
                throw new IllegalStateException("This exact alert already exists");
            }
        }
    }
}
