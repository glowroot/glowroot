/*
 * Copyright 2015-2018 the original author or authors.
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

import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.BaseEncoding;

import org.glowroot.central.repo.AgentConfigDao.AgentConfigUpdater;
import org.glowroot.common.config.CentralAdminGeneralConfig;
import org.glowroot.common.config.CentralStorageConfig;
import org.glowroot.common.config.CentralWebConfig;
import org.glowroot.common.config.ConfigDefaults;
import org.glowroot.common.config.EmbeddedAdminGeneralConfig;
import org.glowroot.common.config.EmbeddedStorageConfig;
import org.glowroot.common.config.EmbeddedWebConfig;
import org.glowroot.common.config.HealthchecksIoConfig;
import org.glowroot.common.config.HttpProxyConfig;
import org.glowroot.common.config.ImmutableCentralAdminGeneralConfig;
import org.glowroot.common.config.ImmutableCentralStorageConfig;
import org.glowroot.common.config.ImmutableCentralWebConfig;
import org.glowroot.common.config.ImmutableEmbeddedStorageConfig;
import org.glowroot.common.config.ImmutableHttpProxyConfig;
import org.glowroot.common.config.ImmutableLdapConfig;
import org.glowroot.common.config.ImmutablePagerDutyConfig;
import org.glowroot.common.config.ImmutableSmtpConfig;
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
import org.glowroot.common.util.Versions;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AdvancedConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.GaugeConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.GeneralConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.InstrumentationConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.JvmConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginProperty;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginProperty.Value.ValCase;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.SyntheticMonitorConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.TransactionConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.UiConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.UserRecordingConfig;

import static com.google.common.base.Preconditions.checkState;

public class ConfigRepositoryImpl implements ConfigRepository {

    // TODO this needs to be in sync with agents, so have agents pick up value from central
    private static final long GAUGE_COLLECTION_INTERVAL_MILLIS =
            Long.getLong("glowroot.internal.gaugeCollectionIntervalMillis", 5000);

    private final CentralConfigDao centralConfigDao;
    private final AgentConfigDao agentConfigDao;
    private final UserDao userDao;
    private final RoleDao roleDao;

    private final ImmutableList<RollupConfig> rollupConfigs;

    private final LazySecretKey lazySecretKey;

    private final Set<AgentConfigListener> agentConfigListeners = Sets.newCopyOnWriteArraySet();

    ConfigRepositoryImpl(CentralConfigDao centralConfigDao, AgentConfigDao agentConfigDao,
            UserDao userDao, RoleDao roleDao, String symmetricEncryptionKey) {
        this.centralConfigDao = centralConfigDao;
        this.agentConfigDao = agentConfigDao;
        this.userDao = userDao;
        this.roleDao = roleDao;
        rollupConfigs = ImmutableList.copyOf(RollupConfig.buildRollupConfigs());
        lazySecretKey = new LazySecretKeyImpl(symmetricEncryptionKey);

        centralConfigDao.addKeyType(GENERAL_KEY, ImmutableCentralAdminGeneralConfig.class);
        centralConfigDao.addKeyType(WEB_KEY, ImmutableCentralWebConfig.class);
        centralConfigDao.addKeyType(STORAGE_KEY, ImmutableCentralStorageConfig.class);
        centralConfigDao.addKeyType(SMTP_KEY, ImmutableSmtpConfig.class);
        centralConfigDao.addKeyType(HTTP_PROXY_KEY, ImmutableHttpProxyConfig.class);
        centralConfigDao.addKeyType(LDAP_KEY, ImmutableLdapConfig.class);
        centralConfigDao.addKeyType(PAGER_DUTY_KEY, ImmutablePagerDutyConfig.class);
    }

    @Override
    public GeneralConfig getGeneralConfig(String agentRollupId) throws Exception {
        AgentConfig agentConfig = agentConfigDao.read(agentRollupId);
        if (agentConfig == null) {
            throw new AgentConfigNotFoundException(agentRollupId);
        }
        return agentConfig.getGeneralConfig();
    }

    @Override
    public TransactionConfig getTransactionConfig(String agentId) throws Exception {
        AgentConfig agentConfig = agentConfigDao.read(agentId);
        if (agentConfig == null) {
            throw new AgentConfigNotFoundException(agentId);
        }
        return agentConfig.getTransactionConfig();
    }

    @Override
    public JvmConfig getJvmConfig(String agentId) throws Exception {
        AgentConfig agentConfig = agentConfigDao.read(agentId);
        if (agentConfig == null) {
            throw new AgentConfigNotFoundException(agentId);
        }
        return agentConfig.getJvmConfig();
    }

    // central supports ui config on rollups
    @Override
    public UiConfig getUiConfig(String agentRollupId) throws Exception {
        AgentConfig agentConfig = agentConfigDao.read(agentRollupId);
        if (agentConfig == null) {
            throw new AgentConfigNotFoundException(agentRollupId);
        }
        return agentConfig.getUiConfig();
    }

    @Override
    public UserRecordingConfig getUserRecordingConfig(String agentId) throws Exception {
        AgentConfig agentConfig = agentConfigDao.read(agentId);
        if (agentConfig == null) {
            throw new AgentConfigNotFoundException(agentId);
        }
        return agentConfig.getUserRecordingConfig();
    }

    // central supports advanced config on rollups
    // (maxAggregateQueriesPerType and maxAggregateServiceCallsPerType)
    @Override
    public AdvancedConfig getAdvancedConfig(String agentRollupId) throws Exception {
        AgentConfig agentConfig = agentConfigDao.read(agentRollupId);
        if (agentConfig == null) {
            throw new AgentConfigNotFoundException(agentRollupId);
        }
        return agentConfig.getAdvancedConfig();
    }

    @Override
    public List<GaugeConfig> getGaugeConfigs(String agentId) throws Exception {
        AgentConfig agentConfig = agentConfigDao.read(agentId);
        if (agentConfig == null) {
            throw new AgentConfigNotFoundException(agentId);
        }
        return agentConfig.getGaugeConfigList();
    }

    @Override
    public GaugeConfig getGaugeConfig(String agentId, String configVersion) throws Exception {
        for (GaugeConfig config : getGaugeConfigs(agentId)) {
            if (Versions.getVersion(config).equals(configVersion)) {
                return config;
            }
        }
        throw new IllegalStateException("Gauge config not found: " + configVersion);
    }

    // central supports synthetic monitor configs on rollups
    @Override
    public List<SyntheticMonitorConfig> getSyntheticMonitorConfigs(String agentRollupId)
            throws Exception {
        AgentConfig agentConfig = agentConfigDao.read(agentRollupId);
        if (agentConfig == null) {
            throw new AgentConfigNotFoundException(agentRollupId);
        }
        return agentConfig.getSyntheticMonitorConfigList();
    }

    // central supports synthetic monitor configs on rollups
    @Override
    public @Nullable SyntheticMonitorConfig getSyntheticMonitorConfig(String agentRollupId,
            String syntheticMonitorId) throws Exception {
        for (SyntheticMonitorConfig config : getSyntheticMonitorConfigs(agentRollupId)) {
            if (config.getId().equals(syntheticMonitorId)) {
                return config;
            }
        }
        return null;
    }

    // central supports alert configs on rollups
    @Override
    public List<AlertConfig> getAlertConfigs(String agentRollupId) throws Exception {
        AgentConfig agentConfig = agentConfigDao.read(agentRollupId);
        if (agentConfig == null) {
            throw new AgentConfigNotFoundException(agentRollupId);
        }
        return agentConfig.getAlertConfigList();
    }

    // central supports alert configs on rollups
    public List<AlertConfig> getAlertConfigsForSyntheticMonitorId(String agentRollupId,
            String syntheticMonitorId) throws Exception {
        List<AlertConfig> configs = Lists.newArrayList();
        for (AlertConfig config : getAlertConfigs(agentRollupId)) {
            AlertCondition alertCondition = config.getCondition();
            if (alertCondition.getValCase() == AlertCondition.ValCase.SYNTHETIC_MONITOR_CONDITION
                    && alertCondition.getSyntheticMonitorCondition().getSyntheticMonitorId()
                            .equals(syntheticMonitorId)) {
                configs.add(config);
            }
        }
        return configs;
    }

    // central supports alert configs on rollups
    @Override
    public @Nullable AlertConfig getAlertConfig(String agentRollupId, String configVersion)
            throws Exception {
        for (AlertConfig config : getAlertConfigs(agentRollupId)) {
            if (Versions.getVersion(config).equals(configVersion)) {
                return config;
            }
        }
        throw new IllegalStateException("Alert config not found: " + configVersion);
    }

    @Override
    public List<PluginConfig> getPluginConfigs(String agentId) throws Exception {
        AgentConfig agentConfig = agentConfigDao.read(agentId);
        if (agentConfig == null) {
            throw new AgentConfigNotFoundException(agentId);
        }
        return agentConfig.getPluginConfigList();
    }

    @Override
    public PluginConfig getPluginConfig(String agentId, String pluginId) throws Exception {
        for (PluginConfig config : getPluginConfigs(agentId)) {
            if (config.getId().equals(pluginId)) {
                return config;
            }
        }
        throw new IllegalStateException("Plugin config not found: " + pluginId);
    }

    @Override
    public List<InstrumentationConfig> getInstrumentationConfigs(String agentId) throws Exception {
        AgentConfig agentConfig = agentConfigDao.read(agentId);
        if (agentConfig == null) {
            throw new AgentConfigNotFoundException(agentId);
        }
        return agentConfig.getInstrumentationConfigList();
    }

    @Override
    public InstrumentationConfig getInstrumentationConfig(String agentId, String configVersion)
            throws Exception {
        for (InstrumentationConfig config : getInstrumentationConfigs(agentId)) {
            if (Versions.getVersion(config).equals(configVersion)) {
                return config;
            }
        }
        throw new IllegalStateException("Instrumentation config not found: " + configVersion);
    }

    @Override
    public EmbeddedAdminGeneralConfig getEmbeddedAdminGeneralConfig() {
        throw new UnsupportedOperationException();
    }

    @Override
    public CentralAdminGeneralConfig getCentralAdminGeneralConfig() throws Exception {
        CentralAdminGeneralConfig config =
                (CentralAdminGeneralConfig) centralConfigDao.read(GENERAL_KEY);
        if (config == null) {
            return ImmutableCentralAdminGeneralConfig.builder().build();
        }
        return config;
    }

    @Override
    public List<UserConfig> getUserConfigs() throws Exception {
        return userDao.read();
    }

    @Override
    public UserConfig getUserConfig(String username) throws Exception {
        UserConfig config = userDao.read(username);
        if (config == null) {
            throw new UserNotFoundException();
        }
        return config;
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
        return getCentralWebConfig();
    }

    @Override
    public EmbeddedWebConfig getEmbeddedWebConfig() throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    public CentralWebConfig getCentralWebConfig() throws Exception {
        CentralWebConfig config = (CentralWebConfig) centralConfigDao.read(WEB_KEY);
        if (config == null) {
            return ImmutableCentralWebConfig.builder().build();
        }
        return config;
    }

    @Override
    public StorageConfig getStorageConfig() throws Exception {
        return getCentralStorageConfig();
    }

    @Override
    public EmbeddedStorageConfig getEmbeddedStorageConfig() {
        throw new UnsupportedOperationException();
    }

    @Override
    public CentralStorageConfig getCentralStorageConfig() throws Exception {
        CentralStorageConfig config = (CentralStorageConfig) centralConfigDao.read(STORAGE_KEY);
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
        SmtpConfig config = (SmtpConfig) centralConfigDao.read(SMTP_KEY);
        if (config == null) {
            return ImmutableSmtpConfig.builder().build();
        }
        return config;
    }

    @Override
    public HttpProxyConfig getHttpProxyConfig() throws Exception {
        HttpProxyConfig config = (HttpProxyConfig) centralConfigDao.read(HTTP_PROXY_KEY);
        if (config == null) {
            return ImmutableHttpProxyConfig.builder().build();
        }
        return config;
    }

    @Override
    public LdapConfig getLdapConfig() throws Exception {
        LdapConfig config = (LdapConfig) centralConfigDao.read(LDAP_KEY);
        if (config == null) {
            return ImmutableLdapConfig.builder().build();
        }
        return config;
    }

    @Override
    public PagerDutyConfig getPagerDutyConfig() throws Exception {
        PagerDutyConfig config = (PagerDutyConfig) centralConfigDao.read(PAGER_DUTY_KEY);
        if (config == null) {
            return ImmutablePagerDutyConfig.builder().build();
        }
        return config;
    }

    @Override
    public HealthchecksIoConfig getHealthchecksIoConfig() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateGeneralConfig(String agentId, GeneralConfig config, String priorVersion)
            throws Exception {
        agentConfigDao.update(agentId, new AgentConfigUpdater() {
            @Override
            public AgentConfig updateAgentConfig(AgentConfig agentConfig) throws Exception {
                String existingVersion = Versions.getVersion(agentConfig.getGeneralConfig());
                if (!priorVersion.equals(existingVersion)) {
                    throw new OptimisticLockException();
                }
                return agentConfig.toBuilder()
                        .setGeneralConfig(config)
                        .build();
            }
        });
        notifyAgentConfigListeners(agentId);
    }

    @Override
    public void updateTransactionConfig(String agentId, TransactionConfig config,
            String priorVersion) throws Exception {
        agentConfigDao.update(agentId, new AgentConfigUpdater() {
            @Override
            public AgentConfig updateAgentConfig(AgentConfig agentConfig) throws Exception {
                String existingVersion = Versions.getVersion(agentConfig.getTransactionConfig());
                if (!priorVersion.equals(existingVersion)) {
                    throw new OptimisticLockException();
                }
                return agentConfig.toBuilder()
                        .setTransactionConfig(config)
                        .build();
            }
        });
        notifyAgentConfigListeners(agentId);
    }

    @Override
    public void insertGaugeConfig(String agentId, GaugeConfig config) throws Exception {
        agentConfigDao.update(agentId, new AgentConfigUpdater() {
            @Override
            public AgentConfig updateAgentConfig(AgentConfig agentConfig) throws Exception {
                // check for duplicate mbeanObjectName
                for (GaugeConfig loopConfig : agentConfig.getGaugeConfigList()) {
                    if (loopConfig.getMbeanObjectName().equals(config.getMbeanObjectName())) {
                        throw new DuplicateMBeanObjectNameException();
                    }
                }
                // no need to check for exact match since redundant with dup mbean object name check
                return agentConfig.toBuilder()
                        .addGaugeConfig(config)
                        .build();
            }
        });
        notifyAgentConfigListeners(agentId);
    }

    @Override
    public void updateGaugeConfig(String agentId, GaugeConfig config, String priorVersion)
            throws Exception {
        agentConfigDao.update(agentId, new AgentConfigUpdater() {
            @Override
            public AgentConfig updateAgentConfig(AgentConfig agentConfig) throws Exception {
                List<GaugeConfig> existingConfigs =
                        Lists.newArrayList(agentConfig.getGaugeConfigList());
                ListIterator<GaugeConfig> i = existingConfigs.listIterator();
                boolean found = false;
                while (i.hasNext()) {
                    GaugeConfig loopConfig = i.next();
                    String loopVersion = Versions.getVersion(loopConfig);
                    if (loopVersion.equals(priorVersion)) {
                        i.set(config);
                        found = true;
                    } else if (loopConfig.getMbeanObjectName()
                            .equals(config.getMbeanObjectName())) {
                        throw new DuplicateMBeanObjectNameException();
                    }
                    // no need to check for exact match since redundant with dup mbean object name
                    // check
                }
                if (!found) {
                    throw new OptimisticLockException();
                }
                return agentConfig.toBuilder()
                        .clearGaugeConfig()
                        .addAllGaugeConfig(existingConfigs)
                        .build();
            }
        });
        notifyAgentConfigListeners(agentId);
    }

    @Override
    public void deleteGaugeConfig(String agentId, String version) throws Exception {
        agentConfigDao.update(agentId, new AgentConfigUpdater() {
            @Override
            public AgentConfig updateAgentConfig(AgentConfig agentConfig) throws Exception {
                List<GaugeConfig> existingConfigs =
                        Lists.newArrayList(agentConfig.getGaugeConfigList());
                ListIterator<GaugeConfig> i = existingConfigs.listIterator();
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
                return agentConfig.toBuilder()
                        .clearGaugeConfig()
                        .addAllGaugeConfig(existingConfigs)
                        .build();
            }
        });
        notifyAgentConfigListeners(agentId);
    }

    @Override
    public void updateJvmConfig(String agentId, JvmConfig config, String priorVersion)
            throws Exception {
        agentConfigDao.update(agentId, new AgentConfigUpdater() {
            @Override
            public AgentConfig updateAgentConfig(AgentConfig agentConfig) throws Exception {
                String existingVersion = Versions.getVersion(agentConfig.getJvmConfig());
                if (!priorVersion.equals(existingVersion)) {
                    throw new OptimisticLockException();
                }
                return agentConfig.toBuilder()
                        .setJvmConfig(config)
                        .build();
            }
        });
        notifyAgentConfigListeners(agentId);
    }

    // central supports synthetic monitor configs on rollups
    @Override
    public String insertSyntheticMonitorConfig(String agentRollupId,
            SyntheticMonitorConfig configWithoutId) throws Exception {
        checkState(configWithoutId.getId().isEmpty());
        SyntheticMonitorConfig config = configWithoutId.toBuilder()
                .setId(AgentConfigDao.generateNewId())
                .build();
        agentConfigDao.update(agentRollupId, new AgentConfigUpdater() {
            @Override
            public AgentConfig updateAgentConfig(AgentConfig agentConfig) throws Exception {
                // check for duplicate display
                String display = ConfigDefaults.getDisplayOrDefault(config);
                for (SyntheticMonitorConfig loopConfig : agentConfig
                        .getSyntheticMonitorConfigList()) {
                    if (ConfigDefaults.getDisplayOrDefault(loopConfig).equals(display)) {
                        throw new DuplicateSyntheticMonitorDisplayException();
                    }
                }
                // no need to check for exact match since redundant with duplicate name check
                return agentConfig.toBuilder()
                        .addSyntheticMonitorConfig(config)
                        .build();
            }
        });
        notifyAgentConfigListeners(agentRollupId);
        return config.getId();
    }

    // central supports synthetic monitor configs on rollups
    @Override
    public void updateSyntheticMonitorConfig(String agentRollupId, SyntheticMonitorConfig config,
            String priorVersion) throws Exception {
        agentConfigDao.update(agentRollupId, new AgentConfigUpdater() {
            @Override
            public AgentConfig updateAgentConfig(AgentConfig agentConfig) throws Exception {
                List<SyntheticMonitorConfig> existingConfigs =
                        Lists.newArrayList(agentConfig.getSyntheticMonitorConfigList());
                ListIterator<SyntheticMonitorConfig> i = existingConfigs.listIterator();
                boolean found = false;
                String display = config.getDisplay();
                while (i.hasNext()) {
                    SyntheticMonitorConfig loopConfig = i.next();
                    if (loopConfig.getId().equals(config.getId())) {
                        if (!Versions.getVersion(loopConfig).equals(priorVersion)) {
                            throw new OptimisticLockException();
                        }
                        i.set(config);
                        found = true;
                    } else if (ConfigDefaults.getDisplayOrDefault(loopConfig).equals(display)) {
                        throw new DuplicateSyntheticMonitorDisplayException();
                    }
                }
                if (!found) {
                    throw new SyntheticNotFoundException();
                }
                return agentConfig.toBuilder()
                        .clearSyntheticMonitorConfig()
                        .addAllSyntheticMonitorConfig(existingConfigs)
                        .build();
            }
        });
        notifyAgentConfigListeners(agentRollupId);
    }

    // central supports synthetic monitor configs on rollups
    @Override
    public void deleteSyntheticMonitorConfig(String agentRollupId, String syntheticMonitorId)
            throws Exception {
        agentConfigDao.update(agentRollupId, new AgentConfigUpdater() {
            @Override
            public AgentConfig updateAgentConfig(AgentConfig agentConfig) throws Exception {
                if (!getAlertConfigsForSyntheticMonitorId(agentRollupId, syntheticMonitorId)
                        .isEmpty()) {
                    throw new IllegalStateException(
                            "Cannot delete synthetic monitor is being used by active alert");
                }
                List<SyntheticMonitorConfig> existingConfigs =
                        Lists.newArrayList(agentConfig.getSyntheticMonitorConfigList());
                ListIterator<SyntheticMonitorConfig> i = existingConfigs.listIterator();
                boolean found = false;
                while (i.hasNext()) {
                    if (i.next().getId().equals(syntheticMonitorId)) {
                        i.remove();
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    throw new OptimisticLockException();
                }
                return agentConfig.toBuilder()
                        .clearSyntheticMonitorConfig()
                        .addAllSyntheticMonitorConfig(existingConfigs)
                        .build();
            }
        });
        notifyAgentConfigListeners(agentRollupId);
    }

    // central supports alert configs on rollups
    @Override
    public void insertAlertConfig(String agentRollupId, AlertConfig config) throws Exception {
        agentConfigDao.update(agentRollupId, new AgentConfigUpdater() {
            @Override
            public AgentConfig updateAgentConfig(AgentConfig agentConfig) throws Exception {
                for (AlertConfig loopConfig : agentConfig.getAlertConfigList()) {
                    if (loopConfig.getCondition().equals(config.getCondition())) {
                        throw new IllegalStateException(
                                "This exact alert condition already exists");
                    }
                }
                return agentConfig.toBuilder()
                        .addAlertConfig(config)
                        .build();
            }
        });
        notifyAgentConfigListeners(agentRollupId);
    }

    // central supports alert configs on rollups
    @Override
    public void updateAlertConfig(String agentRollupId, AlertConfig config, String priorVersion)
            throws Exception {
        agentConfigDao.update(agentRollupId, new AgentConfigUpdater() {
            @Override
            public AgentConfig updateAgentConfig(AgentConfig agentConfig) throws Exception {
                List<AlertConfig> existingConfigs =
                        Lists.newArrayList(agentConfig.getAlertConfigList());
                ListIterator<AlertConfig> i = existingConfigs.listIterator();
                boolean found = false;
                while (i.hasNext()) {
                    AlertConfig loopConfig = i.next();
                    if (Versions.getVersion(loopConfig).equals(priorVersion)) {
                        i.set(config);
                        found = true;
                    } else if (loopConfig.getCondition().equals(config.getCondition())) {
                        throw new IllegalStateException(
                                "This exact alert condition already exists");
                    }
                }
                if (!found) {
                    throw new AlertNotFoundException();
                }
                return agentConfig.toBuilder()
                        .clearAlertConfig()
                        .addAllAlertConfig(existingConfigs)
                        .build();
            }
        });
        notifyAgentConfigListeners(agentRollupId);
    }

    // central supports alert configs on rollups
    @Override
    public void deleteAlertConfig(String agentRollupId, String version) throws Exception {
        agentConfigDao.update(agentRollupId, new AgentConfigUpdater() {
            @Override
            public AgentConfig updateAgentConfig(AgentConfig agentConfig) throws Exception {
                List<AlertConfig> existingConfigs =
                        Lists.newArrayList(agentConfig.getAlertConfigList());
                ListIterator<AlertConfig> i = existingConfigs.listIterator();
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
                return agentConfig.toBuilder()
                        .clearAlertConfig()
                        .addAllAlertConfig(existingConfigs)
                        .build();
            }
        });
        notifyAgentConfigListeners(agentRollupId);
    }

    // central supports ui config on rollups
    @Override
    public void updateUiConfig(String agentRollupId, UiConfig config, String priorVersion)
            throws Exception {
        agentConfigDao.update(agentRollupId, new AgentConfigUpdater() {
            @Override
            public AgentConfig updateAgentConfig(AgentConfig agentConfig) throws Exception {
                String existingVersion = Versions.getVersion(agentConfig.getUiConfig());
                if (!priorVersion.equals(existingVersion)) {
                    throw new OptimisticLockException();
                }
                return agentConfig.toBuilder()
                        .setUiConfig(config)
                        .build();
            }
        });
        notifyAgentConfigListeners(agentRollupId);
    }

    @Override
    public void updatePluginConfig(String agentId, String pluginId,
            List<PluginProperty> properties, String priorVersion) throws Exception {
        agentConfigDao.update(agentId, new AgentConfigUpdater() {
            @Override
            public AgentConfig updateAgentConfig(AgentConfig agentConfig) throws Exception {
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
                return agentConfig.toBuilder()
                        .clearPluginConfig()
                        .addAllPluginConfig(pluginConfigs)
                        .build();
            }
        });
        notifyAgentConfigListeners(agentId);
    }

    @Override
    public void insertInstrumentationConfig(String agentId, InstrumentationConfig config)
            throws Exception {
        agentConfigDao.update(agentId, new AgentConfigUpdater() {
            @Override
            public AgentConfig updateAgentConfig(AgentConfig agentConfig) throws Exception {
                if (agentConfig.getInstrumentationConfigList().contains(config)) {
                    throw new IllegalStateException("This exact instrumentation already exists");
                }
                return agentConfig.toBuilder()
                        .addInstrumentationConfig(config)
                        .build();
            }
        });
        notifyAgentConfigListeners(agentId);
    }

    @Override
    public void updateInstrumentationConfig(String agentId, InstrumentationConfig config,
            String priorVersion) throws Exception {
        agentConfigDao.update(agentId, new AgentConfigUpdater() {
            @Override
            public AgentConfig updateAgentConfig(AgentConfig agentConfig) throws Exception {
                String newVersion = Versions.getVersion(config);
                List<InstrumentationConfig> existingConfigs =
                        Lists.newArrayList(agentConfig.getInstrumentationConfigList());
                ListIterator<InstrumentationConfig> i = existingConfigs.listIterator();
                boolean found = false;
                while (i.hasNext()) {
                    String loopVersion = Versions.getVersion(i.next());
                    if (loopVersion.equals(priorVersion)) {
                        i.set(config);
                        found = true;
                    } else if (loopVersion.equals(newVersion)) {
                        throw new IllegalStateException(
                                "This exact instrumentation already exists");
                    }
                }
                if (!found) {
                    throw new OptimisticLockException();
                }
                return agentConfig.toBuilder()
                        .clearInstrumentationConfig()
                        .addAllInstrumentationConfig(existingConfigs)
                        .build();
            }
        });
        notifyAgentConfigListeners(agentId);
    }

    @Override
    public void deleteInstrumentationConfigs(String agentId, List<String> versions)
            throws Exception {
        agentConfigDao.update(agentId, new AgentConfigUpdater() {
            @Override
            public AgentConfig updateAgentConfig(AgentConfig agentConfig) throws Exception {
                List<InstrumentationConfig> existingConfigs =
                        Lists.newArrayList(agentConfig.getInstrumentationConfigList());
                ListIterator<InstrumentationConfig> i = existingConfigs.listIterator();
                List<String> remainingVersions = Lists.newArrayList(versions);
                while (i.hasNext()) {
                    String currVersion = Versions.getVersion(i.next());
                    if (remainingVersions.contains(currVersion)) {
                        i.remove();
                        remainingVersions.remove(currVersion);
                    }
                }
                if (!remainingVersions.isEmpty()) {
                    throw new OptimisticLockException();
                }
                return agentConfig.toBuilder()
                        .clearInstrumentationConfig()
                        .addAllInstrumentationConfig(existingConfigs)
                        .build();
            }
        });
        notifyAgentConfigListeners(agentId);
    }

    // ignores any instrumentation configs that are duplicates of existing instrumentation configs
    @Override
    public void insertInstrumentationConfigs(String agentId, List<InstrumentationConfig> configs)
            throws Exception {
        agentConfigDao.update(agentId, new AgentConfigUpdater() {
            @Override
            public AgentConfig updateAgentConfig(AgentConfig agentConfig) throws Exception {
                AgentConfig.Builder builder = agentConfig.toBuilder();
                List<InstrumentationConfig> existingConfigs =
                        Lists.newArrayList(agentConfig.getInstrumentationConfigList());
                for (InstrumentationConfig config : configs) {
                    if (!existingConfigs.contains(config)) {
                        existingConfigs.add(config);
                    }
                }
                return builder.clearInstrumentationConfig()
                        .addAllInstrumentationConfig(existingConfigs)
                        .build();
            }
        });
        notifyAgentConfigListeners(agentId);
    }

    @Override
    public void updateUserRecordingConfig(String agentId, UserRecordingConfig config,
            String priorVersion) throws Exception {
        agentConfigDao.update(agentId, new AgentConfigUpdater() {
            @Override
            public AgentConfig updateAgentConfig(AgentConfig agentConfig) throws Exception {
                String existingVersion = Versions.getVersion(agentConfig.getUserRecordingConfig());
                if (!priorVersion.equals(existingVersion)) {
                    throw new OptimisticLockException();
                }
                return agentConfig.toBuilder()
                        .setUserRecordingConfig(config)
                        .build();
            }
        });
        notifyAgentConfigListeners(agentId);
    }

    @Override
    public void updateAdvancedConfig(String agentRollupId, AdvancedConfig config,
            String priorVersion) throws Exception {
        agentConfigDao.update(agentRollupId, new AgentConfigUpdater() {
            @Override
            public AgentConfig updateAgentConfig(AgentConfig agentConfig)
                    throws OptimisticLockException {
                String existingVersion = Versions.getVersion(agentConfig.getAdvancedConfig());
                if (!priorVersion.equals(existingVersion)) {
                    throw new OptimisticLockException();
                }
                return agentConfig.toBuilder()
                        .setAdvancedConfig(config)
                        .build();
            }
        });
        notifyAgentConfigListeners(agentRollupId);
    }

    @Override
    public void updateEmbeddedAdminGeneralConfig(EmbeddedAdminGeneralConfig config,
            String priorVersion) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateCentralAdminGeneralConfig(CentralAdminGeneralConfig config,
            String priorVersion) throws Exception {
        centralConfigDao.write(GENERAL_KEY, config, priorVersion);
    }

    @Override
    public void insertUserConfig(UserConfig config) throws Exception {
        // check for case-insensitive duplicate
        String username = config.username();
        for (UserConfig loopConfig : userDao.read()) {
            if (loopConfig.username().equalsIgnoreCase(username)) {
                throw new DuplicateUsernameException();
            }
        }
        userDao.insertIfNotExists(config);
    }

    @Override
    public void updateUserConfig(UserConfig config, String priorVersion) throws Exception {
        UserConfig existingConfig = userDao.read(config.username());
        if (existingConfig == null) {
            throw new UserNotFoundException();
        }
        if (!existingConfig.version().equals(priorVersion)) {
            throw new OptimisticLockException();
        }
        userDao.insert(config);
    }

    @Override
    public void deleteUserConfig(String username) throws Exception {
        boolean found = false;
        List<UserConfig> configs = userDao.read();
        for (UserConfig config : configs) {
            if (config.username().equalsIgnoreCase(username)) {
                found = true;
                break;
            }
        }
        if (!found) {
            throw new UserNotFoundException();
        }
        if (getSmtpConfig().host().isEmpty() && configs.size() == 1) {
            throw new CannotDeleteLastUserException();
        }
        userDao.delete(username);
    }

    @Override
    public void insertRoleConfig(RoleConfig config) throws Exception {
        // check for case-insensitive duplicate
        String name = config.name();
        for (RoleConfig loopConfig : roleDao.read()) {
            if (loopConfig.name().equalsIgnoreCase(name)) {
                throw new DuplicateRoleNameException();
            }
        }
        roleDao.insertIfNotExists(config);
    }

    @Override
    public void updateRoleConfig(RoleConfig config, String priorVersion) throws Exception {
        RoleConfig existingConfig = roleDao.read(config.name());
        if (existingConfig == null) {
            throw new RoleNotFoundException();
        }
        if (!existingConfig.version().equals(priorVersion)) {
            throw new OptimisticLockException();
        }
        roleDao.insert(config);
    }

    @Override
    public void deleteRoleConfig(String name) throws Exception {
        boolean found = false;
        List<RoleConfig> configs = roleDao.read();
        for (RoleConfig config : configs) {
            if (config.name().equalsIgnoreCase(name)) {
                found = true;
                break;
            }
        }
        if (!found) {
            throw new RoleNotFoundException();
        }
        if (configs.size() == 1) {
            throw new CannotDeleteLastRoleException();
        }
        roleDao.delete(name);
    }

    @Override
    public void updateEmbeddedWebConfig(EmbeddedWebConfig config, String priorVersion)
            throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateCentralWebConfig(CentralWebConfig config, String priorVersion)
            throws Exception {
        centralConfigDao.write(WEB_KEY, config, priorVersion);
    }

    @Override
    public void updateEmbeddedStorageConfig(EmbeddedStorageConfig config, String priorVersion) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateCentralStorageConfig(CentralStorageConfig config, String priorVersion)
            throws Exception {
        centralConfigDao.write(STORAGE_KEY, config, priorVersion);
    }

    @Override
    public void updateSmtpConfig(SmtpConfig config, String priorVersion) throws Exception {
        centralConfigDao.write(SMTP_KEY, config, priorVersion);
    }

    @Override
    public void updateHttpProxyConfig(HttpProxyConfig config, String priorVersion)
            throws Exception {
        centralConfigDao.write(HTTP_PROXY_KEY, config, priorVersion);
    }

    @Override
    public void updateLdapConfig(LdapConfig config, String priorVersion) throws Exception {
        centralConfigDao.write(LDAP_KEY, config, priorVersion);
    }

    @Override
    public void updatePagerDutyConfig(PagerDutyConfig config, String priorVersion)
            throws Exception {
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
        centralConfigDao.write(PAGER_DUTY_KEY, config, priorVersion);
    }

    @Override
    public void updateHealthchecksIoConfig(HealthchecksIoConfig healthchecksIoConfig,
            String priorVersion) throws Exception {
        throw new UnsupportedOperationException();
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
    public LazySecretKey getLazySecretKey() throws Exception {
        return lazySecretKey;
    }

    public void addAgentConfigListener(AgentConfigListener listener) {
        agentConfigListeners.add(listener);
    }

    // the updated config is not passed to the listeners to avoid the race condition of multiple
    // config updates being sent out of order, instead listeners must call get*Config() which will
    // never return the updates out of order (at worst it may return the most recent update twice
    // which is ok)
    private void notifyAgentConfigListeners(String agentRollupId) throws Exception {
        for (AgentConfigListener agentConfigListener : agentConfigListeners) {
            agentConfigListener.onChange(agentRollupId);
        }
    }

    private static PluginConfig buildPluginConfig(PluginConfig existingPluginConfig,
            List<PluginProperty> properties) {
        Map<String, PluginProperty> props = buildMutablePropertiesMap(properties);
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

    private static Map<String, PluginProperty> buildMutablePropertiesMap(
            List<PluginProperty> properties) {
        return Maps.newHashMap(Maps.uniqueIndex(properties, PluginProperty::getName));
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

    private static CentralStorageConfig withCorrectedLists(CentralStorageConfig config) {
        EmbeddedStorageConfig defaultConfig = ImmutableEmbeddedStorageConfig.builder().build();
        ImmutableList<Integer> rollupExpirationHours =
                fix(config.rollupExpirationHours(), defaultConfig.rollupExpirationHours());
        return ImmutableCentralStorageConfig.builder()
                .copyFrom(config)
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
        void onChange(String agentRollupId) throws Exception;
    }

    private static class LazySecretKeyImpl implements LazySecretKey {

        private final @Nullable SecretKey secretKey;

        private LazySecretKeyImpl(String symmetricEncryptionKey) {
            if (symmetricEncryptionKey.isEmpty()) {
                secretKey = null;
            } else {
                byte[] bytes = BaseEncoding.base16()
                        .decode(symmetricEncryptionKey.toUpperCase(Locale.ENGLISH));
                secretKey = new SecretKeySpec(bytes, "AES");
            }
        }

        @Override
        public @Nullable SecretKey getExisting() throws Exception {
            return secretKey;
        }

        @Override
        public SecretKey getOrCreate() throws Exception {
            if (secretKey == null) {
                throw new SymmetricEncryptionKeyMissingException();
            }
            return secretKey;
        }
    }
}
