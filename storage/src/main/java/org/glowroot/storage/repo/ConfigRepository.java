/*
 * Copyright 2015-2016 the original author or authors.
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

import org.glowroot.common.util.Styles;
import org.glowroot.storage.config.AlertConfig;
import org.glowroot.storage.config.SmtpConfig;
import org.glowroot.storage.config.StorageConfig;
import org.glowroot.storage.config.UserInterfaceConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AdvancedConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.GaugeConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.InstrumentationConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginProperty;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.TransactionConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.UserRecordingConfig;

public interface ConfigRepository {

    String UI_KEY = "ui";
    String STORAGE_KEY = "storage";
    String SMTP_KEY = "smtp";

    long ROLLUP_0_INTERVAL_MILLIS =
            Long.getLong("glowroot.internal.rollup.0.intervalMillis", 60 * 1000); // 1 minute
    long ROLLUP_1_INTERVAL_MILLIS =
            Long.getLong("glowroot.internal.rollup.1.intervalMillis", 5 * 60 * 1000); // 5 minutes
    long ROLLUP_2_INTERVAL_MILLIS =
            Long.getLong("glowroot.internal.rollup.2.intervalMillis", 30 * 60 * 1000); // 30 minutes

    TransactionConfig getTransactionConfig(String agentId) throws IOException;

    UserRecordingConfig getUserRecordingConfig(String agentId) throws IOException;

    @Nullable
    AdvancedConfig getAdvancedConfig(String agentId) throws IOException;

    List<PluginConfig> getPluginConfigs(String agentId) throws IOException;

    @Nullable
    PluginConfig getPluginConfig(String agentId, String pluginId) throws IOException;

    List<GaugeConfig> getGaugeConfigs(String agentId) throws IOException;

    @Nullable
    GaugeConfig getGaugeConfig(String agentId, String version) throws IOException;

    List<InstrumentationConfig> getInstrumentationConfigs(String agentId) throws IOException;

    @Nullable
    InstrumentationConfig getInstrumentationConfig(String agentId, String version)
            throws IOException;

    UserInterfaceConfig getUserInterfaceConfig() throws Exception;

    StorageConfig getStorageConfig() throws Exception;

    SmtpConfig getSmtpConfig() throws Exception;

    List<AlertConfig> getAlertConfigs(String agentId) throws Exception;

    @Nullable
    AlertConfig getAlertConfig(String agentId, String version) throws Exception;

    void updateTransactionConfig(String agentId, TransactionConfig transactionConfig,
            String priorVersion) throws Exception;

    void updateUserRecordingConfig(String agentId, UserRecordingConfig userRecordingConfig,
            String priorVersion) throws Exception;

    void updateAdvancedConfig(String agentId, AdvancedConfig advancedConfig, String priorVersion)
            throws Exception;

    // only name, type and value of properties is used
    void updatePluginConfig(String agentId, String pluginId, List<PluginProperty> properties,
            String priorVersion) throws Exception;

    void insertInstrumentationConfig(String agentId, InstrumentationConfig instrumentationConfig)
            throws Exception;

    void updateInstrumentationConfig(String agentId, InstrumentationConfig instrumentationConfig,
            String priorVersion) throws Exception;

    void deleteInstrumentationConfig(String agentId, String version) throws Exception;

    void insertGaugeConfig(String agentId, GaugeConfig gaugeConfig) throws Exception;

    void updateGaugeConfig(String agentId, GaugeConfig gaugeConfig, String priorVersion)
            throws Exception;

    void deleteGaugeConfig(String agentId, String version) throws Exception;

    void updateUserInterfaceConfig(UserInterfaceConfig userInterfaceConfig, String priorVersion)
            throws Exception;

    void updateStorageConfig(StorageConfig storageConfig, String priorVersion) throws Exception;

    void updateSmtpConfig(SmtpConfig smtpConfig, String priorVersion) throws Exception;

    void insertAlertConfig(String agentId, AlertConfig alertConfig) throws Exception;

    void updateAlertConfig(String agentId, AlertConfig alertConfig, String priorVersion)
            throws Exception;

    void deleteAlertConfig(String agentId, String version) throws Exception;

    long getGaugeCollectionIntervalMillis();

    List<RollupConfig> getRollupConfigs();

    SecretKey getSecretKey() throws Exception;

    interface DeprecatedConfigListener {
        // the new config is not passed to onChange so that the receiver has to get the latest,
        // this avoids race condition worries that two updates may get sent to the receiver in the
        // wrong order
        void onChange();
    }

    @Value.Immutable
    @Styles.AllParameters
    abstract class RollupConfig {

        public abstract long intervalMillis();
        public abstract long viewThresholdMillis();

        public static List<RollupConfig> buildRollupConfigs() {
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
    class OptimisticLockException extends Exception {}

    @SuppressWarnings("serial")
    class DuplicateMBeanObjectNameException extends Exception {}
}
