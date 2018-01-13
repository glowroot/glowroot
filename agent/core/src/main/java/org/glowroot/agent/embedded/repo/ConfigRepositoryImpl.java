/*
 * Copyright 2011-2018 the original author or authors.
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

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import org.glowroot.agent.config.AdvancedConfig;
import org.glowroot.agent.config.AlertConfig;
import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.config.GaugeConfig;
import org.glowroot.agent.config.InstrumentationConfig;
import org.glowroot.agent.config.JvmConfig;
import org.glowroot.agent.config.PluginCache;
import org.glowroot.agent.config.PluginConfig;
import org.glowroot.agent.config.PluginDescriptor;
import org.glowroot.agent.config.TransactionConfig;
import org.glowroot.agent.config.UiConfig;
import org.glowroot.agent.config.UserRecordingConfig;
import org.glowroot.common.config.CentralAdminGeneralConfig;
import org.glowroot.common.config.CentralStorageConfig;
import org.glowroot.common.config.CentralWebConfig;
import org.glowroot.common.config.EmbeddedAdminGeneralConfig;
import org.glowroot.common.config.EmbeddedStorageConfig;
import org.glowroot.common.config.EmbeddedWebConfig;
import org.glowroot.common.config.HealthchecksIoConfig;
import org.glowroot.common.config.HttpProxyConfig;
import org.glowroot.common.config.ImmutableEmbeddedAdminGeneralConfig;
import org.glowroot.common.config.ImmutableEmbeddedStorageConfig;
import org.glowroot.common.config.ImmutableEmbeddedWebConfig;
import org.glowroot.common.config.ImmutableHealthchecksIoConfig;
import org.glowroot.common.config.ImmutableHttpProxyConfig;
import org.glowroot.common.config.ImmutableLdapConfig;
import org.glowroot.common.config.ImmutablePagerDutyConfig;
import org.glowroot.common.config.ImmutableRoleConfig;
import org.glowroot.common.config.ImmutableSmtpConfig;
import org.glowroot.common.config.ImmutableUserConfig;
import org.glowroot.common.config.LdapConfig;
import org.glowroot.common.config.PagerDutyConfig;
import org.glowroot.common.config.PagerDutyConfig.PagerDutyIntegrationKey;
import org.glowroot.common.config.RoleConfig;
import org.glowroot.common.config.SmtpConfig;
import org.glowroot.common.config.StorageConfig;
import org.glowroot.common.config.UserConfig;
import org.glowroot.common.config.WebConfig;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.util.LazySecretKey;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.common.util.Versions;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginProperty;

import static com.google.common.base.Preconditions.checkState;

public class ConfigRepositoryImpl implements ConfigRepository {

    private static final String HEALTHCHECKS_IO_KEY = "healthchecksIo";

    private final ConfigService configService;
    private final PluginCache pluginCache;

    private final ImmutableList<RollupConfig> rollupConfigs;

    private final LazySecretKey lazySecretKey;

    private final Object writeLock = new Object();

    private volatile EmbeddedAdminGeneralConfig generalConfig;
    private volatile ImmutableList<UserConfig> userConfigs;
    private volatile ImmutableList<RoleConfig> roleConfigs;
    private volatile EmbeddedWebConfig webConfig;
    private volatile EmbeddedStorageConfig storageConfig;
    private volatile SmtpConfig smtpConfig;
    private volatile HttpProxyConfig httpProxyConfig;
    private volatile LdapConfig ldapConfig;
    private volatile PagerDutyConfig pagerDutyConfig;
    private volatile HealthchecksIoConfig healthchecksIoConfig;

    public static ConfigRepositoryImpl create(File confDir, ConfigService configService,
            PluginCache pluginCache) throws IOException {
        ConfigRepositoryImpl configRepository =
                new ConfigRepositoryImpl(confDir, configService, pluginCache);
        // it's nice to update admin.json on startup if it is missing some/all config
        // properties so that the file contents can be reviewed/updated/copied if desired
        configRepository.writeAll();
        return configRepository;
    }

    private ConfigRepositoryImpl(File confDir, ConfigService configService,
            PluginCache pluginCache) {
        this.configService = configService;
        this.pluginCache = pluginCache;
        rollupConfigs = ImmutableList.copyOf(RollupConfig.buildRollupConfigs());
        lazySecretKey = new LazySecretKeyImpl(new File(confDir, "secret"));

        EmbeddedAdminGeneralConfig generalConfig = configService.getAdminConfig(GENERAL_KEY,
                ImmutableEmbeddedAdminGeneralConfig.class);
        if (generalConfig == null) {
            this.generalConfig = ImmutableEmbeddedAdminGeneralConfig.builder().build();
        } else {
            this.generalConfig = generalConfig;
        }
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
                    .addPermissions("agent:transaction", "agent:error", "agent:jvm",
                            "agent:incident", "agent:config", "admin")
                    .build());
        }
        EmbeddedWebConfig webConfig =
                configService.getAdminConfig(WEB_KEY, ImmutableEmbeddedWebConfig.class);
        if (webConfig == null) {
            this.webConfig = ImmutableEmbeddedWebConfig.builder().build();
        } else {
            this.webConfig = webConfig;
        }
        EmbeddedStorageConfig storageConfig =
                configService.getAdminConfig(STORAGE_KEY, ImmutableEmbeddedStorageConfig.class);
        if (storageConfig == null) {
            this.storageConfig = ImmutableEmbeddedStorageConfig.builder().build();
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
        HttpProxyConfig httpProxyConfig =
                configService.getAdminConfig(HTTP_PROXY_KEY, ImmutableHttpProxyConfig.class);
        if (httpProxyConfig == null) {
            this.httpProxyConfig = ImmutableHttpProxyConfig.builder().build();
        } else {
            this.httpProxyConfig = httpProxyConfig;
        }
        LdapConfig ldapConfig = configService.getAdminConfig(LDAP_KEY, ImmutableLdapConfig.class);
        if (ldapConfig == null) {
            this.ldapConfig = ImmutableLdapConfig.builder().build();
        } else {
            this.ldapConfig = ldapConfig;
        }
        PagerDutyConfig pagerDutyConfig =
                configService.getAdminConfig(PAGER_DUTY_KEY, ImmutablePagerDutyConfig.class);
        if (pagerDutyConfig == null) {
            this.pagerDutyConfig = ImmutablePagerDutyConfig.builder().build();
        } else {
            this.pagerDutyConfig = pagerDutyConfig;
        }
        HealthchecksIoConfig healthchecksIoConfig = configService
                .getAdminConfig(HEALTHCHECKS_IO_KEY, ImmutableHealthchecksIoConfig.class);
        if (healthchecksIoConfig == null) {
            this.healthchecksIoConfig = ImmutableHealthchecksIoConfig.builder().build();
        } else {
            this.healthchecksIoConfig = healthchecksIoConfig;
        }
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
    public AgentConfig.UiConfig getUiConfig(String agentRollupId) {
        return configService.getUiConfig().toProto();
    }

    @Override
    public AgentConfig.UserRecordingConfig getUserRecordingConfig(String agentId) {
        return configService.getUserRecordingConfig().toProto();
    }

    @Override
    public AgentConfig.AdvancedConfig getAdvancedConfig(String agentRollupId) {
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
    public List<AgentConfig.SyntheticMonitorConfig> getSyntheticMonitorConfigs(
            String agentRollupId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @Nullable AgentConfig.SyntheticMonitorConfig getSyntheticMonitorConfig(
            String agentRollupId, String syntheticMonitorId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<AgentConfig.AlertConfig> getAlertConfigs(String agentRollupId) throws Exception {
        List<AgentConfig.AlertConfig> configs = Lists.newArrayList();
        for (AlertConfig config : configService.getAlertConfigs()) {
            configs.add(config.toProto());
        }
        return configs;
    }

    @Override
    public @Nullable AgentConfig.AlertConfig getAlertConfig(String agentRollupId,
            String alertVersion) {
        for (AlertConfig alertConfig : configService.getAlertConfigs()) {
            AgentConfig.AlertConfig config = alertConfig.toProto();
            if (Versions.getVersion(config).equals(alertVersion)) {
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
        for (InstrumentationConfig config : configService.getInstrumentationConfigs()) {
            AgentConfig.InstrumentationConfig protoConfig = config.toProto();
            if (Versions.getVersion(protoConfig).equals(version)) {
                return protoConfig;
            }
        }
        return null;
    }

    @Override
    public EmbeddedAdminGeneralConfig getEmbeddedAdminGeneralConfig() {
        return generalConfig;
    }

    @Override
    public CentralAdminGeneralConfig getCentralAdminGeneralConfig() {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<UserConfig> getUserConfigs() {
        return userConfigs;
    }

    @Override
    public @Nullable UserConfig getUserConfig(String username) {
        for (UserConfig config : userConfigs) {
            if (config.username().equals(username)) {
                return config;
            }
        }
        return null;
    }

    @Override
    public @Nullable UserConfig getUserConfigCaseInsensitive(String username) {
        for (UserConfig config : userConfigs) {
            if (config.username().equalsIgnoreCase(username)) {
                return config;
            }
        }
        return null;
    }

    @Override
    public boolean namedUsersExist() {
        for (UserConfig config : userConfigs) {
            if (!config.username().equalsIgnoreCase("anonymous")) {
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
        for (RoleConfig config : roleConfigs) {
            if (config.name().equals(name)) {
                return config;
            }
        }
        return null;
    }

    @Override
    public WebConfig getWebConfig() {
        return getEmbeddedWebConfig();
    }

    @Override
    public EmbeddedWebConfig getEmbeddedWebConfig() {
        return webConfig;
    }

    @Override
    public CentralWebConfig getCentralWebConfig() {
        throw new UnsupportedOperationException();
    }

    @Override
    public StorageConfig getStorageConfig() {
        return getEmbeddedStorageConfig();
    }

    @Override
    public EmbeddedStorageConfig getEmbeddedStorageConfig() {
        return storageConfig;
    }

    @Override
    public CentralStorageConfig getCentralStorageConfig() {
        throw new UnsupportedOperationException();
    }

    @Override
    public SmtpConfig getSmtpConfig() {
        return smtpConfig;
    }

    @Override
    public HttpProxyConfig getHttpProxyConfig() {
        return httpProxyConfig;
    }

    @Override
    public LdapConfig getLdapConfig() {
        return ldapConfig;
    }

    @Override
    public PagerDutyConfig getPagerDutyConfig() {
        return pagerDutyConfig;
    }

    @Override
    public HealthchecksIoConfig getHealthchecksIoConfig() {
        return healthchecksIoConfig;
    }

    @Override
    public void updateGeneralConfig(String agentId, AgentConfig.GeneralConfig protoConfig,
            String priorVersion) throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateTransactionConfig(String agentId, AgentConfig.TransactionConfig protoConfig,
            String priorVersion) throws Exception {
        TransactionConfig config = TransactionConfig.create(protoConfig);
        synchronized (writeLock) {
            String currVersion =
                    Versions.getVersion(configService.getTransactionConfig().toProto());
            checkVersionsEqual(currVersion, priorVersion);
            configService.updateTransactionConfig(config);
        }
    }

    @Override
    public void insertGaugeConfig(String agentId, AgentConfig.GaugeConfig protoConfig)
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
    }

    @Override
    public void updateGaugeConfig(String agentId, AgentConfig.GaugeConfig protoConfig,
            String priorVersion) throws Exception {
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
    }

    @Override
    public void deleteGaugeConfig(String agentId, String version) throws Exception {
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
    }

    @Override
    public void updateJvmConfig(String agentId, AgentConfig.JvmConfig protoConfig,
            String priorVersion) throws Exception {
        JvmConfig config = JvmConfig.create(protoConfig);
        synchronized (writeLock) {
            String currVersion =
                    Versions.getVersion(configService.getJvmConfig().toProto());
            checkVersionsEqual(currVersion, priorVersion);
            configService.updateJvmConfig(config);
        }
    }

    @Override
    public String insertSyntheticMonitorConfig(String agentRollupId,
            AgentConfig.SyntheticMonitorConfig configWithoutId) throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateSyntheticMonitorConfig(String agentRollupId,
            AgentConfig.SyntheticMonitorConfig config, String priorVersion) throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteSyntheticMonitorConfig(String agentRollupId, String syntheticMonitorId)
            throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    public void insertAlertConfig(String agentRollupId, AgentConfig.AlertConfig configWithoutId)
            throws Exception {
        synchronized (writeLock) {
            List<AlertConfig> configs =
                    Lists.newArrayList(configService.getAlertConfigs());
            // check for exact duplicate
            String version = Versions.getVersion(configWithoutId);
            for (AlertConfig loopConfig : configs) {
                if (Versions.getVersion(loopConfig.toProto()).equals(version)) {
                    throw new IllegalStateException("This exact alert already exists");
                }
            }
            configs.add(AlertConfig.create(configWithoutId));
            configService.updateAlertConfigs(configs);
        }
    }

    @Override
    public void updateAlertConfig(String agentRollupId, AgentConfig.AlertConfig config,
            String priorVersion) throws Exception {
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
            configService.updateAlertConfigs(configs);
        }
    }

    @Override
    public void deleteAlertConfig(String agentRollupId, String version) throws Exception {
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

    @Override
    public void updateUiConfig(String agentId, AgentConfig.UiConfig protoConfig,
            String priorVersion) throws Exception {
        UiConfig config = UiConfig.create(protoConfig);
        synchronized (writeLock) {
            String currVersion = Versions.getVersion(configService.getUiConfig().toProto());
            checkVersionsEqual(currVersion, priorVersion);
            configService.updateUiConfig(config);
        }
    }

    @Override
    public void updatePluginConfig(String agentId, String pluginId,
            List<PluginProperty> properties, String priorVersion) throws Exception {
        PluginDescriptor pluginDescriptor = getPluginDescriptor(pluginId);
        PluginConfig config = PluginConfig.create(pluginDescriptor, properties);
        synchronized (writeLock) {
            List<PluginConfig> configs = Lists.newArrayList(configService.getPluginConfigs());
            boolean found = false;
            for (ListIterator<PluginConfig> i = configs.listIterator(); i.hasNext();) {
                PluginConfig loopPluginConfig = i.next();
                if (pluginId.equals(loopPluginConfig.id())) {
                    String loopVersion = Versions.getVersion(loopPluginConfig.toProto());
                    checkVersionsEqual(loopVersion, priorVersion);
                    i.set(config);
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
            AgentConfig.InstrumentationConfig protoConfig) throws Exception {
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
    }

    @Override
    public void updateInstrumentationConfig(String agentId,
            AgentConfig.InstrumentationConfig protoConfig, String priorVersion) throws Exception {
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

    // ignores any instrumentation configs that are duplicates of existing instrumentation configs
    @Override
    public void insertInstrumentationConfigs(String agentId,
            List<AgentConfig.InstrumentationConfig> protoConfigs) throws Exception {
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
    }

    @Override
    public void updateUserRecordingConfig(String agentId,
            AgentConfig.UserRecordingConfig protoConfig, String priorVersion)
            throws Exception {
        UserRecordingConfig config = UserRecordingConfig.create(protoConfig);
        synchronized (writeLock) {
            String currVersion =
                    Versions.getVersion(configService.getUserRecordingConfig().toProto());
            checkVersionsEqual(currVersion, priorVersion);
            configService.updateUserRecordingConfig(config);
        }
    }

    @Override
    public void updateAdvancedConfig(String agentId, AgentConfig.AdvancedConfig protoConfig,
            String priorVersion) throws Exception {
        AdvancedConfig config = AdvancedConfig.create(protoConfig);
        synchronized (writeLock) {
            String currVersion = Versions.getVersion(configService.getAdvancedConfig().toProto());
            checkVersionsEqual(currVersion, priorVersion);
            configService.updateAdvancedConfig(config);
        }
    }

    @Override
    public void updateEmbeddedAdminGeneralConfig(EmbeddedAdminGeneralConfig config,
            String priorVersion) throws Exception {
        synchronized (writeLock) {
            checkVersionsEqual(generalConfig.version(), priorVersion);
            configService.updateAdminConfig(GENERAL_KEY, config);
            generalConfig = config;
        }
    }

    @Override
    public void updateCentralAdminGeneralConfig(CentralAdminGeneralConfig config,
            String priorVersion) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void insertUserConfig(UserConfig config) throws Exception {
        synchronized (writeLock) {
            List<UserConfig> configs = Lists.newArrayList(userConfigs);
            // check for case-insensitive duplicate
            String username = config.username();
            for (UserConfig loopConfig : configs) {
                if (loopConfig.username().equalsIgnoreCase(username)) {
                    throw new DuplicateUsernameException();
                }
            }
            configs.add(config);
            configService.updateAdminConfig(USERS_KEY, configs);
            userConfigs = ImmutableList.copyOf(configs);
        }
    }

    @Override
    public void updateUserConfig(UserConfig config, String priorVersion) throws Exception {
        synchronized (writeLock) {
            List<UserConfig> configs = Lists.newArrayList(userConfigs);
            String username = config.username();
            boolean found = false;
            for (ListIterator<UserConfig> i = configs.listIterator(); i.hasNext();) {
                UserConfig loopConfig = i.next();
                if (loopConfig.username().equals(username)) {
                    if (loopConfig.version().equals(priorVersion)) {
                        i.set(config);
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
            if (getSmtpConfig().host().isEmpty() && configs.isEmpty()) {
                throw new CannotDeleteLastUserException();
            }
            configService.updateAdminConfig(USERS_KEY, configs);
            userConfigs = ImmutableList.copyOf(configs);
        }
    }

    @Override
    public void insertRoleConfig(RoleConfig config) throws Exception {
        synchronized (writeLock) {
            List<RoleConfig> configs = Lists.newArrayList(roleConfigs);
            // check for case-insensitive duplicate
            String name = config.name();
            for (RoleConfig loopConfig : configs) {
                if (loopConfig.name().equalsIgnoreCase(name)) {
                    throw new DuplicateRoleNameException();
                }
            }
            configs.add(config);
            configService.updateAdminConfig(ROLES_KEY, configs);
            roleConfigs = ImmutableList.copyOf(configs);
        }
    }

    @Override
    public void updateRoleConfig(RoleConfig config, String priorVersion) throws Exception {
        synchronized (writeLock) {
            List<RoleConfig> configs = Lists.newArrayList(roleConfigs);
            String name = config.name();
            boolean found = false;
            for (ListIterator<RoleConfig> i = configs.listIterator(); i.hasNext();) {
                RoleConfig loopConfig = i.next();
                if (loopConfig.name().equals(name)) {
                    if (loopConfig.version().equals(priorVersion)) {
                        i.set(config);
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
            if (configs.isEmpty()) {
                throw new CannotDeleteLastRoleException();
            }
            configService.updateAdminConfig(ROLES_KEY, configs);
            roleConfigs = ImmutableList.copyOf(configs);
        }
    }

    @Override
    public void updateEmbeddedWebConfig(EmbeddedWebConfig config, String priorVersion)
            throws Exception {
        synchronized (writeLock) {
            checkVersionsEqual(webConfig.version(), priorVersion);
            configService.updateAdminConfig(WEB_KEY, config);
            webConfig = config;
        }
    }

    @Override
    public void updateCentralWebConfig(CentralWebConfig config, String priorVersion) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateEmbeddedStorageConfig(EmbeddedStorageConfig config, String priorVersion)
            throws Exception {
        synchronized (writeLock) {
            checkVersionsEqual(storageConfig.version(), priorVersion);
            configService.updateAdminConfig(STORAGE_KEY, config);
            storageConfig = config;
        }
    }

    @Override
    public void updateCentralStorageConfig(CentralStorageConfig config, String priorVersion) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateSmtpConfig(SmtpConfig config, String priorVersion) throws Exception {
        synchronized (writeLock) {
            checkVersionsEqual(smtpConfig.version(), priorVersion);
            configService.updateAdminConfig(SMTP_KEY, config);
            smtpConfig = config;
        }
    }

    @Override
    public void updateHttpProxyConfig(HttpProxyConfig config, String priorVersion)
            throws Exception {
        synchronized (writeLock) {
            checkVersionsEqual(httpProxyConfig.version(), priorVersion);
            configService.updateAdminConfig(HTTP_PROXY_KEY, config);
            httpProxyConfig = config;
        }
    }

    @Override
    public void updateLdapConfig(LdapConfig config, String priorVersion) throws Exception {
        synchronized (writeLock) {
            checkVersionsEqual(ldapConfig.version(), priorVersion);
            configService.updateAdminConfig(LDAP_KEY, config);
            ldapConfig = config;
        }
    }

    @Override
    public void updatePagerDutyConfig(PagerDutyConfig config, String priorVersion)
            throws Exception {
        synchronized (writeLock) {
            checkVersionsEqual(pagerDutyConfig.version(), priorVersion);
            // check for duplicate integration key / display
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
            configService.updateAdminConfig(PAGER_DUTY_KEY, config);
            pagerDutyConfig = config;
        }
    }

    @Override
    public void updateHealthchecksIoConfig(HealthchecksIoConfig updatedConfig, String priorVersion)
            throws Exception {
        synchronized (writeLock) {
            checkVersionsEqual(healthchecksIoConfig.version(), priorVersion);
            configService.updateAdminConfig(HEALTHCHECKS_IO_KEY, updatedConfig);
            healthchecksIoConfig = updatedConfig;
        }
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
    public LazySecretKey getLazySecretKey() throws Exception {
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
    public void resetAdminConfig() throws IOException {
        generalConfig = ImmutableEmbeddedAdminGeneralConfig.builder().build();
        userConfigs = ImmutableList.<UserConfig>of(ImmutableUserConfig.builder()
                .username("anonymous")
                .addRoles("Administrator")
                .build());
        roleConfigs = ImmutableList.<RoleConfig>of(ImmutableRoleConfig.builder()
                .name("Administrator")
                .addPermissions("agent:transaction", "agent:error", "agent:jvm",
                        "agent:config:view", "agent:config:edit", "admin")
                .build());
        webConfig = ImmutableEmbeddedWebConfig.builder().build();
        storageConfig = ImmutableEmbeddedStorageConfig.builder().build();
        smtpConfig = ImmutableSmtpConfig.builder().build();
        ldapConfig = ImmutableLdapConfig.builder().build();
        writeAll();
    }

    private void writeAll() throws IOException {
        // linked hash map to preserve ordering when writing to config file
        Map<String, Object> configs = Maps.newLinkedHashMap();
        configs.put(GENERAL_KEY, generalConfig);
        configs.put(USERS_KEY, userConfigs);
        configs.put(ROLES_KEY, roleConfigs);
        configs.put(WEB_KEY, webConfig);
        configs.put(STORAGE_KEY, storageConfig);
        configs.put(SMTP_KEY, smtpConfig);
        configs.put(LDAP_KEY, ldapConfig);
        configService.updateAdminConfigs(configs);
    }

    private static void checkVersionsEqual(String version, String priorVersion)
            throws OptimisticLockException {
        if (!version.equals(priorVersion)) {
            throw new OptimisticLockException();
        }
    }

    private static EmbeddedStorageConfig withCorrectedLists(EmbeddedStorageConfig config) {
        EmbeddedStorageConfig defaultConfig = ImmutableEmbeddedStorageConfig.builder().build();
        ImmutableList<Integer> rollupExpirationHours =
                fix(config.rollupExpirationHours(), defaultConfig.rollupExpirationHours());
        ImmutableList<Integer> rollupCappedDatabaseSizesMb = fix(
                config.rollupCappedDatabaseSizesMb(), defaultConfig.rollupCappedDatabaseSizesMb());
        return ImmutableEmbeddedStorageConfig.builder()
                .copyFrom(config)
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
