/*
 * Copyright 2011-2015 the original author or authors.
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
package org.glowroot.agent.init.fat;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import javax.annotation.Nullable;
import javax.crypto.SecretKey;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.config.ConfigService.ShadeProtectedTypeReference;
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
import org.glowroot.storage.util.Encryption;

import static com.google.common.base.Preconditions.checkState;

class ConfigRepositoryImpl implements ConfigRepository {

    private static final Logger logger = LoggerFactory.getLogger(ConfigRepositoryImpl.class);

    private final ConfigService configService;
    private final File secretFile;

    private final Object writeLock = new Object();

    private final ImmutableList<RollupConfig> rollupConfigs;

    private volatile UserInterfaceConfig userInterfaceConfig;
    private volatile StorageConfig storageConfig;
    private volatile SmtpConfig smtpConfig;
    private volatile ImmutableList<AlertConfig> alertConfigs;

    // volatile not needed as access is guarded by secretFile
    private @MonotonicNonNull SecretKey secretKey;

    static ConfigRepository create(File baseDir, ConfigService configService) {
        ConfigRepositoryImpl configRepository =
                new ConfigRepositoryImpl(baseDir, configService);
        // it's nice to update config.json on startup if it is missing some/all config
        // properties so that the file contents can be reviewed/updated/copied if desired
        try {
            configRepository.writeAll();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
        return configRepository;
    }

    private ConfigRepositoryImpl(File baseDir, ConfigService configService) {
        this.configService = configService;
        secretFile = new File(baseDir, "secret");
        rollupConfigs = RollupConfig.buildRollupConfigs();

        UserInterfaceConfig userInterfaceConfig =
                configService.getOtherConfig(UI_KEY, ImmutableUserInterfaceConfig.class);
        if (userInterfaceConfig == null) {
            this.userInterfaceConfig = ImmutableUserInterfaceConfig.builder().build();
        } else {
            this.userInterfaceConfig = userInterfaceConfig;
        }
        StorageConfig storageConfig =
                configService.getOtherConfig(STORAGE_KEY, ImmutableStorageConfig.class);
        if (storageConfig == null) {
            this.storageConfig = ImmutableStorageConfig.builder().build();
        } else if (storageConfig.hasListIssues()) {
            this.storageConfig = withCorrectedLists(storageConfig);
        } else {
            this.storageConfig = storageConfig;
        }
        SmtpConfig smtpConfig = configService.getOtherConfig(SMTP_KEY, ImmutableSmtpConfig.class);
        if (smtpConfig == null) {
            this.smtpConfig = ImmutableSmtpConfig.builder().build();
        } else {
            this.smtpConfig = smtpConfig;
        }
        List<ImmutableAlertConfig> alertConfigs = configService.getOtherConfig(ALERTS_KEY,
                new ShadeProtectedTypeReference<List<ImmutableAlertConfig>>() {});
        if (alertConfigs == null) {
            this.alertConfigs = ImmutableList.of();
        } else {
            this.alertConfigs = ImmutableList.<AlertConfig>copyOf(alertConfigs);
        }
    }

    @Override
    public TransactionConfig getTransactionConfig(String serverId) {
        return configService.getTransactionConfig();
    }

    @Override
    public UserRecordingConfig getUserRecordingConfig(String serverId) {
        return configService.getUserRecordingConfig();
    }

    @Override
    public AdvancedConfig getAdvancedConfig(String serverId) {
        return configService.getAdvancedConfig();
    }

    @Override
    public @Nullable PluginConfig getPluginConfig(String serverId, String pluginId) {
        return configService.getPluginConfig(pluginId);
    }

    @Override
    public List<InstrumentationConfig> getInstrumentationConfigs(String serverId) {
        return configService.getInstrumentationConfigs();
    }

    @Override
    public @Nullable InstrumentationConfig getInstrumentationConfig(String serverId,
            String version) {
        for (InstrumentationConfig instrumentationConfig : configService
                .getInstrumentationConfigs()) {
            if (instrumentationConfig.version().equals(version)) {
                return instrumentationConfig;
            }
        }
        return null;
    }

    @Override
    public List<GaugeConfig> getGaugeConfigs(String serverId) {
        return configService.getGaugeConfigs();
    }

    @Override
    public @Nullable GaugeConfig getGaugeConfig(String serverId, String version) {
        for (GaugeConfig gaugeConfig : configService.getGaugeConfigs()) {
            if (gaugeConfig.version().equals(version)) {
                return gaugeConfig;
            }
        }
        return null;
    }

    @Override
    public UserInterfaceConfig getUserInterfaceConfig() {
        return userInterfaceConfig;
    }

    @Override
    public StorageConfig getStorageConfig() {
        return storageConfig;
    }

    @Override
    public SmtpConfig getSmtpConfig() {
        return smtpConfig;
    }

    @Override
    public List<AlertConfig> getAlertConfigs(String serverId) {
        return alertConfigs;
    }

    @Override
    public @Nullable AlertConfig getAlertConfig(String serverId, String version) {
        for (AlertConfig alertConfig : alertConfigs) {
            if (alertConfig.version().equals(version)) {
                return alertConfig;
            }
        }
        return null;
    }

    @Override
    public void updateTransactionConfig(String serverId, TransactionConfig updatedConfig,
            String priorVersion) throws Exception {
        synchronized (writeLock) {
            checkVersionsEqual(configService.getTransactionConfig().version(), priorVersion);
            configService.updateTransactionConfig(updatedConfig);
        }
    }

    @Override
    public void updateUserRecordingConfig(String serverId, UserRecordingConfig userRecordingConfig,
            String priorVersion) throws Exception {
        synchronized (writeLock) {
            checkVersionsEqual(configService.getUserRecordingConfig().version(), priorVersion);
            configService.updateUserRecordingConfig(userRecordingConfig);
        }
    }

    @Override
    public void updateAdvancedConfig(String serverId, AdvancedConfig advancedConfig,
            String priorVersion) throws Exception {
        synchronized (writeLock) {
            checkVersionsEqual(configService.getAdvancedConfig().version(), priorVersion);
            configService.updateAdvancedConfig(advancedConfig);
        }
    }

    @Override
    public void updatePluginConfig(String serverId, PluginConfig pluginConfig, String priorVersion)
            throws Exception {
        synchronized (writeLock) {
            List<PluginConfig> configs = Lists.newArrayList(configService.getPluginConfigs());
            boolean found = false;
            for (ListIterator<PluginConfig> i = configs.listIterator(); i.hasNext();) {
                PluginConfig loopPluginConfig = i.next();
                if (pluginConfig.id().equals(loopPluginConfig.id())) {
                    checkVersionsEqual(loopPluginConfig.version(), priorVersion);
                    i.set(pluginConfig);
                    found = true;
                    break;
                }
            }
            checkState(found, "Plugin config not found: %s", pluginConfig.id());
            configService.updatePluginConfigs(configs);
        }
    }

    @Override
    public void insertInstrumentationConfig(String serverId,
            InstrumentationConfig instrumentationConfig) throws IOException {
        synchronized (writeLock) {
            List<InstrumentationConfig> configs =
                    Lists.newArrayList(configService.getInstrumentationConfigs());
            configs.add(instrumentationConfig);
            configService.updateInstrumentationConfigs(configs);
        }
    }

    @Override
    public void updateInstrumentationConfig(String serverId,
            InstrumentationConfig instrumentationConfig, String priorVersion) throws IOException {
        synchronized (writeLock) {
            List<InstrumentationConfig> configs =
                    Lists.newArrayList(configService.getInstrumentationConfigs());
            boolean found = false;
            for (ListIterator<InstrumentationConfig> i = configs.listIterator(); i.hasNext();) {
                if (priorVersion.equals(i.next().version())) {
                    i.set(instrumentationConfig);
                    found = true;
                    break;
                }
            }
            checkState(found, "Instrumentation config not found: %s", priorVersion);
            configService.updateInstrumentationConfigs(configs);
        }
    }

    @Override
    public void deleteInstrumentationConfig(String serverId, String version) throws IOException {
        synchronized (writeLock) {
            List<InstrumentationConfig> configs =
                    Lists.newArrayList(configService.getInstrumentationConfigs());
            boolean found = false;
            for (ListIterator<InstrumentationConfig> i = configs.listIterator(); i.hasNext();) {
                if (version.equals(i.next().version())) {
                    i.remove();
                    found = true;
                    break;
                }
            }
            checkState(found, "Instrumentation config not found: %s", version);
            configService.updateInstrumentationConfigs(configs);
        }
    }

    @Override
    public void insertGaugeConfig(String serverId, GaugeConfig gaugeConfig) throws Exception {
        synchronized (writeLock) {
            List<GaugeConfig> configs = Lists.newArrayList(configService.getGaugeConfigs());
            // check for duplicate mbeanObjectName
            for (GaugeConfig loopConfig : configs) {
                if (loopConfig.mbeanObjectName().equals(gaugeConfig.mbeanObjectName())) {
                    throw new DuplicateMBeanObjectNameException();
                }
            }
            configs.add(gaugeConfig);
            configService.updateGaugeConfigs(configs);
        }
    }

    @Override
    public void updateGaugeConfig(String serverId, GaugeConfig gaugeConfig, String priorVersion)
            throws Exception {
        synchronized (writeLock) {
            List<GaugeConfig> configs = Lists.newArrayList(configService.getGaugeConfigs());
            boolean found = false;
            for (ListIterator<GaugeConfig> i = configs.listIterator(); i.hasNext();) {
                GaugeConfig loopConfig = i.next();
                if (priorVersion.equals(loopConfig.version())) {
                    i.set(gaugeConfig);
                    found = true;
                    break;
                } else if (loopConfig.mbeanObjectName().equals(gaugeConfig.mbeanObjectName())) {
                    throw new DuplicateMBeanObjectNameException();
                }
            }
            checkState(found, "Gauge config not found: %s", priorVersion);
            configService.updateGaugeConfigs(configs);
        }
    }

    @Override
    public void deleteGaugeConfig(String serverId, String version) throws IOException {
        synchronized (writeLock) {
            List<GaugeConfig> configs = Lists.newArrayList(configService.getGaugeConfigs());
            boolean found = false;
            for (ListIterator<GaugeConfig> i = configs.listIterator(); i.hasNext();) {
                if (version.equals(i.next().version())) {
                    i.remove();
                    found = true;
                    break;
                }
            }
            checkState(found, "Gauge config not found: %s", version);
            configService.updateGaugeConfigs(configs);
        }
    }

    @Override
    public void updateUserInterfaceConfig(UserInterfaceConfig updatedConfig, String priorVersion)
            throws Exception {
        synchronized (writeLock) {
            checkVersionsEqual(userInterfaceConfig.version(), priorVersion);
            configService.updateOtherConfig(UI_KEY, updatedConfig);
            userInterfaceConfig = updatedConfig;
        }
    }

    @Override
    public void updateStorageConfig(StorageConfig updatedConfig, String priorVersion)
            throws Exception {
        synchronized (writeLock) {
            checkVersionsEqual(storageConfig.version(), priorVersion);
            configService.updateOtherConfig(STORAGE_KEY, updatedConfig);
            storageConfig = updatedConfig;
        }
    }

    @Override
    public void updateSmtpConfig(SmtpConfig updatedConfig, String priorVersion) throws Exception {
        synchronized (writeLock) {
            checkVersionsEqual(smtpConfig.version(), priorVersion);
            configService.updateOtherConfig(SMTP_KEY, updatedConfig);
            smtpConfig = updatedConfig;
        }
    }

    @Override
    public void insertAlertConfig(String serverId, AlertConfig alertConfig) throws Exception {
        synchronized (writeLock) {
            List<AlertConfig> configs = Lists.newArrayList(alertConfigs);
            configs.add(alertConfig);
            configService.updateOtherConfig(ALERTS_KEY, configs);
            this.alertConfigs = ImmutableList.copyOf(configs);
        }
    }

    @Override
    public void updateAlertConfig(String serverId, AlertConfig alertConfig, String priorVersion)
            throws IOException {
        synchronized (writeLock) {
            List<AlertConfig> configs = Lists.newArrayList(alertConfigs);
            boolean found = false;
            for (ListIterator<AlertConfig> i = configs.listIterator(); i.hasNext();) {
                if (priorVersion.equals(i.next().version())) {
                    i.set(alertConfig);
                    found = true;
                    break;
                }
            }
            checkState(found, "Alert config not found: %s", priorVersion);
            configService.updateOtherConfig(ALERTS_KEY, configs);
            alertConfigs = ImmutableList.copyOf(configs);
        }
    }

    @Override
    public void deleteAlertConfig(String serverId, String version) throws IOException {
        synchronized (writeLock) {
            List<AlertConfig> configs = Lists.newArrayList(alertConfigs);
            boolean found = false;
            for (ListIterator<AlertConfig> i = configs.listIterator(); i.hasNext();) {
                if (version.equals(i.next().version())) {
                    i.remove();
                    found = true;
                    break;
                }
            }
            checkState(found, "Alert config not found: %s", version);
            configService.updateOtherConfig(ALERTS_KEY, configs);
            alertConfigs = ImmutableList.copyOf(configs);
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

    private void checkVersionsEqual(String version, String priorVersion)
            throws OptimisticLockException {
        if (!version.equals(priorVersion)) {
            throw new OptimisticLockException();
        }
    }

    private void writeAll() throws IOException {
        // linked hash map to preserve ordering when writing to config file
        Map<String, Object> configs = Maps.newLinkedHashMap();
        configs.put(UI_KEY, userInterfaceConfig);
        configs.put(STORAGE_KEY, storageConfig);
        configs.put(SMTP_KEY, smtpConfig);
        configs.put(ALERTS_KEY, alertConfigs);
        configService.updateOtherConfigs(configs);
    }

    private static StorageConfig withCorrectedLists(StorageConfig storageConfig) {
        StorageConfig defaultConfig = ImmutableStorageConfig.builder().build();
        ImmutableList<Integer> rollupExpirationHours =
                fix(storageConfig.rollupExpirationHours(), defaultConfig.rollupExpirationHours());
        ImmutableList<Integer> rollupCappedDatabaseSizesMb =
                fix(storageConfig.rollupCappedDatabaseSizesMb(),
                        defaultConfig.rollupCappedDatabaseSizesMb());
        return ImmutableStorageConfig.builder()
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
