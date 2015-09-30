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
package org.glowroot.storage.repo;

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
import org.glowroot.storage.repo.config.AlertConfig;
import org.glowroot.storage.repo.config.SmtpConfig;
import org.glowroot.storage.repo.config.StorageConfig;
import org.glowroot.storage.repo.config.UserInterfaceConfig;

public interface ConfigRepository {

    TransactionConfig getTransactionConfig(long serverId);

    UserRecordingConfig getUserRecordingConfig(long serverId);

    AdvancedConfig getAdvancedConfig(long serverId);

    @Nullable
    PluginConfig getPluginConfig(long serverId, String pluginId);

    List<InstrumentationConfig> getInstrumentationConfigs(long serverId);

    @Nullable
    InstrumentationConfig getInstrumentationConfig(long serverId, String version);

    List<GaugeConfig> getGaugeConfigs(long serverId);

    @Nullable
    GaugeConfig getGaugeConfig(long serverId, String version);

    List<AlertConfig> getAlertConfigs(long serverId);

    @Nullable
    AlertConfig getAlertConfig(long serverId, String version);

    UserInterfaceConfig getUserInterfaceConfig();

    StorageConfig getStorageConfig();

    SmtpConfig getSmtpConfig();

    String updateTransactionConfig(long serverId, TransactionConfig transactionConfig,
            String priorVersion) throws Exception;

    String updateUserRecordingConfig(long serverId, UserRecordingConfig userRecordingConfig,
            String priorVersion) throws Exception;

    String updateAdvancedConfig(long serverId, AdvancedConfig advancedConfig, String priorVersion)
            throws Exception;

    String updatePluginConfig(long serverId, PluginConfig pluginConfig, String priorVersion)
            throws Exception;

    String insertInstrumentationConfig(long serverId, InstrumentationConfig instrumentationConfig)
            throws IOException;

    String updateInstrumentationConfig(long serverId, InstrumentationConfig instrumentationConfig,
            String priorVersion) throws IOException;

    void deleteInstrumentationConfig(long serverId, String version) throws IOException;

    String insertGaugeConfig(long serverId, GaugeConfig gaugeConfig) throws Exception;

    String updateGaugeConfig(long serverId, GaugeConfig gaugeConfig, String priorVersion)
            throws Exception;

    void deleteGaugeConfig(long serverId, String version) throws IOException;

    String insertAlertConfig(long serverId, AlertConfig alertConfig) throws Exception;

    String updateAlertConfig(long serverId, AlertConfig alertConfig, String priorVersion)
            throws IOException;

    void deleteAlertConfig(long serverId, String version) throws IOException;

    String updateUserInterfaceConfig(UserInterfaceConfig userInterfaceConfig, String priorVersion)
            throws Exception;

    String updateStorageConfig(StorageConfig storageConfig, String priorVersion) throws Exception;

    String updateSmtpConfig(SmtpConfig smtpConfig, String priorVersion) throws Exception;

    List<String> getAllTransactionTypes(long serverId);

    String getDefaultDisplayedTransactionType(long serverId);

    long getGaugeCollectionIntervalMillis();

    ImmutableList<RollupConfig> getRollupConfigs();

    SecretKey getSecretKey() throws Exception;

    // only listens for UserInterfaceConfig, StorageConfig, SmtpConfig, AlertConfig
    void addListener(ConfigListener listener);

    @OnlyUsedByTests
    void resetAllConfig(long serverId) throws IOException;

    public interface ConfigListener {
        // the new config is not passed to onChange so that the receiver has to get the latest,
        // this avoids race condition worries that two updates may get sent to the receiver in the
        // wrong order
        void onChange();
    }

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
