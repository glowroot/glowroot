/*
 * Copyright 2015-2017 the original author or authors.
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
package org.glowroot.common.repo;

import java.util.List;

import javax.annotation.Nullable;
import javax.crypto.SecretKey;

import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;

import org.glowroot.common.config.AgentRollupConfig;
import org.glowroot.common.config.CentralStorageConfig;
import org.glowroot.common.config.FatStorageConfig;
import org.glowroot.common.config.LdapConfig;
import org.glowroot.common.config.RoleConfig;
import org.glowroot.common.config.SmtpConfig;
import org.glowroot.common.config.StorageConfig;
import org.glowroot.common.config.UserConfig;
import org.glowroot.common.config.WebConfig;
import org.glowroot.common.util.Styles;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AdvancedConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.GaugeConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.InstrumentationConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginProperty;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.TransactionConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.UiConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.UserRecordingConfig;

import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MINUTES;

public interface ConfigRepository {

    public static final int GAUGE_VIEW_THRESHOLD_MULTIPLIER = 4;

    String USERS_KEY = "users";
    String ROLES_KEY = "roles";
    String WEB_KEY = "web";
    String STORAGE_KEY = "storage";
    String SMTP_KEY = "smtp";
    String LDAP_KEY = "ldap";

    long ROLLUP_0_INTERVAL_MILLIS =
            Long.getLong("glowroot.internal.rollup.0.intervalMillis", MINUTES.toMillis(1));
    long ROLLUP_1_INTERVAL_MILLIS =
            Long.getLong("glowroot.internal.rollup.1.intervalMillis", MINUTES.toMillis(5));
    long ROLLUP_2_INTERVAL_MILLIS =
            Long.getLong("glowroot.internal.rollup.2.intervalMillis", MINUTES.toMillis(30));
    long ROLLUP_3_INTERVAL_MILLIS =
            Long.getLong("glowroot.internal.rollup.3.intervalMillis", HOURS.toMillis(4));

    TransactionConfig getTransactionConfig(String agentId) throws Exception;

    // central supports ui config on rollups
    UiConfig getUiConfig(String agentRollupId) throws Exception;

    UserRecordingConfig getUserRecordingConfig(String agentId) throws Exception;

    // central supports advanced config on rollups
    // (maxAggregateQueriesPerType and maxAggregateServiceCallsPerType)
    AdvancedConfig getAdvancedConfig(String agentRollupId) throws Exception;

    List<GaugeConfig> getGaugeConfigs(String agentId) throws Exception;

    @Nullable
    GaugeConfig getGaugeConfig(String agentId, String version) throws Exception;

    // central supports alert configs on rollups
    List<AlertConfig> getAlertConfigs(String agentRollupId) throws Exception;

    // central supports alert configs on rollups
    @Nullable
    AlertConfig getAlertConfig(String agentRollupId, String alertConfigId) throws Exception;

    List<PluginConfig> getPluginConfigs(String agentId) throws Exception;

    @Nullable
    PluginConfig getPluginConfig(String agentId, String pluginId) throws Exception;

    List<InstrumentationConfig> getInstrumentationConfigs(String agentId) throws Exception;

    @Nullable
    InstrumentationConfig getInstrumentationConfig(String agentId, String version) throws Exception;

    @Nullable
    AgentRollupConfig getAgentRollupConfig(String agentRollupId) throws Exception;

    List<UserConfig> getUserConfigs();

    @Nullable
    UserConfig getUserConfig(String username) throws Exception;

    @Nullable
    UserConfig getUserConfigCaseInsensitive(String username) throws Exception;

    boolean namedUsersExist() throws Exception;

    List<RoleConfig> getRoleConfigs() throws Exception;

    @Nullable
    RoleConfig getRoleConfig(String name) throws Exception;

    WebConfig getWebConfig() throws Exception;

    FatStorageConfig getFatStorageConfig() throws Exception;

    CentralStorageConfig getCentralStorageConfig() throws Exception;

    SmtpConfig getSmtpConfig() throws Exception;

    LdapConfig getLdapConfig() throws Exception;

    void updateTransactionConfig(String agentId, TransactionConfig transactionConfig,
            String priorVersion) throws Exception;

    void insertGaugeConfig(String agentId, GaugeConfig gaugeConfig) throws Exception;

    void updateGaugeConfig(String agentId, GaugeConfig gaugeConfig, String priorVersion)
            throws Exception;

    void deleteGaugeConfig(String agentId, String version) throws Exception;

    // central supports alert configs on rollups
    // returns id
    String insertAlertConfig(String agentRollupId, AlertConfig alertConfigWithoutId)
            throws Exception;

    // central supports alert configs on rollups
    void updateAlertConfig(String agentRollupId, AlertConfig alertConfig, String priorVersion)
            throws Exception;

    // central supports alert configs on rollups
    void deleteAlertConfig(String agentRollupId, String alertConfigId) throws Exception;

    // central supports ui config on rollups
    void updateUiConfig(String agentRollupId, UiConfig uiConfig, String priorVersion)
            throws Exception;

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

    // central supports advanced config on rollups
    // (maxAggregateQueriesPerType and maxAggregateServiceCallsPerType)
    void updateAdvancedConfig(String agentRollupId, AdvancedConfig advancedConfig,
            String priorVersion) throws Exception;

    void updateAgentRollupConfig(AgentRollupConfig agentRollupConfig, String priorVersion)
            throws Exception;

    void deleteAgentRollupConfig(String agentRollupId) throws Exception;

    void insertUserConfig(UserConfig userConfig) throws Exception;

    void updateUserConfig(UserConfig userConfig, String priorVersion) throws Exception;

    void deleteUserConfig(String username) throws Exception;

    void insertRoleConfig(RoleConfig roleConfig) throws Exception;

    void updateRoleConfig(RoleConfig roleConfig, String priorVersion) throws Exception;

    void deleteRoleConfig(String name) throws Exception;

    void updateWebConfig(WebConfig webConfig, String priorVersion) throws Exception;

    void updateFatStorageConfig(FatStorageConfig storageConfig, String priorVersion)
            throws Exception;

    void updateCentralStorageConfig(CentralStorageConfig storageConfig, String priorVersion)
            throws Exception;

    void updateSmtpConfig(SmtpConfig smtpConfig, String priorVersion) throws Exception;

    void updateLdapConfig(LdapConfig ldapConfig, String priorVersion) throws Exception;

    StorageConfig getStorageConfig() throws Exception;

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
    class AgentConfigNotFoundException extends Exception {}

    @SuppressWarnings("serial")
    class UserNotFoundException extends Exception {}

    @SuppressWarnings("serial")
    class CannotDeleteLastUserException extends Exception {}

    @SuppressWarnings("serial")
    class RoleNotFoundException extends Exception {}

    @SuppressWarnings("serial")
    class CannotDeleteLastRoleException extends Exception {}

    @SuppressWarnings("serial")
    class DuplicateMBeanObjectNameException extends Exception {}

    @SuppressWarnings("serial")
    class DuplicateUsernameException extends Exception {}

    @SuppressWarnings("serial")
    class DuplicateRoleNameException extends Exception {}

    @SuppressWarnings("serial")
    class AgentRollupNotFoundException extends Exception {}

    @SuppressWarnings("serial")
    class AlertNotFoundException extends Exception {}
}
