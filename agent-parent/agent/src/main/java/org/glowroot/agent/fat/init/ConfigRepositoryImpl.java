/*
 * Copyright 2011-2016 the original author or authors.
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
package org.glowroot.agent.fat.init;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import javax.annotation.Nullable;
import javax.crypto.SecretKey;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.config.AdvancedConfig;
import org.glowroot.agent.config.AlertConfig;
import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.config.GaugeConfig;
import org.glowroot.agent.config.InstrumentationConfig;
import org.glowroot.agent.config.PluginCache;
import org.glowroot.agent.config.PluginConfig;
import org.glowroot.agent.config.PluginDescriptor;
import org.glowroot.agent.config.TransactionConfig;
import org.glowroot.agent.config.UiConfig;
import org.glowroot.agent.config.UserRecordingConfig;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.common.util.Versions;
import org.glowroot.storage.config.FatStorageConfig;
import org.glowroot.storage.config.ImmutableFatStorageConfig;
import org.glowroot.storage.config.ImmutableLdapConfig;
import org.glowroot.storage.config.ImmutableRoleConfig;
import org.glowroot.storage.config.ImmutableSmtpConfig;
import org.glowroot.storage.config.ImmutableUserConfig;
import org.glowroot.storage.config.ImmutableWebConfig;
import org.glowroot.storage.config.LdapConfig;
import org.glowroot.storage.config.RoleConfig;
import org.glowroot.storage.config.ServerStorageConfig;
import org.glowroot.storage.config.SmtpConfig;
import org.glowroot.storage.config.StorageConfig;
import org.glowroot.storage.config.UserConfig;
import org.glowroot.storage.config.WebConfig;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.util.LazySecretKey;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginProperty;

import static com.google.common.base.Preconditions.checkState;

class ConfigRepositoryImpl implements ConfigRepository {

    private static final Logger logger = LoggerFactory.getLogger(ConfigRepositoryImpl.class);

    private final ConfigService configService;
    private final PluginCache pluginCache;

    private final ImmutableList<RollupConfig> rollupConfigs;

    private final LazySecretKey secretKey;

    private final Object writeLock = new Object();

    private volatile ImmutableList<UserConfig> userConfigs;
    private volatile ImmutableList<RoleConfig> roleConfigs;
    private volatile WebConfig webConfig;
    private volatile FatStorageConfig storageConfig;
    private volatile SmtpConfig smtpConfig;
    private volatile LdapConfig ldapConfig;

    static ConfigRepository create(File baseDir, ConfigService configService,
            PluginCache pluginCache) {
        ConfigRepositoryImpl configRepository =
                new ConfigRepositoryImpl(baseDir, configService, pluginCache);
        // it's nice to update admin.json on startup if it is missing some/all config
        // properties so that the file contents can be reviewed/updated/copied if desired
        try {
            configRepository.writeAll();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
        return configRepository;
    }

    private ConfigRepositoryImpl(File baseDir, ConfigService configService,
            PluginCache pluginCache) {
        this.configService = configService;
        this.pluginCache = pluginCache;
        rollupConfigs = ImmutableList.copyOf(RollupConfig.buildRollupConfigs());
        secretKey = new LazySecretKey(new File(baseDir, "secret"));

        List<ImmutableUserConfig> userConfigs = configService.getAdminConfig(USERS_KEY,
                new TypeReference<List<ImmutableUserConfig>>() {});
        if (userConfigs == null) {
            this.userConfigs = ImmutableList.of();
        } else {
            this.userConfigs = ImmutableList.<UserConfig>copyOf(userConfigs);
        }
        if (this.userConfigs.isEmpty()) {
            this.userConfigs = ImmutableList.<UserConfig>of(ImmutableUserConfig.builder()
                    .username("anonymous")
                    .addRoles("Administrator")
                    .build());
        }
        List<ImmutableRoleConfig> roleConfigs = configService.getAdminConfig(ROLES_KEY,
                new TypeReference<List<ImmutableRoleConfig>>() {});
        if (roleConfigs == null) {
            this.roleConfigs = ImmutableList.of();
        } else {
            this.roleConfigs = ImmutableList.<RoleConfig>copyOf(roleConfigs);
        }
        if (this.roleConfigs.isEmpty()) {
            this.roleConfigs = ImmutableList.<RoleConfig>of(ImmutableRoleConfig.builder()
                    .name("Administrator")
                    .addPermissions("agent:transaction", "agent:error", "agent:jvm", "agent:config",
                            "admin")
                    .build());
        }
        WebConfig webConfig = configService.getAdminConfig(WEB_KEY, ImmutableWebConfig.class);
        if (webConfig == null) {
            this.webConfig = ImmutableWebConfig.builder().build();
        } else {
            this.webConfig = webConfig;
        }
        FatStorageConfig storageConfig =
                configService.getAdminConfig(STORAGE_KEY, ImmutableFatStorageConfig.class);
        if (storageConfig == null) {
            this.storageConfig = ImmutableFatStorageConfig.builder().build();
        } else if (storageConfig.hasListIssues()) {
            this.storageConfig = withCorrectedLists(storageConfig);
        } else {
            this.storageConfig = storageConfig;
        }
        SmtpConfig smtpConfig = configService.getAdminConfig(SMTP_KEY, ImmutableSmtpConfig.class);
        if (smtpConfig == null) {
            this.smtpConfig = ImmutableSmtpConfig.builder().build();
        } else {
            this.smtpConfig = smtpConfig;
        }
        LdapConfig ldapConfig = configService.getAdminConfig(LDAP_KEY, ImmutableLdapConfig.class);
        if (ldapConfig == null) {
            this.ldapConfig = ImmutableLdapConfig.builder().build();
        } else {
            this.ldapConfig = ldapConfig;
        }
    }

    @Override
    public AgentConfig.TransactionConfig getTransactionConfig(String agentId) {
        return configService.getTransactionConfig().toProto();
    }

    @Override
    public AgentConfig.UiConfig getUiConfig(String agentId) {
        return configService.getUiConfig().toProto();
    }

    @Override
    public AgentConfig.UserRecordingConfig getUserRecordingConfig(String agentId) {
        return configService.getUserRecordingConfig().toProto();
    }

    @Override
    public AgentConfig.AdvancedConfig getAdvancedConfig(String agentId) {
        return configService.getAdvancedConfig().toProto();
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
    public @Nullable AgentConfig.GaugeConfig getGaugeConfig(String agentId, String version) {
        for (GaugeConfig gaugeConfig : configService.getGaugeConfigs()) {
            AgentConfig.GaugeConfig config = gaugeConfig.toProto();
            if (Versions.getVersion(config).equals(version)) {
                return config;
            }
        }
        return null;
    }

    @Override
    public List<AgentConfig.AlertConfig> getAlertConfigs(String agentId) {
        List<AgentConfig.AlertConfig> configs = Lists.newArrayList();
        for (AlertConfig config : configService.getAlertConfigs()) {
            configs.add(config.toProto());
        }
        return configs;
    }

    @Override
    public @Nullable AgentConfig.AlertConfig getAlertConfig(String agentId, String version) {
        for (AlertConfig alertConfig : configService.getAlertConfigs()) {
            AgentConfig.AlertConfig config = alertConfig.toProto();
            if (Versions.getVersion(config).equals(version)) {
                return config;
            }
        }
        return null;
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
    public @Nullable AgentConfig.PluginConfig getPluginConfig(String agentId, String pluginId) {
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
    public @Nullable AgentConfig.InstrumentationConfig getInstrumentationConfig(String agentId,
            String version) {
        for (InstrumentationConfig instrumentationConfig : configService
                .getInstrumentationConfigs()) {
            AgentConfig.InstrumentationConfig config = instrumentationConfig.toProto();
            if (Versions.getVersion(config).equals(version)) {
                return config;
            }
        }
        return null;
    }

    @Override
    public List<UserConfig> getUserConfigs() {
        return userConfigs;
    }

    @Override
    public @Nullable UserConfig getUserConfig(String username) {
        for (UserConfig userConfig : userConfigs) {
            if (userConfig.username().equals(username)) {
                return userConfig;
            }
        }
        return null;
    }

    @Override
    public @Nullable UserConfig getUserConfigCaseInsensitive(String username) {
        for (UserConfig userConfig : userConfigs) {
            if (userConfig.username().equalsIgnoreCase(username)) {
                return userConfig;
            }
        }
        return null;
    }

    @Override
    public boolean namedUsersExist() {
        for (UserConfig userConfig : userConfigs) {
            if (!userConfig.username().equalsIgnoreCase("anonymous")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<RoleConfig> getRoleConfigs() {
        return roleConfigs;
    }

    @Override
    public @Nullable RoleConfig getRoleConfig(String name) {
        for (RoleConfig roleConfig : roleConfigs) {
            if (roleConfig.name().equals(name)) {
                return roleConfig;
            }
        }
        return null;
    }

    @Override
    public WebConfig getWebConfig() {
        return webConfig;
    }

    @Override
    public FatStorageConfig getFatStorageConfig() {
        return storageConfig;
    }

    @Override
    public ServerStorageConfig getServerStorageConfig() {
        throw new UnsupportedOperationException();
    }

    @Override
    public SmtpConfig getSmtpConfig() {
        return smtpConfig;
    }

    @Override
    public LdapConfig getLdapConfig() {
        return ldapConfig;
    }

    @Override
    public void updateTransactionConfig(String agentId,
            AgentConfig.TransactionConfig updatedConfig, String priorVersion) throws Exception {
        synchronized (writeLock) {
            String currVersion =
                    Versions.getVersion(configService.getTransactionConfig().toProto());
            checkVersionsEqual(currVersion, priorVersion);
            configService.updateTransactionConfig(TransactionConfig.create(updatedConfig));
        }
    }

    @Override
    public void insertGaugeConfig(String agentId, AgentConfig.GaugeConfig gaugeConfig)
            throws Exception {
        synchronized (writeLock) {
            List<GaugeConfig> configs = Lists.newArrayList(configService.getGaugeConfigs());
            // check for duplicate mbeanObjectName
            for (GaugeConfig loopConfig : configs) {
                if (loopConfig.mbeanObjectName().equals(gaugeConfig.getMbeanObjectName())) {
                    throw new DuplicateMBeanObjectNameException();
                }
            }
            // check for exact duplicate
            String version = Versions.getVersion(gaugeConfig);
            for (GaugeConfig loopConfig : configs) {
                if (Versions.getVersion(loopConfig.toProto()).equals(version)) {
                    throw new IllegalStateException("This exact gauge already exists");
                }
            }
            configs.add(GaugeConfig.create(gaugeConfig));
            configService.updateGaugeConfigs(configs);
        }
    }

    @Override
    public void updateGaugeConfig(String agentId, AgentConfig.GaugeConfig gaugeConfig,
            String priorVersion) throws Exception {
        synchronized (writeLock) {
            List<GaugeConfig> configs = Lists.newArrayList(configService.getGaugeConfigs());
            boolean found = false;
            for (ListIterator<GaugeConfig> i = configs.listIterator(); i.hasNext();) {
                GaugeConfig loopConfig = i.next();
                String loopVersion = Versions.getVersion(loopConfig.toProto());
                if (loopVersion.equals(priorVersion)) {
                    i.set(GaugeConfig.create(gaugeConfig));
                    found = true;
                    break;
                } else if (loopConfig.mbeanObjectName().equals(gaugeConfig.getMbeanObjectName())) {
                    throw new DuplicateMBeanObjectNameException();
                }
            }
            if (!found) {
                throw new OptimisticLockException();
            }
            configService.updateGaugeConfigs(configs);
        }
    }

    @Override
    public void deleteGaugeConfig(String agentId, String version) throws Exception {
        synchronized (writeLock) {
            List<GaugeConfig> configs = Lists.newArrayList(configService.getGaugeConfigs());
            boolean found = false;
            for (ListIterator<GaugeConfig> i = configs.listIterator(); i.hasNext();) {
                GaugeConfig loopConfig = i.next();
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
            configService.updateGaugeConfigs(configs);
        }
    }

    @Override
    public void insertAlertConfig(String agentId, AgentConfig.AlertConfig alertConfig)
            throws Exception {
        synchronized (writeLock) {
            List<AlertConfig> configs =
                    Lists.newArrayList(configService.getAlertConfigs());
            // check for exact duplicate
            String version = Versions.getVersion(alertConfig);
            for (AlertConfig loopConfig : configs) {
                if (Versions.getVersion(loopConfig.toProto()).equals(version)) {
                    throw new IllegalStateException("This exact alert already exists");
                }
            }
            configs.add(AlertConfig.create(alertConfig));
            configService.updateAlertConfigs(configs);
        }
    }

    @Override
    public void updateAlertConfig(String agentId, AgentConfig.AlertConfig alertConfig,
            String priorVersion) throws Exception {
        synchronized (writeLock) {
            List<AlertConfig> configs = Lists.newArrayList(configService.getAlertConfigs());
            boolean found = false;
            for (ListIterator<AlertConfig> i = configs.listIterator(); i.hasNext();) {
                AlertConfig loopConfig = i.next();
                String loopVersion = Versions.getVersion(loopConfig.toProto());
                if (loopVersion.equals(priorVersion)) {
                    i.set(AlertConfig.create(alertConfig));
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new OptimisticLockException();
            }
            configService.updateAlertConfigs(configs);
        }
    }

    @Override
    public void deleteAlertConfig(String agentId, String version) throws Exception {
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
            configService.updateAlertConfigs(configs);
        }
    }

    @Override
    public void updateUiConfig(String agentId, AgentConfig.UiConfig uiConfig, String priorVersion)
            throws Exception {
        synchronized (writeLock) {
            String currVersion = Versions.getVersion(configService.getUiConfig().toProto());
            checkVersionsEqual(currVersion, priorVersion);
            configService.updateUiConfig(UiConfig.create(uiConfig));
        }
    }

    @Override
    public void updatePluginConfig(String agentId, String pluginId,
            List<PluginProperty> properties, String priorVersion) throws Exception {
        synchronized (writeLock) {
            List<PluginConfig> configs = Lists.newArrayList(configService.getPluginConfigs());
            boolean found = false;
            for (ListIterator<PluginConfig> i = configs.listIterator(); i.hasNext();) {
                PluginConfig loopPluginConfig = i.next();
                if (pluginId.equals(loopPluginConfig.id())) {
                    String loopVersion = Versions.getVersion(loopPluginConfig.toProto());
                    checkVersionsEqual(loopVersion, priorVersion);
                    PluginDescriptor pluginDescriptor = getPluginDescriptor(pluginId);
                    i.set(PluginConfig.create(pluginDescriptor, properties));
                    found = true;
                    break;
                }
            }
            checkState(found, "Plugin config not found: %s", pluginId);
            configService.updatePluginConfigs(configs);
        }
    }

    @Override
    public void insertInstrumentationConfig(String agentId,
            AgentConfig.InstrumentationConfig instrumentationConfig) throws Exception {
        synchronized (writeLock) {
            List<InstrumentationConfig> configs =
                    Lists.newArrayList(configService.getInstrumentationConfigs());
            // check for exact duplicate
            String version = Versions.getVersion(instrumentationConfig);
            for (InstrumentationConfig loopConfig : configs) {
                if (Versions.getVersion(loopConfig.toProto()).equals(version)) {
                    throw new IllegalStateException("This exact instrumentation already exists");
                }
            }
            configs.add(InstrumentationConfig.create(instrumentationConfig));
            configService.updateInstrumentationConfigs(configs);
        }
    }

    @Override
    public void updateInstrumentationConfig(String agentId,
            AgentConfig.InstrumentationConfig instrumentationConfig, String priorVersion)
            throws Exception {
        synchronized (writeLock) {
            List<InstrumentationConfig> configs =
                    Lists.newArrayList(configService.getInstrumentationConfigs());
            boolean found = false;
            for (ListIterator<InstrumentationConfig> i = configs.listIterator(); i.hasNext();) {
                InstrumentationConfig loopConfig = i.next();
                String loopVersion = Versions.getVersion(loopConfig.toProto());
                if (loopVersion.equals(priorVersion)) {
                    i.set(InstrumentationConfig.create(instrumentationConfig));
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new OptimisticLockException();
            }
            configService.updateInstrumentationConfigs(configs);
        }
    }

    @Override
    public void deleteInstrumentationConfigs(String agentId, List<String> versions)
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
    }

    @Override
    public void insertInstrumentationConfigs(String agentId,
            List<AgentConfig.InstrumentationConfig> instrumentationConfigs) throws Exception {
        synchronized (writeLock) {
            List<InstrumentationConfig> configs =
                    Lists.newArrayList(configService.getInstrumentationConfigs());
            for (AgentConfig.InstrumentationConfig instrumentationConfig : instrumentationConfigs) {
                // check for exact duplicate
                String version = Versions.getVersion(instrumentationConfig);
                for (InstrumentationConfig loopConfig : configs) {
                    if (Versions.getVersion(loopConfig.toProto()).equals(version)) {
                        throw new IllegalStateException(
                                "This exact instrumentation already exists");
                    }
                }
                configs.add(InstrumentationConfig.create(instrumentationConfig));
            }
            configService.updateInstrumentationConfigs(configs);
        }
    }

    @Override
    public void updateUserRecordingConfig(String agentId,
            AgentConfig.UserRecordingConfig userRecordingConfig, String priorVersion)
            throws Exception {
        synchronized (writeLock) {
            String currVersion =
                    Versions.getVersion(configService.getUserRecordingConfig().toProto());
            checkVersionsEqual(currVersion, priorVersion);
            configService
                    .updateUserRecordingConfig(UserRecordingConfig.create(userRecordingConfig));
        }
    }

    @Override
    public void updateAdvancedConfig(String agentId, AgentConfig.AdvancedConfig advancedConfig,
            String priorVersion) throws Exception {
        synchronized (writeLock) {
            String currVersion = Versions.getVersion(configService.getAdvancedConfig().toProto());
            checkVersionsEqual(currVersion, priorVersion);
            configService.updateAdvancedConfig(AdvancedConfig.create(advancedConfig));
        }
    }

    @Override
    public void insertUserConfig(UserConfig userConfig) throws Exception {
        synchronized (writeLock) {
            List<UserConfig> configs = Lists.newArrayList(userConfigs);
            // check for case-insensitive duplicate
            String username = userConfig.username();
            for (UserConfig loopConfig : configs) {
                if (loopConfig.username().equalsIgnoreCase(username)) {
                    throw new DuplicateUsernameException();
                }
            }
            configs.add(userConfig);
            configService.updateAdminConfig(USERS_KEY, configs);
            userConfigs = ImmutableList.copyOf(configs);
        }
    }

    @Override
    public void updateUserConfig(UserConfig userConfig, String priorVersion) throws Exception {
        synchronized (writeLock) {
            List<UserConfig> configs = Lists.newArrayList(userConfigs);
            String username = userConfig.username();
            boolean found = false;
            for (ListIterator<UserConfig> i = configs.listIterator(); i.hasNext();) {
                UserConfig loopConfig = i.next();
                if (loopConfig.username().equals(username)) {
                    if (loopConfig.version().equals(priorVersion)) {
                        i.set(userConfig);
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
            configService.updateAdminConfig(USERS_KEY, configs);
            userConfigs = ImmutableList.copyOf(configs);
        }
    }

    @Override
    public void deleteUserConfig(String username) throws Exception {
        synchronized (writeLock) {
            List<UserConfig> configs = Lists.newArrayList(userConfigs);
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
            configService.updateAdminConfig(USERS_KEY, configs);
            userConfigs = ImmutableList.copyOf(configs);
        }
    }

    @Override
    public void insertRoleConfig(RoleConfig roleConfig) throws Exception {
        synchronized (writeLock) {
            List<RoleConfig> configs = Lists.newArrayList(roleConfigs);
            // check for case-insensitive duplicate
            String name = roleConfig.name();
            for (RoleConfig loopConfig : configs) {
                if (loopConfig.name().equalsIgnoreCase(name)) {
                    throw new DuplicateRoleNameException();
                }
            }
            configs.add(roleConfig);
            configService.updateAdminConfig(ROLES_KEY, configs);
            roleConfigs = ImmutableList.copyOf(configs);
        }
    }

    @Override
    public void updateRoleConfig(RoleConfig roleConfig, String priorVersion) throws Exception {
        synchronized (writeLock) {
            List<RoleConfig> configs = Lists.newArrayList(roleConfigs);
            String name = roleConfig.name();
            boolean found = false;
            for (ListIterator<RoleConfig> i = configs.listIterator(); i.hasNext();) {
                RoleConfig loopConfig = i.next();
                if (loopConfig.name().equals(name)) {
                    if (loopConfig.version().equals(priorVersion)) {
                        i.set(roleConfig);
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
            configService.updateAdminConfig(ROLES_KEY, configs);
            roleConfigs = ImmutableList.copyOf(configs);
        }
    }

    @Override
    public void deleteRoleConfig(String name) throws Exception {
        synchronized (writeLock) {
            List<RoleConfig> configs = Lists.newArrayList(roleConfigs);
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
            configService.updateAdminConfig(ROLES_KEY, configs);
            roleConfigs = ImmutableList.copyOf(configs);
        }
    }

    @Override
    public void updateWebConfig(WebConfig updatedConfig, String priorVersion)
            throws Exception {
        synchronized (writeLock) {
            checkVersionsEqual(webConfig.version(), priorVersion);
            configService.updateAdminConfig(WEB_KEY, updatedConfig);
            webConfig = updatedConfig;
        }
    }

    @Override
    public void updateFatStorageConfig(FatStorageConfig updatedConfig, String priorVersion)
            throws Exception {
        synchronized (writeLock) {
            checkVersionsEqual(storageConfig.version(), priorVersion);
            configService.updateAdminConfig(STORAGE_KEY, updatedConfig);
            storageConfig = updatedConfig;
        }
    }

    @Override
    public void updateServerStorageConfig(ServerStorageConfig updatedConfig, String priorVersion) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateSmtpConfig(SmtpConfig updatedConfig, String priorVersion) throws Exception {
        synchronized (writeLock) {
            checkVersionsEqual(smtpConfig.version(), priorVersion);
            configService.updateAdminConfig(SMTP_KEY, updatedConfig);
            smtpConfig = updatedConfig;
        }
    }

    @Override
    public void updateLdapConfig(LdapConfig updatedConfig, String priorVersion) throws Exception {
        synchronized (writeLock) {
            checkVersionsEqual(ldapConfig.version(), priorVersion);
            configService.updateAdminConfig(LDAP_KEY, updatedConfig);
            ldapConfig = updatedConfig;
        }
    }

    @Override
    public StorageConfig getStorageConfig() {
        return getFatStorageConfig();
    }

    @Override
    public long getGaugeCollectionIntervalMillis() {
        return configService.getGaugeCollectionIntervalMillis();
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

    private PluginDescriptor getPluginDescriptor(String pluginId) {
        for (PluginDescriptor pluginDescriptor : pluginCache.pluginDescriptors()) {
            if (pluginDescriptor.id().equals(pluginId)) {
                return pluginDescriptor;
            }
        }
        throw new IllegalStateException("Could not find plugin descriptor: " + pluginId);
    }

    private void checkVersionsEqual(String version, String priorVersion)
            throws OptimisticLockException {
        if (!version.equals(priorVersion)) {
            throw new OptimisticLockException();
        }
    }

    @OnlyUsedByTests
    public void resetAdminConfig() throws IOException {
        userConfigs = ImmutableList.<UserConfig>of(ImmutableUserConfig.builder()
                .username("anonymous")
                .addRoles("Administrator")
                .build());
        roleConfigs = ImmutableList.<RoleConfig>of(ImmutableRoleConfig.builder()
                .name("Administrator")
                .addPermissions("agent:transaction", "agent:error", "agent:jvm",
                        "agent:config:view", "agent:config:edit", "admin")
                .build());
        webConfig = ImmutableWebConfig.builder().build();
        storageConfig = ImmutableFatStorageConfig.builder().build();
        smtpConfig = ImmutableSmtpConfig.builder().build();
        ldapConfig = ImmutableLdapConfig.builder().build();
        writeAll();
    }

    private void writeAll() throws IOException {
        // linked hash map to preserve ordering when writing to config file
        Map<String, Object> configs = Maps.newLinkedHashMap();
        configs.put(USERS_KEY, userConfigs);
        configs.put(ROLES_KEY, roleConfigs);
        configs.put(WEB_KEY, webConfig);
        configs.put(STORAGE_KEY, storageConfig);
        configs.put(SMTP_KEY, smtpConfig);
        configs.put(LDAP_KEY, ldapConfig);
        configService.updateAdminConfigs(configs);
    }

    private static FatStorageConfig withCorrectedLists(FatStorageConfig storageConfig) {
        FatStorageConfig defaultConfig = ImmutableFatStorageConfig.builder().build();
        ImmutableList<Integer> rollupExpirationHours =
                fix(storageConfig.rollupExpirationHours(), defaultConfig.rollupExpirationHours());
        ImmutableList<Integer> rollupCappedDatabaseSizesMb =
                fix(storageConfig.rollupCappedDatabaseSizesMb(),
                        defaultConfig.rollupCappedDatabaseSizesMb());
        return ImmutableFatStorageConfig.builder()
                .copyFrom(storageConfig)
                .rollupExpirationHours(rollupExpirationHours)
                .rollupCappedDatabaseSizesMb(rollupCappedDatabaseSizesMb)
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
