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
package org.glowroot.server.repo;

import java.io.IOException;
import java.util.List;

import javax.annotation.Nullable;
import javax.crypto.SecretKey;

import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;

import org.glowroot.common.config.AdvancedConfig;
import org.glowroot.common.config.GaugeConfig;
import org.glowroot.common.config.InstrumentationConfig;
import org.glowroot.common.config.PluginConfig;
import org.glowroot.common.config.TransactionConfig;
import org.glowroot.common.config.UserRecordingConfig;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.common.util.Styles;
import org.glowroot.plugin.api.config.ConfigListener;
import org.glowroot.server.repo.config.AlertConfig;
import org.glowroot.server.repo.config.SmtpConfig;
import org.glowroot.server.repo.config.StorageConfig;
import org.glowroot.server.repo.config.UserInterfaceConfig;

public interface ConfigRepository {

    TransactionConfig getTransactionConfig();

    UserRecordingConfig getUserRecordingConfig();

    AdvancedConfig getAdvancedConfig();

    @Nullable
    PluginConfig getPluginConfig(String pluginId);

    List<InstrumentationConfig> getInstrumentationConfigs();

    @Nullable
    InstrumentationConfig getInstrumentationConfig(String version);

    List<GaugeConfig> getGaugeConfigs();

    @Nullable
    GaugeConfig getGaugeConfig(String version);

    UserInterfaceConfig getUserInterfaceConfig();

    StorageConfig getStorageConfig();

    SmtpConfig getSmtpConfig();

    List<AlertConfig> getAlertConfigs();

    @Nullable
    AlertConfig getAlertConfig(String version);

    String updateTransactionConfig(TransactionConfig transactionConfig, String priorVersion)
            throws Exception;

    String updateUserRecordingConfig(UserRecordingConfig userRecordingConfig, String priorVersion)
            throws Exception;

    String updateAdvancedConfig(AdvancedConfig advancedConfig, String priorVersion)
            throws Exception;

    String updatePluginConfig(PluginConfig pluginConfig, String priorVersion) throws Exception;

    String insertInstrumentationConfig(InstrumentationConfig instrumentationConfig)
            throws IOException;

    String updateInstrumentationConfig(InstrumentationConfig instrumentationConfig,
            String priorVersion) throws IOException;

    void deleteInstrumentationConfig(String version) throws IOException;

    String insertGaugeConfig(GaugeConfig gaugeConfig) throws Exception;

    String updateGaugeConfig(GaugeConfig gaugeConfig, String priorVersion) throws Exception;

    void deleteGaugeConfig(String version) throws IOException;

    String updateUserInterfaceConfig(UserInterfaceConfig userInterfaceConfig, String priorVersion)
            throws Exception;

    String updateStorageConfig(StorageConfig storageConfig, String priorVersion) throws Exception;

    String updateSmtpConfig(SmtpConfig smtpConfig, String priorVersion) throws Exception;

    String insertAlertConfig(AlertConfig alertConfig) throws Exception;

    String updateAlertConfig(AlertConfig alertConfig, String priorVersion) throws IOException;

    void deleteAlertConfig(String version) throws IOException;

    @OnlyUsedByTests
    void resetAllConfig() throws IOException;

    List<String> getAllTransactionTypes();

    String getDefaultDisplayedTransactionType();

    SecretKey getSecretKey() throws Exception;

    long getGaugeCollectionIntervalMillis();

    ImmutableList<RollupConfig> getRollupConfigs();

    // only listens for UserInterfaceConfig, StorageConfig, SmtpConfig, AlertConfig
    void addListener(ConfigListener listener);

    @Value.Immutable
    @Styles.AllParameters
    public interface RollupConfig {
        long intervalMillis();
        long viewThresholdMillis();
    }

    @SuppressWarnings("serial")
    public static class OptimisticLockException extends Exception {}

    @SuppressWarnings("serial")
    public static class DuplicateMBeanObjectNameException extends Exception {}
}
