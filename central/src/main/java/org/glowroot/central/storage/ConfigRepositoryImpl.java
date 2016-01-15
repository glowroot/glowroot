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

import java.io.IOException;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import javax.annotation.Nullable;
import javax.crypto.SecretKey;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.protobuf.InvalidProtocolBufferException;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.central.DownstreamServiceImpl;
import org.glowroot.central.DownstreamServiceImpl.AgentNotConnectedException;
import org.glowroot.common.util.Versions;
import org.glowroot.storage.config.AlertConfig;
import org.glowroot.storage.config.ImmutableAlertConfig;
import org.glowroot.storage.config.ImmutableSmtpConfig;
import org.glowroot.storage.config.ImmutableStorageConfig;
import org.glowroot.storage.config.ImmutableUserInterfaceConfig;
import org.glowroot.storage.config.SmtpConfig;
import org.glowroot.storage.config.StorageConfig;
import org.glowroot.storage.config.UserInterfaceConfig;
import org.glowroot.storage.repo.ConfigRepository;
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

    private static final ObjectMapper mapper = new ObjectMapper();

    private final ServerDao serverDao;
    private final CentralConfigDao centralConfigDao;

    private final ImmutableList<RollupConfig> rollupConfigs;

    private volatile @MonotonicNonNull DownstreamServiceImpl downstreamService;

    public ConfigRepositoryImpl(ServerDao serverDao, CentralConfigDao centralConfigDao) {
        this.serverDao = serverDao;
        this.centralConfigDao = centralConfigDao;
        rollupConfigs = ImmutableList.copyOf(RollupConfig.buildRollupConfigs());
    }

    public void setDownstreamService(DownstreamServiceImpl downstreamService) {
        this.downstreamService = downstreamService;
    }

    @Override
    public UserInterfaceConfig getUserInterfaceConfig() {
        UserInterfaceConfig config =
                centralConfigDao.read(UI_KEY, ImmutableUserInterfaceConfig.class, mapper);
        if (config == null) {
            return ImmutableUserInterfaceConfig.builder().build();
        }
        return config;
    }

    @Override
    public SmtpConfig getSmtpConfig() {
        SmtpConfig config = centralConfigDao.read(SMTP_KEY, ImmutableSmtpConfig.class, mapper);
        if (config == null) {
            return ImmutableSmtpConfig.builder().build();
        }
        return config;
    }

    @Override
    public List<AlertConfig> getAlertConfigs(String serverRollup) throws JsonProcessingException {
        List<ImmutableAlertConfig> configs = centralConfigDao.read(ALERTS_KEY,
                new TypeReference<List<ImmutableAlertConfig>>() {}, mapper);
        if (configs == null) {
            return ImmutableList.of();
        }
        return ImmutableList.<AlertConfig>copyOf(configs);
    }

    @Override
    public @Nullable AlertConfig getAlertConfig(String serverRollup, String version)
            throws JsonProcessingException {
        for (AlertConfig alertConfig : getAlertConfigs(serverRollup)) {
            if (alertConfig.version().equals(version)) {
                return alertConfig;
            }
        }
        return null;
    }

    @Override
    public void updateUserInterfaceConfig(UserInterfaceConfig userInterfaceConfig,
            String priorVersion) throws Exception {
        if (!getUserInterfaceConfig().version().equals(priorVersion)) {
            throw new OptimisticLockException();
        }
        centralConfigDao.write(UI_KEY, userInterfaceConfig, mapper);
    }

    @Override
    public void updateSmtpConfig(SmtpConfig smtpConfig, String priorVersion) throws Exception {
        if (!getSmtpConfig().version().equals(priorVersion)) {
            throw new OptimisticLockException();
        }
        centralConfigDao.write(SMTP_KEY, smtpConfig, mapper);
    }

    @Override
    public void insertAlertConfig(String serverRollup, AlertConfig alertConfig)
            throws JsonProcessingException {
        List<AlertConfig> configs = Lists.newArrayList(getAlertConfigs(serverRollup));
        configs.add(alertConfig);
        centralConfigDao.write(ALERTS_KEY, configs, mapper);
    }

    @Override
    public void updateAlertConfig(String serverRollup, AlertConfig alertConfig, String priorVersion)
            throws JsonProcessingException {
        List<AlertConfig> configs = Lists.newArrayList(getAlertConfigs(serverRollup));
        boolean found = false;
        for (ListIterator<AlertConfig> i = configs.listIterator(); i.hasNext();) {
            if (priorVersion.equals(i.next().version())) {
                i.set(alertConfig);
                found = true;
                break;
            }
        }
        checkState(found, "Alert config not found: %s", priorVersion);
        centralConfigDao.write(ALERTS_KEY, configs, mapper);
    }

    @Override
    public void deleteAlertConfig(String serverRollup, String version)
            throws JsonProcessingException {
        List<AlertConfig> configs = Lists.newArrayList(getAlertConfigs(serverRollup));
        boolean found = false;
        for (ListIterator<AlertConfig> i = configs.listIterator(); i.hasNext();) {
            if (version.equals(i.next().version())) {
                i.remove();
                found = true;
                break;
            }
        }
        checkState(found, "Alert config not found: %s", version);
        centralConfigDao.write(ALERTS_KEY, configs, mapper);
    }

    @Override
    public long getGaugeCollectionIntervalMillis() {
        return GAUGE_COLLECTION_INTERVAL_MILLIS;
    }

    @Override
    public ImmutableList<RollupConfig> getRollupConfigs() {
        return rollupConfigs;
    }

    @Override
    public SecretKey getSecretKey() {
        throw new UnsupportedOperationException();
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
    public InstrumentationConfig getInstrumentationConfig(String serverId, String version) {
        throw new UnsupportedOperationException();
    }

    @Override
    public StorageConfig getStorageConfig() {
        // this is needed for access to StorageConfig.rollupExpirationHours()
        return ImmutableStorageConfig.builder().build();
    }

    public void updateAgentConfig(String serverId, AgentConfig agentConfig) throws IOException {
        serverDao.storeAgentConfig(serverId, agentConfig);
    }

    @Override
    public void updateTransactionConfig(String serverId, TransactionConfig transactionConfig,
            String priorVersion) throws Exception {
        AgentConfig updatedAgentConfig;
        // TODO smaller scope on synchronized block
        synchronized (this) {
            AgentConfig agentConfig = serverDao.readAgentConfig(serverId);
            if (agentConfig == null) {
                throw new IllegalStateException("Agent config not found");
            }
            String existingVersion = Versions.getVersion(agentConfig.getTransactionConfig());
            if (!priorVersion.equals(existingVersion)) {
                throw new OptimisticLockException();
            }
            updatedAgentConfig = AgentConfig.newBuilder(agentConfig)
                    .setTransactionConfig(transactionConfig)
                    .build();
            serverDao.storeAgentConfig(serverId, updatedAgentConfig);
        }
        sendUpdatedAgentConfig(serverId, updatedAgentConfig);
    }

    @Override
    public void updateUserRecordingConfig(String serverId, UserRecordingConfig userRecordingConfig,
            String priorVersion) throws Exception {
        AgentConfig updatedAgentConfig;
        // TODO smaller scope on synchronized block
        synchronized (this) {
            AgentConfig agentConfig = serverDao.readAgentConfig(serverId);
            if (agentConfig == null) {
                throw new IllegalStateException("Agent config not found");
            }
            String existingVersion = Versions.getVersion(agentConfig.getUserRecordingConfig());
            if (!priorVersion.equals(existingVersion)) {
                throw new OptimisticLockException();
            }
            updatedAgentConfig = AgentConfig.newBuilder(agentConfig)
                    .setUserRecordingConfig(userRecordingConfig)
                    .build();
            serverDao.storeAgentConfig(serverId, agentConfig);
        }
        sendUpdatedAgentConfig(serverId, updatedAgentConfig);
    }

    @Override
    public void updateAdvancedConfig(String serverId, AdvancedConfig advancedConfig,
            String priorVersion) throws Exception {
        AgentConfig updatedAgentConfig;
        // TODO smaller scope on synchronized block
        synchronized (this) {
            AgentConfig agentConfig = serverDao.readAgentConfig(serverId);
            if (agentConfig == null) {
                throw new IllegalStateException("Agent config not found");
            }
            String existingVersion = Versions.getVersion(agentConfig.getAdvancedConfig());
            if (!priorVersion.equals(existingVersion)) {
                throw new OptimisticLockException();
            }
            updatedAgentConfig = AgentConfig.newBuilder(agentConfig)
                    .setAdvancedConfig(advancedConfig)
                    .build();
            serverDao.storeAgentConfig(serverId, updatedAgentConfig);
        }
        sendUpdatedAgentConfig(serverId, updatedAgentConfig);
    }

    @Override
    public void updatePluginConfig(String serverId, String pluginId,
            List<PluginProperty> properties, String priorVersion) throws Exception {
        AgentConfig updatedAgentConfig;
        // TODO smaller scope on synchronized block
        synchronized (this) {
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
                }
            }
            if (!found) {
                throw new IllegalStateException("Plugin config not found: " + pluginId);
            }
            updatedAgentConfig = AgentConfig.newBuilder(agentConfig)
                    .clearPluginConfig()
                    .addAllPluginConfig(pluginConfigs)
                    .build();
            serverDao.storeAgentConfig(serverId, updatedAgentConfig);
        }
        sendUpdatedAgentConfig(serverId, updatedAgentConfig);
    }

    @Override
    public void insertInstrumentationConfig(String serverId,
            InstrumentationConfig instrumentationConfig) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateInstrumentationConfig(String serverId,
            InstrumentationConfig instrumentationConfig, String priorVersion) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteInstrumentationConfig(String serverId, String version) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void insertGaugeConfig(String serverId, GaugeConfig gaugeConfig) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateGaugeConfig(String serverId, GaugeConfig gaugeConfig, String priorVersion) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteGaugeConfig(String serverId, String version) {
        throw new UnsupportedOperationException();
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
}
