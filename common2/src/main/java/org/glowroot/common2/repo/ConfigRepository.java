/*
 * Copyright 2015-2023 the original author or authors.
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
package org.glowroot.common2.repo;

import java.util.List;
import java.util.concurrent.CompletionStage;

import com.google.common.collect.ImmutableList;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.immutables.value.Value;

import org.glowroot.common.util.Styles;
import org.glowroot.common2.config.AllCentralAdminConfig;
import org.glowroot.common2.config.AllEmbeddedAdminConfig;
import org.glowroot.common2.config.CentralAdminGeneralConfig;
import org.glowroot.common2.config.CentralStorageConfig;
import org.glowroot.common2.config.CentralWebConfig;
import org.glowroot.common2.config.EmbeddedAdminGeneralConfig;
import org.glowroot.common2.config.EmbeddedStorageConfig;
import org.glowroot.common2.config.EmbeddedWebConfig;
import org.glowroot.common2.config.HealthchecksIoConfig;
import org.glowroot.common2.config.HttpProxyConfig;
import org.glowroot.common2.config.LdapConfig;
import org.glowroot.common2.config.PagerDutyConfig;
import org.glowroot.common2.config.RoleConfig;
import org.glowroot.common2.config.SlackConfig;
import org.glowroot.common2.config.SmtpConfig;
import org.glowroot.common2.config.StorageConfig;
import org.glowroot.common2.config.UserConfig;
import org.glowroot.common2.config.WebConfig;
import org.glowroot.common2.repo.util.LazySecretKey;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AdvancedConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.GaugeConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.GeneralConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.InstrumentationConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.JvmConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.SyntheticMonitorConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.TransactionConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.UiDefaultsConfig;

import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MINUTES;

public interface ConfigRepository {

    public static final int GAUGE_VIEW_THRESHOLD_MULTIPLIER = 4;

    String GENERAL_KEY = "general";
    String WEB_KEY = "web";
    String STORAGE_KEY = "storage";
    String SMTP_KEY = "smtp";
    String HTTP_PROXY_KEY = "httpProxy";
    String LDAP_KEY = "ldap";
    String PAGER_DUTY_KEY = "pagerDuty";
    String SLACK_KEY = "slack";

    long ROLLUP_0_INTERVAL_MILLIS =
            Long.getLong("glowroot.internal.rollup.0.intervalMillis", MINUTES.toMillis(1));
    long ROLLUP_1_INTERVAL_MILLIS =
            Long.getLong("glowroot.internal.rollup.1.intervalMillis", MINUTES.toMillis(5));
    long ROLLUP_2_INTERVAL_MILLIS =
            Long.getLong("glowroot.internal.rollup.2.intervalMillis", MINUTES.toMillis(30));
    long ROLLUP_3_INTERVAL_MILLIS =
            Long.getLong("glowroot.internal.rollup.3.intervalMillis", HOURS.toMillis(4));

    GeneralConfig getGeneralConfig(String agentRollupId) throws Exception;

    TransactionConfig getTransactionConfig(String agentId) throws Exception;

    JvmConfig getJvmConfig(String agentId) throws Exception;

    // central supports ui config on rollups
    UiDefaultsConfig getUiDefaultsConfig(String agentRollupId) throws Exception;

    // central supports advanced config on rollups (maxQueryAggregates and maxServiceCallAggregates)
    CompletionStage<AdvancedConfig> getAdvancedConfig(String agentRollupId);

    List<GaugeConfig> getGaugeConfigs(String agentId) throws Exception;

    @Nullable
    GaugeConfig getGaugeConfig(String agentId, String version) throws Exception;

    // central supports synthetic monitor configs on rollups
    CompletionStage<List<SyntheticMonitorConfig>> getSyntheticMonitorConfigs(String agentRollupId);

    // central supports synthetic monitor configs on rollups
    CompletionStage<SyntheticMonitorConfig> getSyntheticMonitorConfig(String agentRollupId,
            String syntheticMonitorId) throws Exception;

    // central supports alert configs on rollups
    CompletionStage<List<AlertConfig>> getAlertConfigs(String agentRollupId);

    CompletionStage<List<AlertConfig>> getAlertConfigsNonBlocking(String agentRollupId);

    // central supports alert configs on rollups
    @Nullable
    CompletionStage<AlertConfig> getAlertConfig(String agentRollupId, String version);

    List<PluginConfig> getPluginConfigs(String agentId) throws Exception;

    @Nullable
    PluginConfig getPluginConfig(String agentId, String pluginId) throws Exception;

    List<InstrumentationConfig> getInstrumentationConfigs(String agentId) throws Exception;

    @Nullable
    InstrumentationConfig getInstrumentationConfig(String agentId, String version) throws Exception;

    AgentConfig getAllConfig(String agentId) throws Exception;

    EmbeddedAdminGeneralConfig getEmbeddedAdminGeneralConfig();

    CompletionStage<CentralAdminGeneralConfig> getCentralAdminGeneralConfig();

    CompletionStage<List<UserConfig>> getUserConfigs();

    CompletionStage<UserConfig> getUserConfig(String username);

    CompletionStage<UserConfig> getUserConfigCaseInsensitive(String username);

    CompletionStage<Boolean> namedUsersExist();

    CompletionStage<List<RoleConfig>> getRoleConfigs();

    CompletionStage<RoleConfig> getRoleConfig(String name);

    CompletionStage<? extends WebConfig> getWebConfig();

    EmbeddedWebConfig getEmbeddedWebConfig();

    CompletionStage<CentralWebConfig> getCentralWebConfig();

    StorageConfig getStorageConfig();

    EmbeddedStorageConfig getEmbeddedStorageConfig();

    CompletionStage<CentralStorageConfig> getCentralStorageConfig();

    CompletionStage<SmtpConfig> getSmtpConfig();

    CompletionStage<HttpProxyConfig> getHttpProxyConfig() throws Exception;

    CompletionStage<LdapConfig> getLdapConfig() throws Exception;

    CompletionStage<PagerDutyConfig> getPagerDutyConfig() throws Exception;

    CompletionStage<SlackConfig> getSlackConfig();

    HealthchecksIoConfig getHealthchecksIoConfig();

    AllEmbeddedAdminConfig getAllEmbeddedAdminConfig();

    CompletionStage<AllCentralAdminConfig> getAllCentralAdminConfig();

    CompletionStage<Boolean> isConfigReadOnly(String agentId);

    CompletionStage<?> updateGeneralConfig(String agentId, GeneralConfig config, String priorVersion, CassandraProfile profile)
            throws Exception;

    CompletionStage<?> updateTransactionConfig(String agentId, TransactionConfig config, String priorVersion, CassandraProfile profile)
            throws Exception;

    CompletionStage<?> insertGaugeConfig(String agentId, GaugeConfig config, CassandraProfile profile) throws Exception;

    CompletionStage<?> updateGaugeConfig(String agentId, GaugeConfig config, String priorVersion, CassandraProfile profile)
            throws Exception;

    CompletionStage<?> deleteGaugeConfig(String agentId, String version, CassandraProfile profile) throws Exception;

    CompletionStage<?> updateJvmConfig(String agentId, JvmConfig config, String priorVersion, CassandraProfile profile) throws Exception;

    // central supports synthetic monitor configs on rollups
    CompletionStage<?> insertSyntheticMonitorConfig(String agentRollupId, SyntheticMonitorConfig config, CassandraProfile profile)
            throws Exception;

    // central supports synthetic monitor configs on rollups
    CompletionStage<?> updateSyntheticMonitorConfig(String agentRollupId, SyntheticMonitorConfig config,
            String priorVersion, CassandraProfile profile) throws Exception;

    // central supports synthetic monitor configs on rollups
    CompletionStage<?> deleteSyntheticMonitorConfig(String agentRollupId, String syntheticMonitorId, CassandraProfile profile)
            throws Exception;

    // central supports alert configs on rollups
    CompletionStage<?> insertAlertConfig(String agentRollupId, AlertConfig config, CassandraProfile profile) throws Exception;

    // central supports alert configs on rollups
    CompletionStage<?> updateAlertConfig(String agentRollupId, AlertConfig config, String priorVersion, CassandraProfile profile);

    // central supports alert configs on rollups
    CompletionStage<?> deleteAlertConfig(String agentRollupId, String version, CassandraProfile profile);

    // central supports ui config on rollups
    CompletionStage<?> updateUiDefaultsConfig(String agentRollupId, UiDefaultsConfig config, String priorVersion, CassandraProfile profile)
            throws Exception;

    // only plugin id and property names and values are used
    CompletionStage<?> updatePluginConfig(String agentId, PluginConfig config, String priorVersion, CassandraProfile profile)
            throws Exception;

    CompletionStage<?> insertInstrumentationConfig(String agentId, InstrumentationConfig config, CassandraProfile profile) throws Exception;

    CompletionStage<?> updateInstrumentationConfig(String agentId, InstrumentationConfig config,
            String priorVersion, CassandraProfile profile) throws Exception;

    CompletionStage<?> deleteInstrumentationConfigs(String agentId, List<String> versions, CassandraProfile profile) throws Exception;

    CompletionStage<?> insertInstrumentationConfigs(String agentId, List<InstrumentationConfig> configs, CassandraProfile profile)
            throws Exception;

    // central supports advanced config on rollups (maxQueryAggregates and maxServiceCallAggregates)
    CompletionStage<?> updateAdvancedConfig(String agentRollupId, AdvancedConfig config, String priorVersion, CassandraProfile profile)
            throws Exception;

    CompletionStage<?> updateAllConfig(String agentId, AgentConfig config, @Nullable String priorVersion, CassandraProfile profile)
            throws Exception;

    CompletionStage<?> updateEmbeddedAdminGeneralConfig(EmbeddedAdminGeneralConfig config, String priorVersion, CassandraProfile profile)
            throws Exception;

    CompletionStage<?> updateCentralAdminGeneralConfig(CentralAdminGeneralConfig config, String priorVersion, CassandraProfile profile)
            throws Exception;

    CompletionStage<?> insertUserConfig(UserConfig config, CassandraProfile profile) throws Exception;

    CompletionStage<?> updateUserConfig(UserConfig config, String priorVersion, CassandraProfile profile) throws Exception;

    CompletionStage<?> deleteUserConfig(String username, CassandraProfile profile) throws Exception;

    CompletionStage<?> insertRoleConfig(RoleConfig config, CassandraProfile profile) throws Exception;

    CompletionStage<?> updateRoleConfig(RoleConfig config, String priorVersion, CassandraProfile profile) throws Exception;

    CompletionStage<?> deleteRoleConfig(String name, CassandraProfile profile);

    void updateEmbeddedWebConfig(EmbeddedWebConfig config, String priorVersion) throws Exception;

    CompletionStage<?> updateCentralWebConfig(CentralWebConfig config, String priorVersion) throws Exception;

    void updateEmbeddedStorageConfig(EmbeddedStorageConfig config, String priorVersion)
            throws Exception;

    CompletionStage<?> updateCentralStorageConfig(CentralStorageConfig config, String priorVersion)
            throws Exception;

    CompletionStage<?> updateSmtpConfig(SmtpConfig config, String priorVersion) throws Exception;

    CompletionStage<?> updateHttpProxyConfig(HttpProxyConfig config, String priorVersion) throws Exception;

    CompletionStage<?> updateLdapConfig(LdapConfig config, String priorVersion) throws Exception;

    CompletionStage<?> updatePagerDutyConfig(PagerDutyConfig config, String priorVersion) throws Exception;

    CompletionStage<?> updateSlackConfig(SlackConfig config, String priorVersion) throws Exception;

    void updateHealthchecksIoConfig(HealthchecksIoConfig healthchecksIoConfig, String priorVersion)
            throws Exception;

    void updateAllEmbeddedAdminConfig(AllEmbeddedAdminConfig config, @Nullable String priorVersion)
            throws Exception;

    void updateAllCentralAdminConfig(AllCentralAdminConfig config, @Nullable String priorVersion)
            throws Exception;

    long getGaugeCollectionIntervalMillis();

    List<RollupConfig> getRollupConfigs();

    LazySecretKey getLazySecretKey();

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
    class OptimisticLockException extends RuntimeException {}

    @SuppressWarnings("serial")
    class AgentConfigNotFoundException extends RuntimeException {

        private final String agentRollupId;

        public AgentConfigNotFoundException(String agentRollupId) {
            this.agentRollupId = agentRollupId;
        }

        @Override
        public String getMessage() {
            return agentRollupId;
        }
    }

    @SuppressWarnings("serial")
    class UserNotFoundException extends Exception {}

    @SuppressWarnings("serial")
    class CannotDeleteLastUserException extends Exception {}

    @SuppressWarnings("serial")
    class RoleNotFoundException extends RuntimeException {}

    @SuppressWarnings("serial")
    class CannotDeleteLastRoleException extends RuntimeException {}

    @SuppressWarnings("serial")
    class DuplicateMBeanObjectNameException extends RuntimeException {}

    @SuppressWarnings("serial")
    class DuplicateSyntheticMonitorDisplayException extends RuntimeException {}

    @SuppressWarnings("serial")
    class DuplicateUsernameException extends Exception {}

    @SuppressWarnings("serial")
    class DuplicateRoleNameException extends Exception {}

    @SuppressWarnings("serial")
    class DuplicatePagerDutyIntegrationKeyException extends Exception {}

    @SuppressWarnings("serial")
    class DuplicatePagerDutyIntegrationKeyDisplayException extends Exception {}

    @SuppressWarnings("serial")
    class DuplicateSlackWebhookUrlException extends Exception {}

    @SuppressWarnings("serial")
    class DuplicateSlackWebhookDisplayException extends Exception {}

    @SuppressWarnings("serial")
    class SyntheticNotFoundException extends RuntimeException {}

    @SuppressWarnings("serial")
    class AlertNotFoundException extends RuntimeException {}
}
