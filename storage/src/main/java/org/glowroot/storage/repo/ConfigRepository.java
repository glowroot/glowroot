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
import org.glowroot.storage.config.FatStorageConfig;
import org.glowroot.storage.config.LdapConfig;
import org.glowroot.storage.config.RoleConfig;
import org.glowroot.storage.config.ServerStorageConfig;
import org.glowroot.storage.config.SmtpConfig;
import org.glowroot.storage.config.StorageConfig;
import org.glowroot.storage.config.UserConfig;
import org.glowroot.storage.config.WebConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AdvancedConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.GaugeConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.InstrumentationConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginProperty;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.TransactionConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.UiConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.UserRecordingConfig;

public interface ConfigRepository {

    String USERS_KEY = "users";
    String ROLES_KEY = "roles";
    String WEB_KEY = "web";
    String STORAGE_KEY = "storage";
    String SMTP_KEY = "smtp";
    String LDAP_KEY = "ldap";

    long ROLLUP_0_INTERVAL_MILLIS =
            Long.getLong("glowroot.internal.rollup.0.intervalMillis", 60 * 1000); // 1 minute
    long ROLLUP_1_INTERVAL_MILLIS =
            Long.getLong("glowroot.internal.rollup.1.intervalMillis", 5 * 60 * 1000); // 5 minutes
    long ROLLUP_2_INTERVAL_MILLIS =
            Long.getLong("glowroot.internal.rollup.2.intervalMillis", 30 * 60 * 1000); // 30 minutes
    long ROLLUP_3_INTERVAL_MILLIS =
            Long.getLong("glowroot.internal.rollup.3.intervalMillis", 4 * 3600 * 1000); // 4 hours

    @Nullable
    TransactionConfig getTransactionConfig(String agentId) throws IOException;

    @Nullable
    UiConfig getUiConfig(String agentId) throws IOException;

    @Nullable
    UserRecordingConfig getUserRecordingConfig(String agentId) throws IOException;

    @Nullable
    AdvancedConfig getAdvancedConfig(String agentId) throws IOException;

    List<GaugeConfig> getGaugeConfigs(String agentId) throws IOException;

    @Nullable
    GaugeConfig getGaugeConfig(String agentId, String version) throws IOException;

    List<AlertConfig> getAlertConfigs(String agentId) throws IOException;

    @Nullable
    AlertConfig getAlertConfig(String agentId, String version) throws IOException;

    List<PluginConfig> getPluginConfigs(String agentId) throws IOException;

    @Nullable
    PluginConfig getPluginConfig(String agentId, String pluginId) throws IOException;

    List<InstrumentationConfig> getInstrumentationConfigs(String agentId) throws IOException;

    @Nullable
    InstrumentationConfig getInstrumentationConfig(String agentId, String version)
            throws IOException;

    List<UserConfig> getUserConfigs();

    @Nullable
    UserConfig getUserConfig(String username) throws Exception;

    @Nullable
    UserConfig getUserConfigCaseInsensitive(String username);

    boolean namedUsersExist();

    List<RoleConfig> getRoleConfigs();

    @Nullable
    RoleConfig getRoleConfig(String name);

    WebConfig getWebConfig();

    FatStorageConfig getFatStorageConfig();

    ServerStorageConfig getServerStorageConfig();

    SmtpConfig getSmtpConfig();

    LdapConfig getLdapConfig();

    void updateTransactionConfig(String agentId, TransactionConfig transactionConfig,
            String priorVersion) throws Exception;

    void insertGaugeConfig(String agentId, GaugeConfig gaugeConfig) throws Exception;

    void updateGaugeConfig(String agentId, GaugeConfig gaugeConfig, String priorVersion)
            throws Exception;

    void deleteGaugeConfig(String agentId, String version) throws Exception;

    void insertAlertConfig(String agentId, AlertConfig alertConfig) throws Exception;

    void updateAlertConfig(String agentId, AlertConfig alertConfig, String priorVersion)
            throws Exception;

    void deleteAlertConfig(String agentId, String version) throws Exception;

    void updateUiConfig(String agentId, UiConfig uiConfig, String priorVersion) throws Exception;

    // only name, type and value of properties is used
    void updatePluginConfig(String agentId, String pluginId, List<PluginProperty> properties,
            String priorVersion) throws Exception;

    void insertInstrumentationConfig(String agentId, InstrumentationConfig instrumentationConfig)
            throws Exception;

    void updateInstrumentationConfig(String agentId, InstrumentationConfig instrumentationConfig,
            String priorVersion) throws Exception;

    void deleteInstrumentationConfigs(String agentId, List<String> versions) throws Exception;

    void insertInstrumentationConfigs(String agentId,
            List<InstrumentationConfig> instrumentationConfigs) throws Exception;

    void updateUserRecordingConfig(String agentId, UserRecordingConfig userRecordingConfig,
            String priorVersion) throws Exception;

    void updateAdvancedConfig(String agentId, AdvancedConfig advancedConfig, String priorVersion)
            throws Exception;

    void insertUserConfig(UserConfig userConfig) throws Exception;

    void updateUserConfig(UserConfig userConfig, String priorVersion) throws Exception;

    void deleteUserConfig(String username) throws Exception;

    void insertRoleConfig(RoleConfig roleConfig) throws Exception;

    void updateRoleConfig(RoleConfig roleConfig, String priorVersion) throws Exception;

    void deleteRoleConfig(String name) throws Exception;

    void updateWebConfig(WebConfig webConfig, String priorVersion) throws Exception;

    void updateFatStorageConfig(FatStorageConfig storageConfig, String priorVersion)
            throws Exception;

    void updateServerStorageConfig(ServerStorageConfig storageConfig, String priorVersion)
            throws Exception;

    void updateSmtpConfig(SmtpConfig smtpConfig, String priorVersion) throws Exception;

    void updateLdapConfig(LdapConfig ldapConfig, String priorVersion) throws Exception;

    StorageConfig getStorageConfig();

    long getGaugeCollectionIntervalMillis();

    List<RollupConfig> getRollupConfigs();

    SecretKey getSecretKey() throws Exception;

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
                            ROLLUP_2_INTERVAL_MILLIS * 16),
                    // default rollup level #3 fixed interval is 4 hours,
                    // making default view threshold 3 days
                    ImmutableRollupConfig.of(ROLLUP_3_INTERVAL_MILLIS,
                            ROLLUP_3_INTERVAL_MILLIS * 18));
        }
    }

    @SuppressWarnings("serial")
    class OptimisticLockException extends Exception {}

    @SuppressWarnings("serial")
    class UserNotFoundException extends Exception {}

    @SuppressWarnings("serial")
    class RoleNotFoundException extends Exception {}

    @SuppressWarnings("serial")
    class DuplicateMBeanObjectNameException extends Exception {}

    @SuppressWarnings("serial")
    class DuplicateUsernameException extends Exception {}

    @SuppressWarnings("serial")
    class DuplicateRoleNameException extends Exception {}
}
