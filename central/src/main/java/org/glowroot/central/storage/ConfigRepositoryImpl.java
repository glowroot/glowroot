/*
 * Copyright 2015 the original author or authors.
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

import java.util.List;
import java.util.ListIterator;

import javax.annotation.Nullable;
import javax.crypto.SecretKey;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import org.glowroot.common.config.AdvancedConfig;
import org.glowroot.common.config.GaugeConfig;
import org.glowroot.common.config.InstrumentationConfig;
import org.glowroot.common.config.PluginConfig;
import org.glowroot.common.config.TransactionConfig;
import org.glowroot.common.config.UserRecordingConfig;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.config.AlertConfig;
import org.glowroot.storage.repo.config.ImmutableAlertConfig;
import org.glowroot.storage.repo.config.ImmutableSmtpConfig;
import org.glowroot.storage.repo.config.ImmutableStorageConfig;
import org.glowroot.storage.repo.config.ImmutableUserInterfaceConfig;
import org.glowroot.storage.repo.config.SmtpConfig;
import org.glowroot.storage.repo.config.StorageConfig;
import org.glowroot.storage.repo.config.UserInterfaceConfig;

import static com.google.common.base.Preconditions.checkState;

public class ConfigRepositoryImpl implements ConfigRepository {

    // TODO this needs to be in sync with agents, so have agents pick up value from central
    private static final long GAUGE_COLLECTION_INTERVAL_MILLIS =
            Long.getLong("glowroot.internal.gaugeCollectionIntervalMillis", 5000);

    private static final ObjectMapper mapper = new ObjectMapper();

    private final ConfigDao configDao;

    private final ImmutableList<RollupConfig> rollupConfigs;

    public ConfigRepositoryImpl(ConfigDao configDao) {
        this.configDao = configDao;
        rollupConfigs = ImmutableList.copyOf(RollupConfig.buildRollupConfigs());
    }

    @Override
    public UserInterfaceConfig getUserInterfaceConfig() {
        UserInterfaceConfig config =
                configDao.read(UI_KEY, ImmutableUserInterfaceConfig.class, mapper);
        if (config == null) {
            return ImmutableUserInterfaceConfig.builder().build();
        }
        return config;
    }

    @Override
    public SmtpConfig getSmtpConfig() {
        SmtpConfig config = configDao.read(SMTP_KEY, ImmutableSmtpConfig.class, mapper);
        if (config == null) {
            return ImmutableSmtpConfig.builder().build();
        }
        return config;
    }

    @Override
    public List<AlertConfig> getAlertConfigs(String serverRollup) throws JsonProcessingException {
        List<ImmutableAlertConfig> configs = configDao.read(ALERTS_KEY,
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
        configDao.write(UI_KEY, userInterfaceConfig, mapper);
    }

    @Override
    public void updateSmtpConfig(SmtpConfig smtpConfig, String priorVersion) throws Exception {
        if (!getSmtpConfig().version().equals(priorVersion)) {
            throw new OptimisticLockException();
        }
        configDao.write(SMTP_KEY, smtpConfig, mapper);
    }

    @Override
    public void insertAlertConfig(String serverRollup, AlertConfig alertConfig)
            throws JsonProcessingException {
        List<AlertConfig> configs = Lists.newArrayList(getAlertConfigs(serverRollup));
        configs.add(alertConfig);
        configDao.write(ALERTS_KEY, configs, mapper);
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
        configDao.write(ALERTS_KEY, configs, mapper);
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
        configDao.write(ALERTS_KEY, configs, mapper);
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
    public TransactionConfig getTransactionConfig(String serverId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public UserRecordingConfig getUserRecordingConfig(String serverId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public AdvancedConfig getAdvancedConfig(String serverId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public PluginConfig getPluginConfig(String serverId, String pluginId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<InstrumentationConfig> getInstrumentationConfigs(String serverId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public InstrumentationConfig getInstrumentationConfig(String serverId, String version) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<GaugeConfig> getGaugeConfigs(String serverId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public GaugeConfig getGaugeConfig(String serverId, String version) {
        throw new UnsupportedOperationException();
    }

    @Override
    public StorageConfig getStorageConfig() {
        // this is needed for access to StorageConfig.rollupExpirationHours()
        return ImmutableStorageConfig.builder().build();
    }

    @Override
    public void updateTransactionConfig(String serverId, TransactionConfig transactionConfig,
            String priorVersion) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateUserRecordingConfig(String serverId, UserRecordingConfig userRecordingConfig,
            String priorVersion) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateAdvancedConfig(String serverId, AdvancedConfig advancedConfig,
            String priorVersion) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updatePluginConfig(String serverId, PluginConfig pluginConfig,
            String priorVersion) {
        throw new UnsupportedOperationException();
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
}
