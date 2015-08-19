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
package org.glowroot.local;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.ListIterator;

import javax.annotation.Nullable;
import javax.crypto.SecretKey;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import org.glowroot.agent.config.ConfigService;
import org.glowroot.common.config.AdvancedConfig;
import org.glowroot.common.config.AlertConfig;
import org.glowroot.common.config.Config;
import org.glowroot.common.config.GaugeConfig;
import org.glowroot.common.config.ImmutableConfig;
import org.glowroot.common.config.InstrumentationConfig;
import org.glowroot.common.config.PluginConfig;
import org.glowroot.common.config.SmtpConfig;
import org.glowroot.common.config.StorageConfig;
import org.glowroot.common.config.TransactionConfig;
import org.glowroot.common.config.UserInterfaceConfig;
import org.glowroot.common.config.UserRecordingConfig;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.util.Encryption;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.plugin.api.config.ConfigListener;

import static com.google.common.base.Preconditions.checkState;

public class ConfigRepositoryImpl implements ConfigRepository {

    private final ConfigService configService;
    private final File secretFile;

    // volatile not needed as access is guarded by secretFile
    private @MonotonicNonNull SecretKey secretKey;

    ConfigRepositoryImpl(File baseDir, ConfigService configService) {
        this.configService = configService;
        secretFile = new File(baseDir, "secret");
    }

    @Override
    public TransactionConfig getTransactionConfig() {
        return configService.getTransactionConfig();
    }

    @Override
    public UserInterfaceConfig getUserInterfaceConfig() {
        return configService.getUserInterfaceConfig();
    }

    @Override
    public StorageConfig getStorageConfig() {
        return configService.getStorageConfig();
    }

    @Override
    public SmtpConfig getSmtpConfig() {
        return configService.getSmtpConfig();
    }

    @Override
    public UserRecordingConfig getUserRecordingConfig() {
        return configService.getUserRecordingConfig();
    }

    @Override
    public AdvancedConfig getAdvancedConfig() {
        return configService.getAdvancedConfig();
    }

    @Override
    public @Nullable PluginConfig getPluginConfig(String pluginId) {
        return configService.getPluginConfig(pluginId);
    }

    @Override
    public List<InstrumentationConfig> getInstrumentationConfigs() {
        return configService.getInstrumentationConfigs();
    }

    @Override
    public @Nullable InstrumentationConfig getInstrumentationConfig(String version) {
        for (InstrumentationConfig instrumentationConfig : configService
                .getInstrumentationConfigs()) {
            if (instrumentationConfig.version().equals(version)) {
                return instrumentationConfig;
            }
        }
        return null;
    }

    @Override
    public List<GaugeConfig> getGaugeConfigs() {
        return configService.getGaugeConfigs();
    }

    @Override
    public @Nullable GaugeConfig getGaugeConfig(String version) {
        for (GaugeConfig gaugeConfig : configService.getGaugeConfigs()) {
            if (gaugeConfig.version().equals(version)) {
                return gaugeConfig;
            }
        }
        return null;
    }

    @Override
    public List<AlertConfig> getAlertConfigs() {
        return configService.getAlertConfigs();
    }

    @Override
    public @Nullable AlertConfig getAlertConfig(String version) {
        for (AlertConfig alertConfig : configService.getAlertConfigs()) {
            if (alertConfig.version().equals(version)) {
                return alertConfig;
            }
        }
        return null;
    }

    @Override
    public String updateTransactionConfig(TransactionConfig transactionConfig, String priorVersion)
            throws Exception {
        synchronized (configService.getWriteLock()) {
            Config config = configService.getConfig();
            checkVersionsEqual(config.transactionConfig().version(), priorVersion);
            Config updatedConfig = ImmutableConfig.builder().copyFrom(config)
                    .transactionConfig(transactionConfig).build();
            configService.updateConfig(updatedConfig);
        }
        configService.notifyConfigListeners();
        return transactionConfig.version();
    }

    @Override
    public String updateUserInterfaceConfig(UserInterfaceConfig userInterfaceConfig,
            String priorVersion) throws Exception {
        synchronized (configService.getWriteLock()) {
            Config config = configService.getConfig();
            checkVersionsEqual(config.userInterfaceConfig().version(), priorVersion);
            Config updatedConfig = ImmutableConfig.builder().copyFrom(config)
                    .userInterfaceConfig(userInterfaceConfig).build();
            configService.updateConfig(updatedConfig);
        }
        configService.notifyConfigListeners();
        return userInterfaceConfig.version();
    }

    @Override
    public String updateStorageConfig(StorageConfig storageConfig, String priorVersion)
            throws Exception {
        synchronized (configService.getWriteLock()) {
            Config config = configService.getConfig();
            checkVersionsEqual(config.storageConfig().version(), priorVersion);
            Config updatedConfig =
                    ImmutableConfig.builder().copyFrom(config).storageConfig(storageConfig).build();
            configService.updateConfig(updatedConfig);
        }
        configService.notifyConfigListeners();
        return storageConfig.version();
    }

    @Override
    public String updateSmtpConfig(SmtpConfig smtpConfig, String priorVersion) throws Exception {
        synchronized (configService.getWriteLock()) {
            Config config = configService.getConfig();
            checkVersionsEqual(config.smtpConfig().version(), priorVersion);
            Config updatedConfig =
                    ImmutableConfig.builder().copyFrom(config).smtpConfig(smtpConfig).build();
            configService.updateConfig(updatedConfig);
        }
        configService.notifyConfigListeners();
        return smtpConfig.version();
    }

    @Override
    public String updateUserRecordingConfig(UserRecordingConfig userRecordingConfig,
            String priorVersion) throws Exception {
        synchronized (configService.getWriteLock()) {
            Config config = configService.getConfig();
            checkVersionsEqual(config.userRecordingConfig().version(), priorVersion);
            Config updatedConfig = ImmutableConfig.builder().copyFrom(config)
                    .userRecordingConfig(userRecordingConfig).build();
            configService.updateConfig(updatedConfig);
        }
        configService.notifyConfigListeners();
        return userRecordingConfig.version();
    }

    @Override
    public String updateAdvancedConfig(AdvancedConfig advancedConfig, String priorVersion)
            throws Exception {
        synchronized (configService.getWriteLock()) {
            Config config = configService.getConfig();
            checkVersionsEqual(config.advancedConfig().version(), priorVersion);
            Config updatedConfig = ImmutableConfig.builder().copyFrom(config)
                    .advancedConfig(advancedConfig).build();
            configService.updateConfig(updatedConfig);
        }
        configService.notifyConfigListeners();
        return advancedConfig.version();
    }

    @Override
    public String updatePluginConfig(PluginConfig pluginConfig, String priorVersion)
            throws Exception {
        synchronized (configService.getWriteLock()) {
            Config config = configService.getConfig();
            List<PluginConfig> pluginConfigs = Lists.newArrayList(config.pluginConfigs());
            boolean found = false;
            for (ListIterator<PluginConfig> i = pluginConfigs.listIterator(); i.hasNext();) {
                PluginConfig loopPluginConfig = i.next();
                if (pluginConfig.id().equals(loopPluginConfig.id())) {
                    checkVersionsEqual(loopPluginConfig.version(), priorVersion);
                    i.set(pluginConfig);
                    found = true;
                    break;
                }
            }
            checkState(found, "Plugin config not found: %s", pluginConfig.id());
            Config updatedConfig =
                    ImmutableConfig.builder().copyFrom(config).pluginConfigs(pluginConfigs).build();

            configService.updateConfig(updatedConfig);
        }
        configService.notifyPluginConfigListeners(pluginConfig.id());
        return pluginConfig.version();
    }

    @Override
    public String insertInstrumentationConfig(InstrumentationConfig instrumentationConfig)
            throws IOException {
        synchronized (configService.getWriteLock()) {
            Config config = configService.getConfig();
            List<InstrumentationConfig> configs =
                    Lists.newArrayList(config.instrumentationConfigs());
            configs.add(instrumentationConfig);
            Config updatedConfig = ImmutableConfig.builder().copyFrom(config)
                    .instrumentationConfigs(configs).build();
            configService.updateConfig(updatedConfig);
        }
        configService.notifyConfigListeners();
        return instrumentationConfig.version();
    }

    @Override
    public String updateInstrumentationConfig(InstrumentationConfig instrumentationConfig,
            String priorVersion) throws IOException {
        synchronized (configService.getWriteLock()) {
            Config config = configService.getConfig();
            List<InstrumentationConfig> configs =
                    Lists.newArrayList(config.instrumentationConfigs());
            boolean found = false;
            for (ListIterator<InstrumentationConfig> i = configs.listIterator(); i.hasNext();) {
                if (priorVersion.equals(i.next().version())) {
                    i.set(instrumentationConfig);
                    found = true;
                    break;
                }
            }
            checkState(found, "Instrumentation config not found: %s", priorVersion);
            Config updatedConfig = ImmutableConfig.builder().copyFrom(config)
                    .instrumentationConfigs(configs).build();
            configService.updateConfig(updatedConfig);
        }
        configService.notifyConfigListeners();
        return instrumentationConfig.version();
    }

    @Override
    public void deleteInstrumentationConfig(String version) throws IOException {
        synchronized (configService.getWriteLock()) {
            Config config = configService.getConfig();
            List<InstrumentationConfig> configs =
                    Lists.newArrayList(config.instrumentationConfigs());
            boolean found = false;
            for (ListIterator<InstrumentationConfig> i = configs.listIterator(); i.hasNext();) {
                if (version.equals(i.next().version())) {
                    i.remove();
                    found = true;
                    break;
                }
            }
            checkState(found, "Instrumentation config not found: %s", version);
            Config updatedConfig = ImmutableConfig.builder().copyFrom(config)
                    .instrumentationConfigs(configs).build();
            configService.updateConfig(updatedConfig);
        }
        configService.notifyConfigListeners();
    }

    @Override
    public String insertGaugeConfig(GaugeConfig gaugeConfig) throws Exception {
        synchronized (configService.getWriteLock()) {
            Config config = configService.getConfig();
            List<GaugeConfig> gaugeConfigs = Lists.newArrayList(config.gaugeConfigs());
            // check for duplicate mbeanObjectName
            for (GaugeConfig loopConfig : gaugeConfigs) {
                if (loopConfig.mbeanObjectName().equals(gaugeConfig.mbeanObjectName())) {
                    throw new DuplicateMBeanObjectNameException();
                }
            }
            gaugeConfigs.add(gaugeConfig);
            Config updatedConfig =
                    ImmutableConfig.builder().copyFrom(config).gaugeConfigs(gaugeConfigs).build();
            configService.updateConfig(updatedConfig);
        }
        configService.notifyConfigListeners();
        return gaugeConfig.version();
    }

    @Override
    public String updateGaugeConfig(GaugeConfig gaugeConfig, String priorVersion) throws Exception {
        synchronized (configService.getWriteLock()) {
            Config config = configService.getConfig();
            List<GaugeConfig> gaugeConfigs = Lists.newArrayList(config.gaugeConfigs());
            boolean found = false;
            for (ListIterator<GaugeConfig> i = gaugeConfigs.listIterator(); i.hasNext();) {
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
            Config updatedConfig =
                    ImmutableConfig.builder().copyFrom(config).gaugeConfigs(gaugeConfigs).build();
            configService.updateConfig(updatedConfig);
        }
        configService.notifyConfigListeners();
        return gaugeConfig.version();
    }

    @Override
    public void deleteGaugeConfig(String version) throws IOException {
        synchronized (configService.getWriteLock()) {
            Config config = configService.getConfig();
            List<GaugeConfig> gaugeConfigs = Lists.newArrayList(config.gaugeConfigs());
            boolean found = false;
            for (ListIterator<GaugeConfig> i = gaugeConfigs.listIterator(); i.hasNext();) {
                if (version.equals(i.next().version())) {
                    i.remove();
                    found = true;
                    break;
                }
            }
            checkState(found, "Gauge config not found: %s", version);
            Config updatedConfig =
                    ImmutableConfig.builder().copyFrom(config).gaugeConfigs(gaugeConfigs).build();
            configService.updateConfig(updatedConfig);
        }
        configService.notifyConfigListeners();
    }

    @Override
    public String insertAlertConfig(AlertConfig alertConfig) throws Exception {
        synchronized (configService.getWriteLock()) {
            Config config = configService.getConfig();
            List<AlertConfig> alertConfigs = Lists.newArrayList(config.alertConfigs());
            alertConfigs.add(alertConfig);
            Config updatedConfig =
                    ImmutableConfig.builder().copyFrom(config).alertConfigs(alertConfigs).build();
            configService.updateConfig(updatedConfig);
        }
        configService.notifyConfigListeners();
        return alertConfig.version();
    }

    @Override
    public String updateAlertConfig(AlertConfig alertConfig, String priorVersion)
            throws IOException {
        synchronized (configService.getWriteLock()) {
            Config config = configService.getConfig();
            List<AlertConfig> alertConfigs = Lists.newArrayList(config.alertConfigs());
            boolean found = false;
            for (ListIterator<AlertConfig> i = alertConfigs.listIterator(); i.hasNext();) {
                if (priorVersion.equals(i.next().version())) {
                    i.set(alertConfig);
                    found = true;
                    break;
                }
            }
            checkState(found, "Alert config not found: %s", priorVersion);
            Config updatedConfig =
                    ImmutableConfig.builder().copyFrom(config).alertConfigs(alertConfigs).build();
            configService.updateConfig(updatedConfig);
        }
        configService.notifyConfigListeners();
        return alertConfig.version();
    }

    @Override
    public void deleteAlertConfig(String version) throws IOException {
        synchronized (configService.getWriteLock()) {
            Config config = configService.getConfig();
            List<AlertConfig> alertConfigs = Lists.newArrayList(config.alertConfigs());
            boolean found = false;
            for (ListIterator<AlertConfig> i = alertConfigs.listIterator(); i.hasNext();) {
                if (version.equals(i.next().version())) {
                    i.remove();
                    found = true;
                    break;
                }
            }
            checkState(found, "Alert config not found: %s", version);
            Config updatedConfig =
                    ImmutableConfig.builder().copyFrom(config).alertConfigs(alertConfigs).build();
            configService.updateConfig(updatedConfig);
        }
        configService.notifyConfigListeners();
    }

    @Override
    public long getGaugeCollectionIntervalMillis() {
        return configService.getGaugeCollectionIntervalMillis();
    }

    @Override
    public ImmutableList<RollupConfig> getRollupConfigs() {
        return configService.getRollupConfigs();
    }

    @Override
    public String getDefaultDisplayedTransactionType() {
        return configService.getDefaultDisplayedTransactionType();
    }

    @Override
    public ImmutableList<String> getAllTransactionTypes() {
        return configService.getAllTransactionTypes();
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
    public void addListener(ConfigListener listener) {
        configService.addConfigListener(listener);
    }

    private void checkVersionsEqual(String version, String priorVersion)
            throws OptimisticLockException {
        if (!version.equals(priorVersion)) {
            throw new OptimisticLockException();
        }
    }

    @Override
    @OnlyUsedByTests
    public void resetAllConfig() throws IOException {
        configService.resetAllConfig();
    }
}
