/*
 * Copyright 2015-2017 the original author or authors.
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
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Random;
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
import com.google.common.io.BaseEncoding;

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

import static com.google.common.base.Preconditions.checkState;

public class ConfigRepositoryImpl implements ConfigRepository {

    // TODO this needs to be in sync with agents, so have agents pick up value from central
    private static final long GAUGE_COLLECTION_INTERVAL_MILLIS =
            Long.getLong("glowroot.internal.gaugeCollectionIntervalMillis", 5000);

    private static final Random random = new Random();

    private final AgentDao agentDao;
    private final ConfigDao configDao;
    private final CentralConfigDao centralConfigDao;
    private final UserDao userDao;
    private final RoleDao roleDao;

    private final ImmutableList<RollupConfig> rollupConfigs;

    private final LazySecretKey secretKey;

    private final Set<AgentConfigListener> agentConfigListeners = Sets.newCopyOnWriteArraySet();

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

    public ConfigRepositoryImpl(AgentDao agentDao, ConfigDao configDao,
            CentralConfigDao centralConfigDao, UserDao userDao, RoleDao roleDao) {
        this.agentDao = agentDao;
        this.configDao = configDao;
        this.centralConfigDao = centralConfigDao;
        this.userDao = userDao;
        this.roleDao = roleDao;
        rollupConfigs = ImmutableList.copyOf(RollupConfig.buildRollupConfigs());
        secretKey = new LazySecretKey(new File("secret"));
    }

    @Override
    public TransactionConfig getTransactionConfig(String agentId) throws Exception {
        AgentConfig agentConfig = configDao.read(agentId);
        if (agentConfig == null) {
            // for some reason received data from agent, but not initial agent config
            throw new AgentConfigNotFoundException();
        }
        return agentConfig.getTransactionConfig();
    }

    // central supports ui config on rollups
    @Override
    public UiConfig getUiConfig(String agentRollupId) throws Exception {
        AgentConfig agentConfig = configDao.read(agentRollupId);
        if (agentConfig == null) {
            // for some reason received data from agent, but not initial agent config
            throw new AgentConfigNotFoundException();
        }
        return agentConfig.getUiConfig();
    }

    @Override
    public UserRecordingConfig getUserRecordingConfig(String agentId) throws Exception {
        AgentConfig agentConfig = configDao.read(agentId);
        if (agentConfig == null) {
            // for some reason received data from agent, but not initial agent config
            throw new AgentConfigNotFoundException();
        }
        return agentConfig.getUserRecordingConfig();
    }

    // central supports advanced config on rollups
    // (maxAggregateQueriesPerType and maxAggregateServiceCallsPerType)
    @Override
    public AdvancedConfig getAdvancedConfig(String agentRollupId) throws Exception {
        AgentConfig agentConfig = configDao.read(agentRollupId);
        if (agentConfig == null) {
            // for some reason received data from agent, but not initial agent config
            throw new AgentConfigNotFoundException();
        }
        return agentConfig.getAdvancedConfig();
    }

    @Override
    public List<GaugeConfig> getGaugeConfigs(String agentId) throws Exception {
        AgentConfig agentConfig = configDao.read(agentId);
        if (agentConfig == null) {
            return ImmutableList.of();
        }
        return agentConfig.getGaugeConfigList();
    }

    @Override
    public GaugeConfig getGaugeConfig(String agentId, String version) throws Exception {
        for (GaugeConfig gaugeConfig : getGaugeConfigs(agentId)) {
            if (Versions.getVersion(gaugeConfig).equals(version)) {
                return gaugeConfig;
            }
        }
        throw new IllegalStateException("Gauge config not found: " + version);
    }

    // central supports alert configs on rollups
    @Override
    public List<AlertConfig> getAlertConfigs(String agentRollupId) throws Exception {
        AgentConfig agentConfig = configDao.read(agentRollupId);
        if (agentConfig == null) {
            return ImmutableList.of();
        }
        return agentConfig.getAlertConfigList();
    }

    // central supports alert configs on rollups
    public List<AlertConfig> getAlertConfigs(String agentRollupId, AlertKind alertKind)
            throws Exception {
        List<AlertConfig> configs = Lists.newArrayList();
        for (AlertConfig config : getAlertConfigs(agentRollupId)) {
            if (config.getKind() == alertKind) {
                configs.add(config);
            }
        }
        return configs;
    }

    // central supports alert configs on rollups
    @Override
    public @Nullable AlertConfig getAlertConfig(String agentRollupId, String alertConfigId)
            throws Exception {
        for (AlertConfig alertConfig : getAlertConfigs(agentRollupId)) {
            if (alertConfig.getId().equals(alertConfigId)) {
                return alertConfig;
            }
        }
        return null;
    }

    @Override
    public List<PluginConfig> getPluginConfigs(String agentId) throws Exception {
        AgentConfig agentConfig = configDao.read(agentId);
        if (agentConfig == null) {
            return ImmutableList.of();
        }
        return agentConfig.getPluginConfigList();
    }

    @Override
    public PluginConfig getPluginConfig(String agentId, String pluginId) throws Exception {
        for (PluginConfig pluginConfig : getPluginConfigs(agentId)) {
            if (pluginConfig.getId().equals(pluginId)) {
                return pluginConfig;
            }
        }
        throw new IllegalStateException("Plugin config not found: " + pluginId);
    }

    @Override
    public List<InstrumentationConfig> getInstrumentationConfigs(String agentId) throws Exception {
        AgentConfig agentConfig = configDao.read(agentId);
        if (agentConfig == null) {
            return ImmutableList.of();
        }
        return agentConfig.getInstrumentationConfigList();
    }

    @Override
    public InstrumentationConfig getInstrumentationConfig(String agentId, String version)
            throws Exception {
        for (InstrumentationConfig config : getInstrumentationConfigs(agentId)) {
            if (Versions.getVersion(config).equals(version)) {
                return config;
            }
        }
        throw new IllegalStateException("Instrumentation config not found: " + version);
    }

    @Override
    public @Nullable AgentRollupConfig getAgentRollupConfig(String agentRollupId)
            throws Exception {
        return agentDao.readAgentRollupConfig(agentRollupId);
    }

    @Override
    public List<UserConfig> getUserConfigs() {
        return userDao.read();
    }

    @Override
    public UserConfig getUserConfig(String username) throws Exception {
        UserConfig userConfig = userDao.read(username);
        if (userConfig == null) {
            throw new UserNotFoundException();
        }
        return userConfig;
    }

    @Override
    public @Nullable UserConfig getUserConfigCaseInsensitive(String username) throws Exception {
        return userDao.readCaseInsensitive(username);
    }

    @Override
    public boolean namedUsersExist() throws Exception {
        return userDao.namedUsersExist();
    }

    @Override
    public List<RoleConfig> getRoleConfigs() throws Exception {
        return roleDao.read();
    }

    @Override
    public @Nullable RoleConfig getRoleConfig(String name) throws Exception {
        return roleDao.read(name);
    }

    @Override
    public WebConfig getWebConfig() throws Exception {
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
    public CentralStorageConfig getCentralStorageConfig() throws Exception {
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
    public SmtpConfig getSmtpConfig() throws Exception {
        SmtpConfig config = centralConfigDao.read(SMTP_KEY, ImmutableSmtpConfig.class);
        if (config == null) {
            return ImmutableSmtpConfig.builder().build();
        }
        return config;
    }

    @Override
    public LdapConfig getLdapConfig() throws Exception {
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
            AgentConfig agentConfig = configDao.read(agentId);
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
            configDao.insert(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            notifyAgentConfigListeners(agentId);
        }
    }

    @Override
    public void insertGaugeConfig(String agentId, GaugeConfig gaugeConfig) throws Exception {
        synchronized (agentConfigLocks.getUnchecked(agentId)) {
            AgentConfig agentConfig = configDao.read(agentId);
            if (agentConfig == null) {
                throw new IllegalStateException("Agent config not found");
            }
            // check for duplicate mbeanObjectName
            for (GaugeConfig loopConfig : agentConfig.getGaugeConfigList()) {
                if (loopConfig.getMbeanObjectName().equals(gaugeConfig.getMbeanObjectName())) {
                    throw new DuplicateMBeanObjectNameException();
                }
            }
            // no need to check for exact match since redundant with dup mbean object name check
            AgentConfig updatedAgentConfig = agentConfig.toBuilder()
                    .addGaugeConfig(gaugeConfig)
                    .build();
            configDao.insert(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            notifyAgentConfigListeners(agentId);
        }
    }

    @Override
    public void updateGaugeConfig(String agentId, GaugeConfig gaugeConfig, String priorVersion)
            throws Exception {
        synchronized (agentConfigLocks.getUnchecked(agentId)) {
            AgentConfig agentConfig = configDao.read(agentId);
            if (agentConfig == null) {
                throw new IllegalStateException("Agent config not found");
            }
            List<GaugeConfig> existingGaugeConfigs =
                    Lists.newArrayList(agentConfig.getGaugeConfigList());
            ListIterator<GaugeConfig> i = existingGaugeConfigs.listIterator();
            boolean found = false;
            while (i.hasNext()) {
                GaugeConfig loopGaugeConfig = i.next();
                String loopVersion = Versions.getVersion(loopGaugeConfig);
                if (loopVersion.equals(priorVersion)) {
                    i.set(gaugeConfig);
                    found = true;
                } else if (loopGaugeConfig.getMbeanObjectName()
                        .equals(gaugeConfig.getMbeanObjectName())) {
                    throw new DuplicateMBeanObjectNameException();
                }
                // no need to check for exact match since redundant with dup mbean object name check
            }
            if (!found) {
                throw new OptimisticLockException();
            }
            AgentConfig updatedAgentConfig = agentConfig.toBuilder()
                    .clearGaugeConfig()
                    .addAllGaugeConfig(existingGaugeConfigs)
                    .build();
            configDao.insert(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            notifyAgentConfigListeners(agentId);
        }
    }

    @Override
    public void deleteGaugeConfig(String agentId, String version) throws Exception {
        synchronized (agentConfigLocks.getUnchecked(agentId)) {
            AgentConfig agentConfig = configDao.read(agentId);
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
            configDao.insert(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            notifyAgentConfigListeners(agentId);
        }
    }

    // central supports alert configs on rollups
    @Override
    public String insertAlertConfig(String agentRollupId, AlertConfig alertConfigWithoutId)
            throws Exception {
        checkState(alertConfigWithoutId.getId().isEmpty());
        AlertConfig alertConfig = alertConfigWithoutId.toBuilder()
                .setId(generateAlertConfigId())
                .build();
        synchronized (agentConfigLocks.getUnchecked(agentRollupId)) {
            AgentConfig agentConfig = configDao.read(agentRollupId);
            if (agentConfig == null) {
                throw new IllegalStateException("Agent config not found");
            }
            checkAlertDoesNotExist(alertConfig, agentConfig.getAlertConfigList());
            AgentConfig updatedAgentConfig = agentConfig.toBuilder()
                    .addAlertConfig(alertConfig)
                    .build();
            configDao.insert(agentRollupId, updatedAgentConfig);
            // updating the agent is inside synchronized block to ensure ordering of updates
            notifyAgentConfigListeners(agentRollupId);
        }
        return alertConfig.getId();
    }

    // central supports alert configs on rollups
    @Override
    public void updateAlertConfig(String agentRollupId, AlertConfig alertConfig,
            String priorVersion) throws Exception {
        synchronized (agentConfigLocks.getUnchecked(agentRollupId)) {
            AgentConfig agentConfig = configDao.read(agentRollupId);
            if (agentConfig == null) {
                throw new IllegalStateException("Agent config not found");
            }
            List<AlertConfig> existingAlertConfigs =
                    Lists.newArrayList(agentConfig.getAlertConfigList());
            ListIterator<AlertConfig> i = existingAlertConfigs.listIterator();
            boolean found = false;
            while (i.hasNext()) {
                AlertConfig loopAlertConfig = i.next();
                if (loopAlertConfig.getId().equals(alertConfig.getId())) {
                    if (!Versions.getVersion(loopAlertConfig).equals(priorVersion)) {
                        throw new OptimisticLockException();
                    }
                    i.set(alertConfig);
                    found = true;
                } else if (loopAlertConfig.equals(alertConfig)) {
                    throw new IllegalStateException("This exact alert already exists");
                }
            }
            if (!found) {
                throw new AlertNotFoundException();
            }
            AgentConfig updatedAgentConfig = agentConfig.toBuilder()
                    .clearAlertConfig()
                    .addAllAlertConfig(existingAlertConfigs)
                    .build();
            configDao.insert(agentRollupId, updatedAgentConfig);
            // updating the agent is inside synchronized block to ensure ordering of updates
            notifyAgentConfigListeners(agentRollupId);
        }
    }

    // central supports alert configs on rollups
    @Override
    public void deleteAlertConfig(String agentRollupId, String agentConfigId) throws Exception {
        synchronized (agentConfigLocks.getUnchecked(agentRollupId)) {
            AgentConfig agentConfig = configDao.read(agentRollupId);
            if (agentConfig == null) {
                throw new IllegalStateException("Agent config not found");
            }
            List<AlertConfig> existingAlertConfigs =
                    Lists.newArrayList(agentConfig.getAlertConfigList());
            ListIterator<AlertConfig> i = existingAlertConfigs.listIterator();
            boolean found = false;
            while (i.hasNext()) {
                if (i.next().getId().equals(agentConfigId)) {
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
            configDao.insert(agentRollupId, updatedAgentConfig);
            // updating the agent is inside synchronized block to ensure ordering of updates
            notifyAgentConfigListeners(agentRollupId);
        }
    }

    // central supports ui config on rollups
    @Override
    public void updateUiConfig(String agentRollupId, UiConfig uiConfig, String priorVersion)
            throws Exception {
        synchronized (agentConfigLocks.getUnchecked(agentRollupId)) {
            AgentConfig agentConfig = configDao.read(agentRollupId);
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
            configDao.insert(agentRollupId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of
            // updates
            notifyAgentConfigListeners(agentRollupId);
        }
    }

    @Override
    public void updatePluginConfig(String agentId, String pluginId,
            List<PluginProperty> properties, String priorVersion) throws Exception {
        synchronized (agentConfigLocks.getUnchecked(agentId)) {
            AgentConfig agentConfig = configDao.read(agentId);
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
            configDao.insert(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            notifyAgentConfigListeners(agentId);
        }
    }

    @Override
    public void insertInstrumentationConfig(String agentId,
            InstrumentationConfig instrumentationConfig) throws Exception {
        synchronized (agentConfigLocks.getUnchecked(agentId)) {
            AgentConfig agentConfig = configDao.read(agentId);
            if (agentConfig == null) {
                throw new IllegalStateException("Agent config not found");
            }
            checkInstrumentationDoesNotExist(instrumentationConfig,
                    agentConfig.getInstrumentationConfigList());
            AgentConfig updatedAgentConfig = agentConfig.toBuilder()
                    .addInstrumentationConfig(instrumentationConfig)
                    .build();
            configDao.insert(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            notifyAgentConfigListeners(agentId);
        }
    }

    @Override
    public void updateInstrumentationConfig(String agentId,
            InstrumentationConfig instrumentationConfig, String priorVersion) throws Exception {
        synchronized (agentConfigLocks.getUnchecked(agentId)) {
            AgentConfig agentConfig = configDao.read(agentId);
            if (agentConfig == null) {
                throw new IllegalStateException("Agent config not found");
            }
            String newVersion = Versions.getVersion(instrumentationConfig);
            List<InstrumentationConfig> existingInstrumentationConfigs =
                    Lists.newArrayList(agentConfig.getInstrumentationConfigList());
            ListIterator<InstrumentationConfig> i = existingInstrumentationConfigs.listIterator();
            boolean found = false;
            while (i.hasNext()) {
                String loopVersion = Versions.getVersion(i.next());
                if (loopVersion.equals(priorVersion)) {
                    i.set(instrumentationConfig);
                    found = true;
                } else if (loopVersion.equals(newVersion)) {
                    throw new IllegalStateException("This exact instrumentation already exists");
                }
            }
            if (!found) {
                throw new OptimisticLockException();
            }
            AgentConfig updatedAgentConfig = agentConfig.toBuilder()
                    .clearInstrumentationConfig()
                    .addAllInstrumentationConfig(existingInstrumentationConfigs)
                    .build();
            configDao.insert(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            notifyAgentConfigListeners(agentId);
        }
    }

    @Override
    public void deleteInstrumentationConfigs(String agentId, List<String> versions)
            throws Exception {
        synchronized (agentConfigLocks.getUnchecked(agentId)) {
            AgentConfig agentConfig = configDao.read(agentId);
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
            configDao.insert(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            notifyAgentConfigListeners(agentId);
        }
    }

    @Override
    public void insertInstrumentationConfigs(String agentId,
            List<InstrumentationConfig> configs) throws Exception {
        synchronized (agentConfigLocks.getUnchecked(agentId)) {
            AgentConfig agentConfig = configDao.read(agentId);
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
            configDao.insert(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            notifyAgentConfigListeners(agentId);
        }
    }

    @Override
    public void updateUserRecordingConfig(String agentId, UserRecordingConfig userRecordingConfig,
            String priorVersion) throws Exception {
        synchronized (agentConfigLocks.getUnchecked(agentId)) {
            AgentConfig agentConfig = configDao.read(agentId);
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
            configDao.insert(agentId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of updates
            notifyAgentConfigListeners(agentId);
        }
    }

    @Override
    public void updateAdvancedConfig(String agentRollupId, AdvancedConfig advancedConfig,
            String priorVersion) throws Exception {
        synchronized (agentConfigLocks.getUnchecked(agentRollupId)) {
            AgentConfig agentConfig = configDao.read(agentRollupId);
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
            configDao.insert(agentRollupId, updatedAgentConfig);
            // updating the agent is inside above synchronized block to ensure ordering of
            // updates
            notifyAgentConfigListeners(agentRollupId);
        }
    }

    @Override
    public void updateAgentRollupConfig(AgentRollupConfig agentRollupConfig, String priorVersion)
            throws Exception {
        synchronized (agentConfigLock) {
            AgentRollupConfig existingAgentRollupConfig =
                    agentDao.readAgentRollupConfig(agentRollupConfig.id());
            if (existingAgentRollupConfig == null) {
                throw new AgentRollupNotFoundException();
            }
            if (!existingAgentRollupConfig.version().equals(priorVersion)) {
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
            UserConfig existingUserConfig = userDao.read(userConfig.username());
            if (existingUserConfig == null) {
                throw new UserNotFoundException();
            }
            if (!existingUserConfig.version().equals(priorVersion)) {
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
            RoleConfig existingRoleConfig = roleDao.read(roleConfig.name());
            if (existingRoleConfig == null) {
                throw new RoleNotFoundException();
            }
            if (!existingRoleConfig.version().equals(priorVersion)) {
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
    public StorageConfig getStorageConfig() throws Exception {
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

    public void addAgentConfigListener(AgentConfigListener listener) {
        agentConfigListeners.add(listener);
    }

    // the updated config is not passed to the listeners to avoid the race condition of multiple
    // config updates being sent out of order, instead listeners must call get*Config() which will
    // never return the updates out of order (at worst it may return the most recent update twice
    // which is ok)
    private void notifyAgentConfigListeners(String agentId) throws Exception {
        for (AgentConfigListener agentConfigListener : agentConfigListeners) {
            agentConfigListener.onChange(agentId);
        }
    }

    static String generateAlertConfigId() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return BaseEncoding.base16().lowerCase().encode(bytes);
    }

    private static PluginConfig buildPluginConfig(PluginConfig existingPluginConfig,
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

    private static boolean isSameType(PluginProperty.Value left, PluginProperty.Value right) {
        if (left.getValCase() == ValCase.DVAL && right.getValCase() == ValCase.DVAL_NULL) {
            return true;
        }
        if (left.getValCase() == ValCase.DVAL_NULL && right.getValCase() == ValCase.DVAL) {
            return true;
        }
        return left.getValCase() == right.getValCase();
    }

    private static void checkAlertDoesNotExist(AlertConfig alertConfig,
            List<AlertConfig> alertConfigs) {
        // compare excluding id
        AlertConfig alertConfigWithoutId = getAlertWithoutId(alertConfig);
        for (AlertConfig loopAlertConfig : alertConfigs) {
            if (getAlertWithoutId(loopAlertConfig).equals(alertConfigWithoutId)) {
                throw new IllegalStateException("This exact alert already exists");
            }
        }
    }

    private static void checkInstrumentationDoesNotExist(
            InstrumentationConfig instrumentationConfig,
            List<InstrumentationConfig> instrumentationConfigs) {
        for (InstrumentationConfig loopInstrumentationConfig : instrumentationConfigs) {
            if (loopInstrumentationConfig.equals(instrumentationConfig)) {
                throw new IllegalStateException("This exact instrumentation already exists");
            }
        }
    }

    private static AlertConfig getAlertWithoutId(AlertConfig alertConfig) {
        return AlertConfig.newBuilder(alertConfig)
                .setId("")
                .build();
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

    public interface AgentConfigListener {

        // the new config is not passed to onChange so that the receiver has to get the latest,
        // this avoids race condition worries that two updates may get sent to the receiver in the
        // wrong order
        void onChange(String agentId) throws Exception;
    }
}
