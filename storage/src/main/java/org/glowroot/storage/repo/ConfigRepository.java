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

    TransactionConfig getTransactionConfig(String server);

    UserRecordingConfig getUserRecordingConfig(String server);

    AdvancedConfig getAdvancedConfig(String server);

    @Nullable
    PluginConfig getPluginConfig(String server, String pluginId);

    List<InstrumentationConfig> getInstrumentationConfigs(String server);

    @Nullable
    InstrumentationConfig getInstrumentationConfig(String server, String version);

    List<GaugeConfig> getGaugeConfigs(String server);

    @Nullable
    GaugeConfig getGaugeConfig(String server, String version);

    List<AlertConfig> getAlertConfigs(String server);

    @Nullable
    AlertConfig getAlertConfig(String server, String version);

    UserInterfaceConfig getUserInterfaceConfig();

    StorageConfig getStorageConfig();

    SmtpConfig getSmtpConfig();

    String updateTransactionConfig(String server, TransactionConfig transactionConfig,
            String priorVersion) throws Exception;

    String updateUserRecordingConfig(String server, UserRecordingConfig userRecordingConfig,
            String priorVersion) throws Exception;

    String updateAdvancedConfig(String server, AdvancedConfig advancedConfig, String priorVersion)
            throws Exception;

    String updatePluginConfig(String server, PluginConfig pluginConfig, String priorVersion)
            throws Exception;

    String insertInstrumentationConfig(String server, InstrumentationConfig instrumentationConfig)
            throws IOException;

    String updateInstrumentationConfig(String server, InstrumentationConfig instrumentationConfig,
            String priorVersion) throws IOException;

    void deleteInstrumentationConfig(String server, String version) throws IOException;

    String insertGaugeConfig(String server, GaugeConfig gaugeConfig) throws Exception;

    String updateGaugeConfig(String server, GaugeConfig gaugeConfig, String priorVersion)
            throws Exception;

    void deleteGaugeConfig(String server, String version) throws IOException;

    String insertAlertConfig(String server, AlertConfig alertConfig) throws Exception;

    String updateAlertConfig(String server, AlertConfig alertConfig, String priorVersion)
            throws IOException;

    void deleteAlertConfig(String server, String version) throws IOException;

    String updateUserInterfaceConfig(UserInterfaceConfig userInterfaceConfig, String priorVersion)
            throws Exception;

    String updateStorageConfig(StorageConfig storageConfig, String priorVersion) throws Exception;

    String updateSmtpConfig(SmtpConfig smtpConfig, String priorVersion) throws Exception;

    List<String> getAllTransactionTypes(String server);

    String getDefaultDisplayedTransactionType(String server);

    long getGaugeCollectionIntervalMillis();

    ImmutableList<RollupConfig> getRollupConfigs();

    SecretKey getSecretKey() throws Exception;

    // only listens for UserInterfaceConfig, StorageConfig, SmtpConfig, AlertConfig
    void addListener(ConfigListener listener);

    @OnlyUsedByTests
    void resetAllConfig(String server) throws IOException;

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
