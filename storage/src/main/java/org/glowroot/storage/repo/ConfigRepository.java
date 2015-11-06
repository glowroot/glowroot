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
import org.glowroot.common.util.Styles;
import org.glowroot.storage.repo.config.AlertConfig;
import org.glowroot.storage.repo.config.SmtpConfig;
import org.glowroot.storage.repo.config.StorageConfig;
import org.glowroot.storage.repo.config.UserInterfaceConfig;

public interface ConfigRepository {

    String UI_KEY = "ui";
    String STORAGE_KEY = "storage";
    String SMTP_KEY = "smtp";
    String ALERTS_KEY = "alerts";

    long ROLLUP_0_INTERVAL_MILLIS =
            Long.getLong("glowroot.internal.rollup.0.intervalMillis", 60 * 1000); // 1 minute
    long ROLLUP_1_INTERVAL_MILLIS =
            Long.getLong("glowroot.internal.rollup.1.intervalMillis", 5 * 60 * 1000); // 5 minutes
    long ROLLUP_2_INTERVAL_MILLIS =
            Long.getLong("glowroot.internal.rollup.2.intervalMillis", 30 * 60 * 1000); // 30 minutes

    TransactionConfig getTransactionConfig(String serverId);

    UserRecordingConfig getUserRecordingConfig(String serverId);

    AdvancedConfig getAdvancedConfig(String serverId);

    @Nullable
    PluginConfig getPluginConfig(String serverId, String pluginId);

    List<InstrumentationConfig> getInstrumentationConfigs(String serverId);

    @Nullable
    InstrumentationConfig getInstrumentationConfig(String serverId, String version);

    List<GaugeConfig> getGaugeConfigs(String serverId);

    @Nullable
    GaugeConfig getGaugeConfig(String serverId, String version);

    UserInterfaceConfig getUserInterfaceConfig() throws Exception;

    StorageConfig getStorageConfig() throws Exception;

    SmtpConfig getSmtpConfig() throws Exception;

    List<AlertConfig> getAlertConfigs(String serverId) throws Exception;

    @Nullable
    AlertConfig getAlertConfig(String serverId, String version) throws Exception;

    void updateTransactionConfig(String serverId, TransactionConfig transactionConfig,
            String priorVersion) throws Exception;

    void updateUserRecordingConfig(String serverId, UserRecordingConfig userRecordingConfig,
            String priorVersion) throws Exception;

    void updateAdvancedConfig(String serverId, AdvancedConfig advancedConfig, String priorVersion)
            throws Exception;

    void updatePluginConfig(String serverId, PluginConfig pluginConfig, String priorVersion)
            throws Exception;

    void insertInstrumentationConfig(String serverId, InstrumentationConfig instrumentationConfig)
            throws IOException;

    void updateInstrumentationConfig(String serverId, InstrumentationConfig instrumentationConfig,
            String priorVersion) throws IOException;

    void deleteInstrumentationConfig(String serverId, String version) throws IOException;

    void insertGaugeConfig(String serverId, GaugeConfig gaugeConfig) throws Exception;

    void updateGaugeConfig(String serverId, GaugeConfig gaugeConfig, String priorVersion)
            throws Exception;

    void deleteGaugeConfig(String serverId, String version) throws IOException;

    void updateUserInterfaceConfig(UserInterfaceConfig userInterfaceConfig, String priorVersion)
            throws Exception;

    void updateStorageConfig(StorageConfig storageConfig, String priorVersion) throws Exception;

    void updateSmtpConfig(SmtpConfig smtpConfig, String priorVersion) throws Exception;

    void insertAlertConfig(String serverId, AlertConfig alertConfig) throws Exception;

    void updateAlertConfig(String serverId, AlertConfig alertConfig, String priorVersion)
            throws Exception;

    void deleteAlertConfig(String serverId, String version) throws Exception;

    long getGaugeCollectionIntervalMillis();

    ImmutableList<RollupConfig> getRollupConfigs();

    SecretKey getSecretKey() throws Exception;

    public interface DeprecatedConfigListener {
        // the new config is not passed to onChange so that the receiver has to get the latest,
        // this avoids race condition worries that two updates may get sent to the receiver in the
        // wrong order
        void onChange();
    }

    @Value.Immutable
    @Styles.AllParameters
    public abstract class RollupConfig {

        public abstract long intervalMillis();
        public abstract long viewThresholdMillis();

        public static ImmutableList<RollupConfig> buildRollupConfigs() {
            return ImmutableList.<RollupConfig>of(
                    // default rollup level #0 fixed interval is 1 minute,
                    // making default view threshold 15 min
                    ImmutableRollupConfig.of(ROLLUP_0_INTERVAL_MILLIS,
                            ROLLUP_0_INTERVAL_MILLIS * 15),
                    // default rollup level #1 fixed interval is 5 minutes,
                    // making default view threshold 1 hour
                    ImmutableRollupConfig.of(ROLLUP_1_INTERVAL_MILLIS,
                            ROLLUP_1_INTERVAL_MILLIS * 12),
                    // default rollup level #2 fixed interval is 30 minutes,
                    // making default view threshold 8 hour
                    ImmutableRollupConfig.of(ROLLUP_2_INTERVAL_MILLIS,
                            ROLLUP_2_INTERVAL_MILLIS * 16));
        }
    }

    @SuppressWarnings("serial")
    public static class OptimisticLockException extends Exception {}

    @SuppressWarnings("serial")
    public static class DuplicateMBeanObjectNameException extends Exception {}
}
