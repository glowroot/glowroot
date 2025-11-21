/*
 * Copyright 2015-2023 the original author or authors.
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

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.BaseEncoding;
import com.spotify.futures.CompletableFutures;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.glowroot.central.repo.AgentConfigDao.AgentConfigUpdater;
import org.glowroot.common.util.Versions;
import org.glowroot.common2.config.*;
import org.glowroot.common2.config.PagerDutyConfig.PagerDutyIntegrationKey;
import org.glowroot.common2.config.SlackConfig.SlackWebhook;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.common2.repo.ConfigRepository;
import org.glowroot.common2.repo.ConfigValidation;
import org.glowroot.common2.repo.util.LazySecretKey;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.*;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginProperty.Value.ValCase;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

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
        centralConfigDao.addKeyType(SLACK_KEY, ImmutableSlackConfig.class);
    }

    @Override
    public GeneralConfig getGeneralConfig(String agentRollupId) throws Exception {
        AgentConfig agentConfig = agentConfigDao.readAsync(agentRollupId).get();
        if (agentConfig == null) {
            throw new AgentConfigNotFoundException(agentRollupId);
        }
        return agentConfig.getGeneralConfig();
    }

    @Override
    public TransactionConfig getTransactionConfig(String agentId) throws Exception {
        AgentConfig agentConfig = agentConfigDao.readAsync(agentId).get();
        if (agentConfig == null) {
            throw new AgentConfigNotFoundException(agentId);
        }
        return agentConfig.getTransactionConfig();
    }

    @Override
    public JvmConfig getJvmConfig(String agentId) throws Exception {
        AgentConfig agentConfig = agentConfigDao.readAsync(agentId).get();
        if (agentConfig == null) {
            throw new AgentConfigNotFoundException(agentId);
        }
        return agentConfig.getJvmConfig();
    }

    // central supports ui config on rollups
    @Override
    public UiDefaultsConfig getUiDefaultsConfig(String agentRollupId) throws Exception {
        AgentConfig agentConfig = agentConfigDao.readAsync(agentRollupId).get();
        if (agentConfig == null) {
            throw new AgentConfigNotFoundException(agentRollupId);
        }
        return agentConfig.getUiDefaultsConfig();
    }

    // central supports advanced config on rollups (maxQueryAggregates and maxServiceCallAggregates)
    @Override
    public CompletionStage<AdvancedConfig> getAdvancedConfig(String agentRollupId) {
        return agentConfigDao.readAsync(agentRollupId).thenApply(agentConfig -> {
            if (agentConfig == null) {
                return null;
            }
            return agentConfig.getAdvancedConfig();
        });
    }

    @Override
    public List<GaugeConfig> getGaugeConfigs(String agentId) throws Exception {
        AgentConfig agentConfig = agentConfigDao.readAsync(agentId).get();
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
    public CompletionStage<List<SyntheticMonitorConfig>> getSyntheticMonitorConfigs(String agentRollupId) {
        return agentConfigDao.readAsync(agentRollupId).thenApply(agentConfig -> {
            if (agentConfig == null) {
                throw new AgentConfigNotFoundException(agentRollupId);
            }
            return agentConfig.getSyntheticMonitorConfigList();
        });
    }

    // central supports synthetic monitor configs on rollups
    @Override
    public CompletionStage<SyntheticMonitorConfig> getSyntheticMonitorConfig(String agentRollupId,
                                                                      String syntheticMonitorId) {
        return getSyntheticMonitorConfigs(agentRollupId).thenApply(configs -> {
            for (SyntheticMonitorConfig config : configs) {
                if (config.getId().equals(syntheticMonitorId)) {
                    return config;
                }
            }
            return null;
        });
    }

    // central supports alert configs on rollups
    @Override
    public CompletionStage<List<AlertConfig>> getAlertConfigs(String agentRollupId) {
        return agentConfigDao.readAsync(agentRollupId).thenApply(agentConfig -> {
            if (agentConfig == null) {
                throw new AgentConfigNotFoundException(agentRollupId);
            }
            return agentConfig.getAlertConfigList();
        });
    }

    @Override
    public CompletionStage<List<AlertConfig>> getAlertConfigsNonBlocking(String agentRollupId) {
        return agentConfigDao.readAsync(agentRollupId).thenApply(agentConfig -> {
            if (agentConfig == null) {
                throw new AgentConfigNotFoundException(agentRollupId);
            }
            return agentConfig.getAlertConfigList();
        });
    }

    // central supports alert configs on rollups
    public CompletionStage<List<AlertConfig>> getAlertConfigsForSyntheticMonitorId(String agentRollupId,
                                                                                   String syntheticMonitorId) {
        return getAlertConfigs(agentRollupId).thenApply(alertConfigs -> {
            List<AlertConfig> configs = new ArrayList<>();
            for (AlertConfig config : alertConfigs) {
                AlertCondition alertCondition = config.getCondition();
                if (alertCondition.getValCase() == AlertCondition.ValCase.SYNTHETIC_MONITOR_CONDITION
                        && alertCondition.getSyntheticMonitorCondition().getSyntheticMonitorId()
                        .equals(syntheticMonitorId)) {
                    configs.add(config);
                }
            }
            return configs;
        });
    }

    // central supports alert configs on rollups
    @Override
    public CompletionStage<AlertConfig> getAlertConfig(String agentRollupId, String configVersion) {
        return getAlertConfigs(agentRollupId).thenApply(alertConfigs -> {
            for (AlertConfig config : alertConfigs) {
                if (Versions.getVersion(config).equals(configVersion)) {
                    return config;
                }
            }
            throw new IllegalStateException("Alert config not found: " + configVersion);
        });
    }

    @Override
    public List<PluginConfig> getPluginConfigs(String agentId) throws Exception {
        AgentConfig agentConfig = agentConfigDao.readAsync(agentId).get();
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
        AgentConfig agentConfig = agentConfigDao.readAsync(agentId).get();
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
    public AgentConfig getAllConfig(String agentId) throws Exception {
        AgentConfig agentConfig = agentConfigDao.readAsync(agentId).get();
        if (agentConfig == null) {
            throw new AgentConfigNotFoundException(agentId);
        } else {
            return agentConfig;
        }
    }

    @Override
    public EmbeddedAdminGeneralConfig getEmbeddedAdminGeneralConfig() {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletionStage<CentralAdminGeneralConfig> getCentralAdminGeneralConfig() {
        return centralConfigDao.read(GENERAL_KEY).thenApply(conf -> {
            ImmutableCentralAdminGeneralConfig config = (ImmutableCentralAdminGeneralConfig) conf;
            if (config == null) {
                return ImmutableCentralAdminGeneralConfig.builder().build();
            }
            return config;
        });
    }

    @Override
    public CompletionStage<List<UserConfig>> getUserConfigs() {
        return userDao.read();
    }

    @Override
    public CompletionStage<UserConfig> getUserConfig(String username) {
        return userDao.read(username).thenCompose(config -> {
            if (config == null) {
                return CompletableFuture.failedFuture(new UserNotFoundException());
            }
            return CompletableFuture.completedFuture(config);
        });
    }

    @Override
    public CompletionStage<UserConfig> getUserConfigCaseInsensitive(String username) {
        return userDao.readCaseInsensitive(username);
    }

    @Override
    public CompletionStage<Boolean> namedUsersExist() {
        return userDao.namedUsersExist();
    }

    @Override
    public CompletionStage<List<RoleConfig>> getRoleConfigs() {
        return roleDao.read();
    }

    @Override
    public CompletionStage<RoleConfig> getRoleConfig(String name) {
        return roleDao.read(name);
    }

    @Override
    public CompletionStage<? extends WebConfig> getWebConfig() {
        return getCentralWebConfig();
    }

    @Override
    public EmbeddedWebConfig getEmbeddedWebConfig() {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletionStage<CentralWebConfig> getCentralWebConfig() {
        return centralConfigDao.read(WEB_KEY).thenApply(conf -> {
            CentralWebConfig config = (CentralWebConfig) conf;
            if (config == null) {
                return ImmutableCentralWebConfig.builder().build();
            }
            return config;
        });
    }

    @Override
    public StorageConfig getStorageConfig() {
        return getCentralStorageConfig().toCompletableFuture().join();
    }

    @Override
    public EmbeddedStorageConfig getEmbeddedStorageConfig() {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletionStage<CentralStorageConfig> getCentralStorageConfig() {
        return centralConfigDao.read(STORAGE_KEY).thenApply(conf -> {
            CentralStorageConfig config = (CentralStorageConfig) conf;
            if (config == null) {
                return ImmutableCentralStorageConfig.builder().build();
            }
            if (config.hasListIssues()) {
                return withCorrectedLists(config);
            }
            return config;
        });
    }

    @Override
    public CompletionStage<SmtpConfig> getSmtpConfig() {
        return centralConfigDao.read(SMTP_KEY).thenApply(conf -> {
            SmtpConfig config = (SmtpConfig) conf;
            if (config == null) {
                return ImmutableSmtpConfig.builder().build();
            }
            return config;
        });
    }

    @Override
    public CompletionStage<HttpProxyConfig> getHttpProxyConfig() {
        return centralConfigDao.read(HTTP_PROXY_KEY).thenApply(conf -> {
            HttpProxyConfig config = (HttpProxyConfig) conf;
            if (config == null) {
                return ImmutableHttpProxyConfig.builder().build();
            }
            return config;
        });
    }

    @Override
    public CompletionStage<LdapConfig> getLdapConfig() {
        return centralConfigDao.read(LDAP_KEY).thenApply(conf -> {
            LdapConfig config = (LdapConfig) conf;
            if (config == null) {
                return ImmutableLdapConfig.builder().build();
            }
            return config;
        });
    }

    @Override
    public CompletionStage<PagerDutyConfig> getPagerDutyConfig() {
        return centralConfigDao.read(PAGER_DUTY_KEY).thenApply(conf -> {
            PagerDutyConfig config = (PagerDutyConfig) conf;
            if (config == null) {
                return ImmutablePagerDutyConfig.builder().build();
            }
            return config;
        });
    }

    @Override
    public CompletionStage<SlackConfig> getSlackConfig() {
        return centralConfigDao.read(SLACK_KEY).thenApply(conf -> {
            SlackConfig config = (SlackConfig) conf;
            if (config == null) {
                return ImmutableSlackConfig.builder().build();
            }
            return config;
        });
    }

    @Override
    public HealthchecksIoConfig getHealthchecksIoConfig() {
        throw new UnsupportedOperationException();
    }

    @Override
    public AllEmbeddedAdminConfig getAllEmbeddedAdminConfig() {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletionStage<AllCentralAdminConfig> getAllCentralAdminConfig() {
        return getCentralAdminGeneralConfig().thenCompose(centralAdminGeneralConfig -> {
            return getUserConfigs().thenCompose(userConfigs -> {
                return getRoleConfigs().thenCompose(roleConfigs -> {
                    return getCentralWebConfig().thenCompose(centralWebConfig -> {
                        return getCentralStorageConfig().thenCompose(centralStorageConfig -> {
                            return getSmtpConfig().thenCompose(smtpConfig -> {
                                return getHttpProxyConfig().thenCompose(httpProxyConfig -> {
                                    return getLdapConfig().thenCompose(ldapConfig -> {
                                        return getPagerDutyConfig().thenCompose(pagerDutyConfig -> {
                                            return getSlackConfig().thenApply(slackConfig -> {

                                                ImmutableAllCentralAdminConfig.Builder builder = ImmutableAllCentralAdminConfig.builder()
                                                        .general((ImmutableCentralAdminGeneralConfig) centralAdminGeneralConfig);
                                                for (UserConfig userConfig : userConfigs) {
                                                    builder.addUsers(ImmutableUserConfig.copyOf(userConfig));
                                                }
                                                for (RoleConfig roleConfig : roleConfigs) {
                                                    builder.addRoles(ImmutableRoleConfig.copyOf(roleConfig));
                                                }
                                                return builder.web(ImmutableCentralWebConfig.copyOf(centralWebConfig))
                                                        .storage(ImmutableCentralStorageConfig.copyOf(centralStorageConfig))
                                                        .smtp(ImmutableSmtpConfig.copyOf(smtpConfig))
                                                        .httpProxy(ImmutableHttpProxyConfig.copyOf(httpProxyConfig))
                                                        .ldap(ImmutableLdapConfig.copyOf(ldapConfig))
                                                        .pagerDuty(ImmutablePagerDutyConfig.copyOf(pagerDutyConfig))
                                                        .slack(ImmutableSlackConfig.copyOf(slackConfig))
                                                        .build();
                                            });
                                        });
                                    });
                                });
                            });
                        });
                    });
                });
            });
        });
    }

    @Override
    public CompletionStage<Boolean> isConfigReadOnly(String agentId) {
        return agentConfigDao.readAsync(agentId).thenApply(agentConfig -> {
            if (agentConfig == null) {
                throw new AgentConfigNotFoundException(agentId);
            }
            return agentConfig.getConfigReadOnly();
        });
    }

    @Override
    public CompletionStage<?> updateGeneralConfig(String agentId, GeneralConfig config, String priorVersion, CassandraProfile profile) {
        return agentConfigDao.updateCentralOnly(agentId, new AgentConfigUpdater() {
            @Override
            public CompletionStage<AgentConfig> updateAgentConfig(AgentConfig agentConfig) {
                return CompletableFuture.completedFuture(null).thenApply(ignore -> {
                    String existingVersion = Versions.getVersion(agentConfig.getGeneralConfig());
                    if (!priorVersion.equals(existingVersion)) {
                        throw new OptimisticLockException();
                    }
                    return agentConfig.toBuilder()
                            .setGeneralConfig(config)
                            .build();
                });
            }
        }, profile);
        // no need to call notifyAgentConfigListeners since updating "central only" data
    }

    @Override
    public CompletionStage<?> updateTransactionConfig(String agentId, TransactionConfig config,
                                        String priorVersion, CassandraProfile profile) {
        return agentConfigDao.update(agentId, new AgentConfigUpdater() {
            @Override
            public CompletionStage<AgentConfig> updateAgentConfig(AgentConfig agentConfig) {
                return CompletableFuture.completedFuture(null).thenApply(ignore -> {
                    String existingVersion = Versions.getVersion(agentConfig.getTransactionConfig());
                    if (!priorVersion.equals(existingVersion)) {
                        throw new OptimisticLockException();
                    }
                    return agentConfig.toBuilder()
                            .setTransactionConfig(config)
                            .build();
                });
            }
        }, profile).thenRun(() -> {
            notifyAgentConfigListeners(agentId);
        });
    }

    @Override
    public CompletionStage<?> insertGaugeConfig(String agentId, GaugeConfig config, CassandraProfile profile) {
        return agentConfigDao.update(agentId, new AgentConfigUpdater() {
            @Override
            public CompletionStage<AgentConfig> updateAgentConfig(AgentConfig agentConfig) {
                return CompletableFuture.completedFuture(null).thenApply(ignore -> {
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
                });
            }
        }, profile).thenRun(() -> {
            notifyAgentConfigListeners(agentId);
        });
    }

    @Override
    public CompletionStage<?> updateGaugeConfig(String agentId, GaugeConfig config, String priorVersion, CassandraProfile profile) {
        return agentConfigDao.update(agentId, new AgentConfigUpdater() {
            @Override
            public CompletionStage<AgentConfig> updateAgentConfig(AgentConfig agentConfig) {
                return CompletableFuture.completedFuture(null).thenApply(ignore -> {
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
                });
            }
        }, profile).thenRun(() -> {
            notifyAgentConfigListeners(agentId);
        });
    }

    @Override
    public CompletionStage<?> deleteGaugeConfig(String agentId, String version, CassandraProfile profile) {
        return agentConfigDao.update(agentId, new AgentConfigUpdater() {
            @Override
            public CompletionStage<AgentConfig> updateAgentConfig(AgentConfig agentConfig) {
                return CompletableFuture.completedFuture(null).thenApply(ignore -> {
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
                });
            }
        }, profile).thenRun(() -> {
            notifyAgentConfigListeners(agentId);
        });
    }

    @Override
    public CompletionStage<?> updateJvmConfig(String agentId, JvmConfig config, String priorVersion, CassandraProfile profile) {
        return agentConfigDao.update(agentId, new AgentConfigUpdater() {
            @Override
            public CompletionStage<AgentConfig> updateAgentConfig(AgentConfig agentConfig) {
                return CompletableFuture.completedFuture(null).thenApply(ignore -> {
                    String existingVersion = Versions.getVersion(agentConfig.getJvmConfig());
                    if (!priorVersion.equals(existingVersion)) {
                        throw new OptimisticLockException();
                    }
                    return agentConfig.toBuilder()
                            .setJvmConfig(config)
                            .build();
                });
            }
        }, profile).thenRun(() -> {
            notifyAgentConfigListeners(agentId);
        });
    }

    // central supports synthetic monitor configs on rollups
    @Override
    public CompletionStage<?> insertSyntheticMonitorConfig(String agentRollupId, SyntheticMonitorConfig config, CassandraProfile profile) {
        return agentConfigDao.update(agentRollupId, new AgentConfigUpdater() {
            @Override
            public CompletionStage<AgentConfig> updateAgentConfig(AgentConfig agentConfig) {
                return CompletableFuture.completedFuture(null).thenApply(ignore -> {
                    // check for duplicate display
                    String display = MoreConfigDefaults.getDisplayOrDefault(config);
                    for (SyntheticMonitorConfig loopConfig : agentConfig
                            .getSyntheticMonitorConfigList()) {
                        if (MoreConfigDefaults.getDisplayOrDefault(loopConfig).equals(display)) {
                            throw new DuplicateSyntheticMonitorDisplayException();
                        }
                    }
                    // no need to check for exact match since redundant with duplicate name check
                    return agentConfig.toBuilder()
                            .addSyntheticMonitorConfig(config)
                            .build();
                });
            }
        }, profile).thenRun(() -> {
            notifyAgentConfigListeners(agentRollupId);
        });
    }

    // central supports synthetic monitor configs on rollups
    @Override
    public CompletionStage<?> updateSyntheticMonitorConfig(String agentRollupId, SyntheticMonitorConfig config,
                                             String priorVersion, CassandraProfile profile) {
        return agentConfigDao.update(agentRollupId, new AgentConfigUpdater() {
            @Override
            public CompletionStage<AgentConfig> updateAgentConfig(AgentConfig agentConfig) {
                return CompletableFuture.completedFuture(null).thenApply(ignore -> {
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
                        } else if (MoreConfigDefaults.getDisplayOrDefault(loopConfig).equals(display)) {
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
                });
            }
        }, profile).thenRun(() -> {
            notifyAgentConfigListeners(agentRollupId);
        });
    }

    // central supports synthetic monitor configs on rollups
    @Override
    public CompletionStage<?> deleteSyntheticMonitorConfig(String agentRollupId, String syntheticMonitorId, CassandraProfile profile) {
        return agentConfigDao.update(agentRollupId, new AgentConfigUpdater() {
            @Override
            public CompletionStage<AgentConfig> updateAgentConfig(AgentConfig agentConfig) {

                return getAlertConfigsForSyntheticMonitorId(agentRollupId, syntheticMonitorId).thenApply(alertConfigs -> {
                    if (!alertConfigs.isEmpty()) {
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
                });
            }
        }, profile).thenRun(() -> {
            notifyAgentConfigListeners(agentRollupId);
        });
    }

    // central supports alert configs on rollups
    @Override
    public CompletionStage<?> insertAlertConfig(String agentRollupId, AlertConfig config, CassandraProfile profile) {
        return agentConfigDao.update(agentRollupId, new AgentConfigUpdater() {
            @Override
            public CompletionStage<AgentConfig> updateAgentConfig(AgentConfig agentConfig) {
                return CompletableFuture.completedFuture(null).thenApply(ignore -> {
                    for (AlertConfig loopConfig : agentConfig.getAlertConfigList()) {
                        if (loopConfig.getCondition().equals(config.getCondition())) {
                            throw new IllegalStateException(
                                    "This exact alert condition already exists");
                        }
                    }
                    return agentConfig.toBuilder()
                            .addAlertConfig(config)
                            .build();
                });
            }
        }, profile).thenRun(() -> {
            notifyAgentConfigListeners(agentRollupId);
        });

    }

    // central supports alert configs on rollups
    @Override
    public CompletionStage<?> updateAlertConfig(String agentRollupId, AlertConfig config, String priorVersion, CassandraProfile profile) {
        return agentConfigDao.update(agentRollupId, new AgentConfigUpdater() {
            @Override
            public CompletionStage<AgentConfig> updateAgentConfig(AgentConfig agentConfig) {
                return CompletableFuture.completedFuture(null).thenApply(ignore -> {
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
                });
            }
        }, profile).thenRun(() -> {
            notifyAgentConfigListeners(agentRollupId);
        });
    }

    // central supports alert configs on rollups
    @Override
    public CompletionStage<?> deleteAlertConfig(String agentRollupId, String version, CassandraProfile profile) {
        return agentConfigDao.update(agentRollupId, new AgentConfigUpdater() {
            @Override
            public CompletionStage<AgentConfig> updateAgentConfig(AgentConfig agentConfig) {
                return CompletableFuture.completedFuture(null).thenApply(ignore -> {
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
                });
            }
        }, profile).thenRun(() -> {
            notifyAgentConfigListeners(agentRollupId);
        });
    }

    // central supports ui config on rollups
    @Override
    public CompletionStage<?> updateUiDefaultsConfig(String agentRollupId, UiDefaultsConfig config,
                                       String priorVersion, CassandraProfile profile) {
        return agentConfigDao.update(agentRollupId, new AgentConfigUpdater() {
            @Override
            public CompletionStage<AgentConfig> updateAgentConfig(AgentConfig agentConfig) {
                return CompletableFuture.completedFuture(null).thenApply(ignore -> {

                    String existingVersion = Versions.getVersion(agentConfig.getUiDefaultsConfig());
                    if (!priorVersion.equals(existingVersion)) {
                        throw new OptimisticLockException();
                    }
                    return agentConfig.toBuilder()
                            .setUiDefaultsConfig(config)
                            .build();
                });
            }
        }, profile).thenRun(() -> {
            notifyAgentConfigListeners(agentRollupId);
        });
    }

    @Override
    public CompletionStage<?> updatePluginConfig(String agentId, PluginConfig config, String priorVersion, CassandraProfile profile) {
        return agentConfigDao.update(agentId, new AgentConfigUpdater() {
            @Override
            public CompletionStage<AgentConfig> updateAgentConfig(AgentConfig agentConfig) {
                return CompletableFuture.completedFuture(null).thenApply(ignore -> {
                    List<PluginConfig> pluginConfigs =
                            buildPluginConfigs(config, priorVersion, agentConfig);
                    return agentConfig.toBuilder()
                            .clearPluginConfig()
                            .addAllPluginConfig(pluginConfigs)
                            .build();
                });
            }
        }, profile).thenRun(() -> {
            notifyAgentConfigListeners(agentId);
        });
    }

    @Override
    public CompletionStage<?> insertInstrumentationConfig(String agentId, InstrumentationConfig config, CassandraProfile profile) {
        return agentConfigDao.update(agentId, new AgentConfigUpdater() {
            @Override
            public CompletionStage<AgentConfig> updateAgentConfig(AgentConfig agentConfig) {
                return CompletableFuture.completedFuture(null).thenApply(ignore -> {
                    if (agentConfig.getInstrumentationConfigList().contains(config)) {
                        throw new IllegalStateException("This exact instrumentation already exists");
                    }
                    return agentConfig.toBuilder()
                            .addInstrumentationConfig(config)
                            .build();
                });
            }
        }, profile).thenRun(() -> {
            notifyAgentConfigListeners(agentId);
        });
    }

    @Override
    public CompletionStage<?> updateInstrumentationConfig(String agentId, InstrumentationConfig config,
                                            String priorVersion, CassandraProfile profile)  {
        return agentConfigDao.update(agentId, new AgentConfigUpdater() {
            @Override
            public CompletionStage<AgentConfig> updateAgentConfig(AgentConfig agentConfig) {
                return CompletableFuture.completedFuture(null).thenApply(ignore -> {
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
                });
            }
        }, profile).thenRun(() -> {
            notifyAgentConfigListeners(agentId);
        });
    }

    @Override
    public CompletionStage<?> deleteInstrumentationConfigs(String agentId, List<String> versions, CassandraProfile profile) {
        return agentConfigDao.update(agentId, new AgentConfigUpdater() {
            @Override
            public CompletionStage<AgentConfig> updateAgentConfig(AgentConfig agentConfig) {
                return CompletableFuture.completedFuture(null).thenApply(ignore -> {
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
                });
            }
        }, profile).thenRun(() -> {
            notifyAgentConfigListeners(agentId);
        });
    }

    // ignores any instrumentation configs that are duplicates of existing instrumentation configs
    @Override
    public CompletionStage<?> insertInstrumentationConfigs(String agentId, List<InstrumentationConfig> configs, CassandraProfile profile) {
        return agentConfigDao.update(agentId, new AgentConfigUpdater() {
            @Override
            public CompletionStage<AgentConfig> updateAgentConfig(AgentConfig agentConfig) {
                return CompletableFuture.completedFuture(null).thenApply(ignore -> {
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
                });
            }
        }, profile).thenRun(() -> {
            notifyAgentConfigListeners(agentId);
        });
    }

    @Override
    public CompletionStage<?> updateAdvancedConfig(String agentRollupId, AdvancedConfig config,
                                     String priorVersion, CassandraProfile profile) {
        return agentConfigDao.update(agentRollupId, new AgentConfigUpdater() {
            @Override
            public CompletionStage<AgentConfig> updateAgentConfig(AgentConfig agentConfig) {
                return CompletableFuture.completedFuture(null).thenApply(ignore -> {
                    String existingVersion = Versions.getVersion(agentConfig.getAdvancedConfig());
                    if (!priorVersion.equals(existingVersion)) {
                        throw new OptimisticLockException();
                    }
                    return agentConfig.toBuilder()
                            .setAdvancedConfig(config)
                            .build();
                });
            }
        }, profile).thenRun(() -> {
            notifyAgentConfigListeners(agentRollupId);
        });
    }

    @Override
    public CompletionStage<?> updateAllConfig(String agentId, AgentConfig config, @Nullable String priorVersion, CassandraProfile profile) {
        ConfigValidation.validatePartOne(config);
        return agentConfigDao.update(agentId, new AgentConfigUpdater() {
            @Override
            public CompletionStage<AgentConfig> updateAgentConfig(AgentConfig agentConfig) {
                return CompletableFuture.completedFuture(null).thenApply(ignore -> {
                    if (priorVersion != null) {
                        String existingVersion = Versions.getVersion(agentConfig);
                        if (!priorVersion.equals(existingVersion)) {
                            throw new OptimisticLockException();
                        }
                    }
                    Set<String> validPluginIds = Sets.newHashSet();
                    for (PluginConfig pluginConfig : agentConfig.getPluginConfigList()) {
                        validPluginIds.add(pluginConfig.getId());
                    }
                    ConfigValidation.validatePartTwo(config, validPluginIds);
                    return config.toBuilder()
                            .clearPluginConfig()
                            .addAllPluginConfig(
                                    buildPluginConfigs(config.getPluginConfigList(), agentConfig))
                            .build();
                });
            }
        }, profile).thenRun(() -> {
            notifyAgentConfigListeners(agentId);
        });
    }

    @Override
    public CompletionStage<?> updateEmbeddedAdminGeneralConfig(EmbeddedAdminGeneralConfig config,
                                                 String priorVersion, CassandraProfile profile) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletionStage<?> updateCentralAdminGeneralConfig(CentralAdminGeneralConfig config,
                                                              String priorVersion, CassandraProfile profile) {
        return centralConfigDao.write(GENERAL_KEY, config, priorVersion);
    }

    @Override
    public CompletionStage<?> insertUserConfig(UserConfig config, CassandraProfile profile) {
        // check for case-insensitive duplicate
        String username = config.username();
        return userDao.read().thenCompose(configs -> {
            for (UserConfig loopConfig : configs) {
                if (loopConfig.username().equalsIgnoreCase(username)) {
                    return CompletableFuture.failedFuture(new DuplicateUsernameException());
                }
            }
            return userDao.insertIfNotExists(config, profile);
        });
    }

    @Override
    public CompletionStage<?> updateUserConfig(UserConfig config, String priorVersion, CassandraProfile profile) {
        return userDao.read(config.username()).thenCompose(existingConfig -> {
            if (existingConfig == null) {
                return CompletableFuture.failedFuture(new UserNotFoundException());
            }
            if (!existingConfig.version().equals(priorVersion)) {
                return CompletableFuture.failedFuture(new OptimisticLockException());
            }
            return userDao.insert(config, profile);
        });
    }

    @Override
    public CompletionStage<?> deleteUserConfig(String username, CassandraProfile profile) {
        return userDao.read().thenCompose(configs -> {
            boolean found = false;
            for (UserConfig config : configs) {
                if (config.username().equalsIgnoreCase(username)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return CompletableFuture.failedFuture(new UserNotFoundException());
            }
            return getSmtpConfig().thenCompose(smtpConfig -> {
                if (smtpConfig.host().isEmpty() && configs.size() == 1) {
                    return CompletableFuture.failedFuture(new CannotDeleteLastUserException());
                }
                return userDao.delete(username, profile);
            });
        });
    }

    @Override
    public CompletionStage<?> insertRoleConfig(RoleConfig config, CassandraProfile profile) {
        // check for case-insensitive duplicate
        String name = config.name();
        return roleDao.read().thenCompose(configs -> {
            for (RoleConfig loopConfig : configs) {
                if (loopConfig.name().equalsIgnoreCase(name)) {
                    return CompletableFuture.failedFuture(new DuplicateRoleNameException());
                }
            }
            return roleDao.insertIfNotExists(config, profile);
        });
    }

    @Override
    public CompletionStage<?> updateRoleConfig(RoleConfig config, String priorVersion, CassandraProfile profile) {
        return roleDao.read(config.name()).thenCompose(existingConfig -> {
            if (existingConfig == null) {
                return CompletableFuture.failedFuture(new RoleNotFoundException());
            }
            if (!existingConfig.version().equals(priorVersion)) {
                return CompletableFuture.failedFuture(new OptimisticLockException());
            }
            return roleDao.insert(config, profile);
        });
    }

    @Override
    public CompletionStage<?> deleteRoleConfig(String name, CassandraProfile profile) {
        return roleDao.read().thenCompose(configs -> {
            boolean found = false;
            for (RoleConfig config : configs) {
                if (config.name().equalsIgnoreCase(name)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return CompletableFuture.failedFuture(new RoleNotFoundException());
            }
            if (configs.size() == 1) {
                return CompletableFuture.failedFuture(new CannotDeleteLastRoleException());
            }
            return roleDao.delete(name, profile);
        });
    }

    @Override
    public void updateEmbeddedWebConfig(EmbeddedWebConfig config, String priorVersion) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletionStage<?> updateCentralWebConfig(CentralWebConfig config, String priorVersion) {
        return centralConfigDao.write(WEB_KEY, config, priorVersion);
    }

    @Override
    public void updateEmbeddedStorageConfig(EmbeddedStorageConfig config, String priorVersion) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletionStage<?> updateCentralStorageConfig(CentralStorageConfig config, String priorVersion) {
        return centralConfigDao.write(STORAGE_KEY, config, priorVersion);
    }

    @Override
    public CompletionStage<?> updateSmtpConfig(SmtpConfig config, String priorVersion) {
        return centralConfigDao.write(SMTP_KEY, config, priorVersion);
    }

    @Override
    public CompletionStage<?> updateHttpProxyConfig(HttpProxyConfig config, String priorVersion) {
        return centralConfigDao.write(HTTP_PROXY_KEY, config, priorVersion);
    }

    @Override
    public CompletionStage<?> updateLdapConfig(LdapConfig config, String priorVersion) {
        return centralConfigDao.write(LDAP_KEY, config, priorVersion);
    }

    @Override
    public CompletionStage<?> updatePagerDutyConfig(PagerDutyConfig config, String priorVersion)
            throws Exception {
        validatePagerDutyConfig(config);
        return centralConfigDao.write(PAGER_DUTY_KEY, config, priorVersion);
    }

    @Override
    public CompletionStage<?> updateSlackConfig(SlackConfig config, String priorVersion) throws Exception {
        validateSlackConfig(config);
        return centralConfigDao.write(SLACK_KEY, config, priorVersion);
    }

    @Override
    public void updateHealthchecksIoConfig(HealthchecksIoConfig healthchecksIoConfig,
                                           String priorVersion) throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateAllEmbeddedAdminConfig(AllEmbeddedAdminConfig config,
                                             @Nullable String priorVersion) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateAllCentralAdminConfig(AllCentralAdminConfig config,
                                            @Nullable String priorVersion) throws Exception {
        validatePagerDutyConfig(config.pagerDuty());
        validateSlackConfig(config.slack());
        if (priorVersion == null) {
            centralConfigDao.writeWithoutOptimisticLocking(GENERAL_KEY, config.general()).thenCompose(ig ->
                    centralConfigDao.writeWithoutOptimisticLocking(WEB_KEY, config.web())).thenCompose(iw ->
                    centralConfigDao.writeWithoutOptimisticLocking(STORAGE_KEY, config.storage())).thenCompose(is ->
                    centralConfigDao.writeWithoutOptimisticLocking(SMTP_KEY, config.smtp())).thenCompose(ism ->
                    centralConfigDao.writeWithoutOptimisticLocking(HTTP_PROXY_KEY, config.httpProxy())).thenCompose(ihp ->
                    centralConfigDao.writeWithoutOptimisticLocking(LDAP_KEY, config.ldap())).thenCompose(il ->
                    centralConfigDao.writeWithoutOptimisticLocking(PAGER_DUTY_KEY, config.pagerDuty())).thenCompose(ipd ->
                    centralConfigDao.writeWithoutOptimisticLocking(SLACK_KEY, config.slack())).thenCompose(isl ->
                    writeUsersWithoutOptimisticLocking(config.users())).thenCompose(iu ->
                    writeRolesWithoutOptimisticLocking(config.roles())).toCompletableFuture().join();
        } else {
            try {
                getAllCentralAdminConfig().thenCompose(currConfig -> {
                    if (!priorVersion.equals(currConfig.version())) {
                        return CompletableFuture.failedFuture(new OptimisticLockException());
                    }
                    return centralConfigDao.write(GENERAL_KEY, config.general(), currConfig.general().version()).thenCompose(ig ->
                            centralConfigDao.write(WEB_KEY, config.web(), currConfig.web().version()).thenCompose(iw ->
                                    centralConfigDao.write(STORAGE_KEY, config.storage(), currConfig.storage().version()).thenCompose(is ->
                                            centralConfigDao.write(SMTP_KEY, config.smtp(), currConfig.smtp().version()).thenCompose(ism ->
                                                    centralConfigDao.write(HTTP_PROXY_KEY, config.httpProxy(),
                                                            currConfig.httpProxy().version()).thenCompose(ihp ->
                                                            centralConfigDao.write(LDAP_KEY, config.ldap(), currConfig.ldap().version()).thenCompose(il ->
                                                                    centralConfigDao.write(PAGER_DUTY_KEY, config.pagerDuty(),
                                                                            currConfig.pagerDuty().version()).thenCompose(ipd ->
                                                                            centralConfigDao.write(SLACK_KEY, config.slack(), currConfig.slack().version()).thenCompose(isl ->
                                                                                    // there is currently no optimistic locking when updating users
                                                                                    writeUsersWithoutOptimisticLocking(config.users()).thenCompose(iu ->
                                                                                            writeRolesWithoutOptimisticLocking(config.roles()))))))))));
                }).toCompletableFuture().join();
            } catch (CompletionException e) {
                if (e.getCause() instanceof OptimisticLockException) {
                    throw (OptimisticLockException) e.getCause();
                }
                throw new RuntimeException(e);
            }
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

    @Override
    public LazySecretKey getLazySecretKey() {
        return lazySecretKey;
    }

    public void addAgentConfigListener(AgentConfigListener listener) {
        agentConfigListeners.add(listener);
    }

    private CompletionStage<?> writeUsersWithoutOptimisticLocking(List<ImmutableUserConfig> userConfigs) {
        return getUserConfigs().thenCompose(currentConfigs -> {
            Map<String, UserConfig> remainingUserConfigs = new HashMap<>();
            for (UserConfig userConfig : currentConfigs) {
                remainingUserConfigs.put(userConfig.username(), userConfig);
            }
            List<CompletionStage<?>> futures = new ArrayList<>();
            for (UserConfig userConfig : userConfigs) {
                UserConfig existingUserConfig =
                        remainingUserConfigs.remove(userConfig.username());
                if (userConfig.passwordHash().isEmpty() && !userConfig.ldap()) {
                    if (existingUserConfig == null) {
                        throw new IllegalStateException(
                                "New user " + userConfig.username() + " is missing password");
                    }
                    userConfig = ImmutableUserConfig.copyOf(userConfig)
                            .withPasswordHash(existingUserConfig.passwordHash());
                }
                futures.add(userDao.insert(userConfig, CassandraProfile.web));
            }
            for (String remainingUsername : remainingUserConfigs.keySet()) {
                futures.add(userDao.delete(remainingUsername, CassandraProfile.web));
            }
            return CompletableFutures.allAsList(futures);
        });
    }

    private CompletionStage<?> writeRolesWithoutOptimisticLocking(List<ImmutableRoleConfig> roleConfigs) {
        return getRoleConfigs().thenCompose(currrentRoleConfigs -> {
            Map<String, RoleConfig> remainingRoleConfigs = new HashMap<>();
            for (RoleConfig roleConfig : currrentRoleConfigs) {
                remainingRoleConfigs.put(roleConfig.name(), roleConfig);
            }
            List<CompletionStage<?>> futures = new ArrayList<>();
            for (RoleConfig roleConfig : roleConfigs) {
                remainingRoleConfigs.remove(roleConfig.name());
                futures.add(roleDao.insert(roleConfig, CassandraProfile.web));
            }
            for (String remainingRolename : remainingRoleConfigs.keySet()) {
                futures.add(roleDao.delete(remainingRolename, CassandraProfile.web));
            }
            return CompletableFutures.allAsList(futures);
        });
    }

    // the updated config is not passed to the listeners to avoid the race condition of multiple
    // config updates being sent out of order, instead listeners must call get*Config() which will
    // never return the updates out of order (at worst it may return the most recent update twice
    // which is ok)
    private void notifyAgentConfigListeners(String agentRollupId) {
        for (AgentConfigListener agentConfigListener : agentConfigListeners) {
            agentConfigListener.onChange(agentRollupId);
        }
    }

    private static List<PluginConfig> buildPluginConfigs(PluginConfig updatedConfig,
                                                         String priorVersion, AgentConfig agentConfig) throws OptimisticLockException {
        List<PluginConfig> pluginConfigs =
                Lists.newArrayList(agentConfig.getPluginConfigList());
        ListIterator<PluginConfig> i = pluginConfigs.listIterator();
        boolean found = false;
        while (i.hasNext()) {
            PluginConfig pluginConfig = i.next();
            if (pluginConfig.getId().equals(updatedConfig.getId())) {
                String existingVersion = Versions.getVersion(pluginConfig);
                if (!priorVersion.equals(existingVersion)) {
                    throw new OptimisticLockException();
                }
                i.set(buildPluginConfig(pluginConfig, updatedConfig.getPropertyList(), true));
                found = true;
                break;
            }
        }
        if (found) {
            return pluginConfigs;
        } else {
            throw new IllegalStateException("Plugin config not found: " + updatedConfig.getId());
        }
    }

    private static List<PluginConfig> buildPluginConfigs(List<PluginConfig> newConfigs,
                                                         AgentConfig agentConfig) {
        List<PluginConfig> pluginConfigs = new ArrayList<>();
        Map<String, PluginConfig> remainingNewConfigs = new HashMap<>();
        for (PluginConfig newConfig : newConfigs) {
            remainingNewConfigs.put(newConfig.getId(), newConfig);
        }
        for (PluginConfig pluginConfig : agentConfig.getPluginConfigList()) {
            PluginConfig newConfig = remainingNewConfigs.remove(pluginConfig.getId());
            List<PluginProperty> newProperties;
            if (newConfig == null) {
                newProperties = new ArrayList<>();
            } else {
                newProperties = newConfig.getPropertyList();
            }
            pluginConfigs.add(buildPluginConfig(pluginConfig, newProperties, false));
        }
        if (remainingNewConfigs.isEmpty()) {
            return pluginConfigs;
        } else {
            throw new IllegalStateException("Plugin config(s) not found: "
                    + Joiner.on(", ").join(remainingNewConfigs.keySet()));
        }
    }

    private static PluginConfig buildPluginConfig(PluginConfig existingConfig,
                                                  List<PluginProperty> newProperties, boolean errorOnMissingProperty) {
        Map<String, PluginProperty> newProps = buildMutablePropertiesMap(newProperties);
        PluginConfig.Builder builder = PluginConfig.newBuilder()
                .setId(existingConfig.getId())
                .setName(existingConfig.getName());
        for (PluginProperty existingProperty : existingConfig.getPropertyList()) {
            PluginProperty prop = newProps.remove(existingProperty.getName());
            if (prop == null) {
                if (errorOnMissingProperty) {
                    throw new IllegalStateException(
                            "Missing plugin property name: " + existingProperty.getName());
                } else {
                    builder.addProperty(existingProperty.toBuilder()
                            .setValue(existingProperty.getDefault()));
                    continue;
                }
            }
            if (!isSameType(prop.getValue(), existingProperty.getValue())) {
                throw new IllegalStateException("Plugin property " + prop.getName()
                        + " has incorrect type: " + prop.getValue().getValCase());
            }
            builder.addProperty(existingProperty.toBuilder()
                    .setValue(prop.getValue()));
        }
        if (!newProps.isEmpty()) {
            throw new IllegalStateException(
                    "Unexpected property name(s): " + Joiner.on(", ").join(newProps.keySet()));
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
        CentralStorageConfig defaultConfig = ImmutableCentralStorageConfig.builder().build();
        return ImmutableCentralStorageConfig.builder()
                .copyFrom(config)
                .rollupExpirationHours(
                        fix(config.rollupExpirationHours(), defaultConfig.rollupExpirationHours()))
                .queryAndServiceCallRollupExpirationHours(
                        fix(config.queryAndServiceCallRollupExpirationHours(),
                                defaultConfig.queryAndServiceCallRollupExpirationHours()))
                .profileRollupExpirationHours(fix(config.profileRollupExpirationHours(),
                        defaultConfig.profileRollupExpirationHours()))
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

    private static void validatePagerDutyConfig(PagerDutyConfig config) throws Exception {
        Set<String> integrationKeys = new HashSet<>();
        Set<String> integrationDisplays = new HashSet<>();
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

    public interface AgentConfigListener {

        // the new config is not passed to onChange so that the receiver has to get the latest,
        // this avoids race condition worries that two updates may get sent to the receiver in the
        // wrong order
        void onChange(String agentRollupId);
    }

    public static class LazySecretKeyImpl implements LazySecretKey {

        private final @Nullable SecretKey secretKey;

        public LazySecretKeyImpl(String symmetricEncryptionKey) {
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
