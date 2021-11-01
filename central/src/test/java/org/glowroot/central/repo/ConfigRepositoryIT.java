/*
 * Copyright 2016-2019 the original author or authors.
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
package org.glowroot.central.repo;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.PoolingOptions;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.MoreExecutors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.Session;
import org.glowroot.common.util.Versions;
import org.glowroot.common2.config.CentralStorageConfig;
import org.glowroot.common2.config.CentralWebConfig;
import org.glowroot.common2.config.HttpProxyConfig;
import org.glowroot.common2.config.ImmutableCentralStorageConfig;
import org.glowroot.common2.config.ImmutableCentralWebConfig;
import org.glowroot.common2.config.ImmutableHttpProxyConfig;
import org.glowroot.common2.config.ImmutableLdapConfig;
import org.glowroot.common2.config.ImmutableRoleConfig;
import org.glowroot.common2.config.ImmutableSmtpConfig;
import org.glowroot.common2.config.ImmutableUserConfig;
import org.glowroot.common2.config.LdapConfig;
import org.glowroot.common2.config.RoleConfig;
import org.glowroot.common2.config.SmtpConfig;
import org.glowroot.common2.config.SmtpConfig.ConnectionSecurity;
import org.glowroot.common2.config.UserConfig;
import org.glowroot.common2.repo.ConfigRepository;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AdvancedConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition.MetricCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertNotification;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertNotification.EmailNotification;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.GaugeConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.InstrumentationConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.InstrumentationConfig.CaptureKind;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.InstrumentationConfig.MethodModifier;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.JvmConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.MBeanAttribute;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.TransactionConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.UiDefaultsConfig;
import org.glowroot.wire.api.model.Proto.OptionalInt32;

import static org.assertj.core.api.Assertions.assertThat;

public class ConfigRepositoryIT {

    private static ClusterManager clusterManager;
    private static Cluster cluster;
    private static Session session;
    private static ExecutorService asyncExecutor;
    private static ConfigRepository configRepository;
    private static AgentConfigDao agentConfigDao;

    @BeforeAll
    public static void setUp() throws Exception {
        SharedSetupRunListener.startCassandra();
        clusterManager = ClusterManager.create();
        cluster = Clusters.newCluster();
        session = new Session(cluster.newSession(), "glowroot_unit_tests", null,
                PoolingOptions.DEFAULT_MAX_QUEUE_SIZE, 0);
        session.updateSchemaWithRetry("drop table if exists agent_config");
        session.updateSchemaWithRetry("drop table if exists user");
        session.updateSchemaWithRetry("drop table if exists role");
        session.updateSchemaWithRetry("drop table if exists central_config");
        session.updateSchemaWithRetry("drop table if exists agent");
        asyncExecutor = Executors.newCachedThreadPool();

        CentralConfigDao centralConfigDao = new CentralConfigDao(session, clusterManager);
        AgentDisplayDao agentDisplayDao =
                new AgentDisplayDao(session, clusterManager, MoreExecutors.directExecutor(), 10);
        agentConfigDao = new AgentConfigDao(session, agentDisplayDao, clusterManager, 10);
        UserDao userDao = new UserDao(session, clusterManager);
        RoleDao roleDao = new RoleDao(session, clusterManager);
        configRepository =
                new ConfigRepositoryImpl(centralConfigDao, agentConfigDao, userDao, roleDao, "");
    }

    @AfterAll
    public static void tearDown() throws Exception {
        asyncExecutor.shutdown();
        // remove bad data so other tests don't have issue
        session.updateSchemaWithRetry("drop table if exists agent_config");
        session.updateSchemaWithRetry("drop table if exists user");
        session.updateSchemaWithRetry("drop table if exists role");
        session.updateSchemaWithRetry("drop table if exists central_config");
        session.close();
        cluster.close();
        clusterManager.close();
        SharedSetupRunListener.stopCassandra();
    }

    @Test
    public void shouldUpdateTransactionConfig() throws Exception {
        // given
        String agentId = UUID.randomUUID().toString();
        agentConfigDao.store(agentId, AgentConfig.getDefaultInstance(), true);
        TransactionConfig config = configRepository.getTransactionConfig(agentId);
        TransactionConfig updatedConfig = TransactionConfig.newBuilder()
                .setSlowThresholdMillis(OptionalInt32.newBuilder().setValue(1234))
                .setProfilingIntervalMillis(OptionalInt32.newBuilder().setValue(2345))
                .setCaptureThreadStats(true)
                .build();

        // when
        configRepository.updateTransactionConfig(agentId, updatedConfig,
                Versions.getVersion(config));
        config = configRepository.getTransactionConfig(agentId);

        // then
        assertThat(config).isEqualTo(updatedConfig);
    }

    @Test
    public void shouldUpdateJvmConfig() throws Exception {
        // given
        String agentId = UUID.randomUUID().toString();
        agentConfigDao.store(agentId, AgentConfig.getDefaultInstance(), true);
        JvmConfig config = configRepository.getJvmConfig(agentId);
        JvmConfig updatedConfig = JvmConfig.newBuilder()
                .addMaskSystemProperty("x")
                .addMaskSystemProperty("y")
                .addMaskSystemProperty("z")
                .build();

        // when
        configRepository.updateJvmConfig(agentId, updatedConfig,
                Versions.getVersion(config));
        config = configRepository.getJvmConfig(agentId);

        // then
        assertThat(config).isEqualTo(updatedConfig);
    }

    @Test
    public void shouldUpdateUiConfig() throws Exception {
        // given
        String agentId = UUID.randomUUID().toString();
        agentConfigDao.store(agentId, AgentConfig.getDefaultInstance(), true);
        UiDefaultsConfig config = configRepository.getUiDefaultsConfig(agentId);
        UiDefaultsConfig updatedConfig = UiDefaultsConfig.newBuilder()
                .setDefaultTransactionType("xyz")
                .addDefaultPercentile(99.0)
                .addDefaultPercentile(99.9)
                .addDefaultPercentile(99.99)
                .build();

        // when
        configRepository.updateUiDefaultsConfig(agentId, updatedConfig,
                Versions.getVersion(config));
        config = configRepository.getUiDefaultsConfig(agentId);

        // then
        assertThat(config).isEqualTo(updatedConfig);
    }

    @Test
    public void shouldUpdateAdvancedConfig() throws Exception {
        // given
        String agentId = UUID.randomUUID().toString();
        agentConfigDao.store(agentId, AgentConfig.getDefaultInstance(), true);
        AdvancedConfig config = configRepository.getAdvancedConfig(agentId);
        AdvancedConfig updatedConfig = AdvancedConfig.newBuilder()
                .setWeavingTimer(true)
                .setImmediatePartialStoreThresholdSeconds(OptionalInt32.newBuilder().setValue(1))
                .setMaxTransactionAggregates(OptionalInt32.newBuilder().setValue(2))
                .setMaxQueryAggregates(OptionalInt32.newBuilder().setValue(3))
                .setMaxServiceCallAggregates(OptionalInt32.newBuilder().setValue(4))
                .setMaxTraceEntriesPerTransaction(OptionalInt32.newBuilder().setValue(5))
                .setMaxProfileSamplesPerTransaction(OptionalInt32.newBuilder().setValue(6))
                .setMbeanGaugeNotFoundDelaySeconds(OptionalInt32.newBuilder().setValue(7))
                .build();

        // when
        configRepository.updateAdvancedConfig(agentId, updatedConfig, Versions.getVersion(config));
        config = configRepository.getAdvancedConfig(agentId);

        // then
        assertThat(config).isEqualTo(updatedConfig);
    }

    @Test
    public void shouldCrudGaugeConfig() throws Exception {
        // given
        String agentId = UUID.randomUUID().toString();
        agentConfigDao.store(agentId, AgentConfig.getDefaultInstance(), true);
        GaugeConfig gaugeConfig = GaugeConfig.newBuilder()
                .setMbeanObjectName("x")
                .addMbeanAttribute(MBeanAttribute.newBuilder()
                        .setName("y")
                        .setCounter(true))
                .build();

        // when
        configRepository.insertGaugeConfig(agentId, gaugeConfig);
        List<GaugeConfig> gaugeConfigs = configRepository.getGaugeConfigs(agentId);

        // then
        assertThat(gaugeConfigs).hasSize(1);
        assertThat(gaugeConfigs.get(0)).isEqualTo(gaugeConfig);

        // and further

        // given
        GaugeConfig updatedGaugeConfig = GaugeConfig.newBuilder()
                .setMbeanObjectName("x2")
                .addMbeanAttribute(MBeanAttribute.newBuilder()
                        .setName("y2"))
                .build();

        // when
        configRepository.updateGaugeConfig(agentId, updatedGaugeConfig,
                Versions.getVersion(gaugeConfig));
        gaugeConfigs = configRepository.getGaugeConfigs(agentId);

        // then
        assertThat(gaugeConfigs).hasSize(1);
        assertThat(gaugeConfigs.get(0)).isEqualTo(updatedGaugeConfig);

        // and further

        // when
        configRepository.deleteGaugeConfig(agentId, Versions.getVersion(updatedGaugeConfig));
        gaugeConfigs = configRepository.getGaugeConfigs(agentId);

        // then
        assertThat(gaugeConfigs).isEmpty();
    }

    @Test
    public void shouldCrudAlertConfig() throws Exception {
        // given
        String agentId = UUID.randomUUID().toString();
        agentConfigDao.store(agentId, AgentConfig.getDefaultInstance(), true);
        AlertConfig alertConfig = AlertConfig.newBuilder()
                .setCondition(AlertCondition.newBuilder()
                        .setMetricCondition(MetricCondition.newBuilder()
                                .setMetric("gauge:abc")
                                .setThreshold(111)
                                .setTimePeriodSeconds(60))
                        .build())
                .setNotification(AlertNotification.newBuilder()
                        .setEmailNotification(EmailNotification.newBuilder()
                                .addEmailAddress("noone@example.org")))
                .build();

        // when
        configRepository.insertAlertConfig(agentId, alertConfig);
        List<AlertConfig> alertConfigs = configRepository.getAlertConfigs(agentId);

        // then
        assertThat(alertConfigs).hasSize(1);
        assertThat(alertConfigs.get(0)).isEqualTo(alertConfig);

        // and further

        // given
        AlertConfig updatedAlertConfig = AlertConfig.newBuilder()
                .setCondition(AlertCondition.newBuilder()
                        .setMetricCondition(MetricCondition.newBuilder()
                                .setMetric("gauge:abc2")
                                .setThreshold(222)
                                .setTimePeriodSeconds(62))
                        .build())
                .setNotification(AlertNotification.newBuilder()
                        .setEmailNotification(EmailNotification.newBuilder()
                                .addEmailAddress("noone2@example.org")))
                .build();

        // when
        configRepository.updateAlertConfig(agentId, updatedAlertConfig,
                Versions.getVersion(alertConfig));
        alertConfigs = configRepository.getAlertConfigs(agentId);

        // then
        assertThat(alertConfigs).hasSize(1);
        assertThat(alertConfigs.get(0)).isEqualTo(updatedAlertConfig);

        // and further

        // when
        configRepository.deleteAlertConfig(agentId, Versions.getVersion(updatedAlertConfig));
        alertConfigs = configRepository.getAlertConfigs(agentId);

        // then
        assertThat(alertConfigs).isEmpty();
    }

    @Test
    public void shouldCrudInstrumentationConfig() throws Exception {
        // given
        String agentId = UUID.randomUUID().toString();
        agentConfigDao.store(agentId, AgentConfig.getDefaultInstance(), true);
        InstrumentationConfig instrumentationConfig = InstrumentationConfig.newBuilder()
                .setClassName("a")
                .setMethodName("b")
                .setMethodReturnType("c")
                .addMethodModifier(MethodModifier.PUBLIC)
                .setCaptureKind(CaptureKind.TRACE_ENTRY)
                .setTimerName("d")
                .setTraceEntryMessageTemplate("e")
                .setTraceEntryStackThresholdMillis(OptionalInt32.newBuilder().setValue(1))
                .setTransactionType("f")
                .setTransactionNameTemplate("g")
                .setTransactionSlowThresholdMillis(OptionalInt32.newBuilder().setValue(2))
                .build();

        // when
        configRepository.insertInstrumentationConfig(agentId, instrumentationConfig);
        List<InstrumentationConfig> instrumentationConfigs =
                configRepository.getInstrumentationConfigs(agentId);

        // then
        assertThat(instrumentationConfigs).hasSize(1);
        assertThat(instrumentationConfigs.get(0)).isEqualTo(instrumentationConfig);

        // and further

        // given
        InstrumentationConfig updatedInstrumentationConfig = InstrumentationConfig.newBuilder()
                .setClassName("a2")
                .setMethodName("b2")
                .setMethodReturnType("c2")
                .addMethodModifier(MethodModifier.PUBLIC)
                .setCaptureKind(CaptureKind.TRACE_ENTRY)
                .setTimerName("d2")
                .setTraceEntryMessageTemplate("e2")
                .setTraceEntryStackThresholdMillis(OptionalInt32.newBuilder().setValue(12))
                .setTransactionType("f2")
                .setTransactionNameTemplate("g2")
                .setTransactionSlowThresholdMillis(OptionalInt32.newBuilder().setValue(22))
                .build();

        // when
        configRepository.updateInstrumentationConfig(agentId, updatedInstrumentationConfig,
                Versions.getVersion(instrumentationConfig));
        instrumentationConfigs = configRepository.getInstrumentationConfigs(agentId);

        // then
        assertThat(instrumentationConfigs).hasSize(1);
        assertThat(instrumentationConfigs.get(0)).isEqualTo(updatedInstrumentationConfig);

        // and further

        // when
        configRepository.deleteInstrumentationConfigs(agentId,
                ImmutableList.of(Versions.getVersion(updatedInstrumentationConfig)));
        instrumentationConfigs = configRepository.getInstrumentationConfigs(agentId);

        // then
        assertThat(instrumentationConfigs).isEmpty();
    }

    @Test
    public void shouldCrudUserConfig() throws Exception {
        // given
        UserConfig userConfig = ImmutableUserConfig.builder()
                .username("auser")
                .addRoles("brole")
                .build();

        // when
        configRepository.insertUserConfig(userConfig);
        List<UserConfig> userConfigs = configRepository.getUserConfigs();

        // then
        assertThat(userConfigs).hasSize(2);
        assertThat(userConfigs.get(1)).isEqualTo(userConfig);

        // and further

        // given
        String username = "auser";

        // when
        UserConfig readUserConfig = configRepository.getUserConfig(username);

        // then
        assertThat(readUserConfig).isNotNull();

        // and further

        // given
        UserConfig updatedUserConfig = ImmutableUserConfig.builder()
                .username("auser")
                .addRoles("brole2")
                .build();

        // when
        configRepository.updateUserConfig(updatedUserConfig, userConfig.version());
        userConfigs = configRepository.getUserConfigs();

        // then
        assertThat(userConfigs).hasSize(2);
        assertThat(userConfigs.get(1)).isEqualTo(updatedUserConfig);

        // and further

        // when
        configRepository.deleteUserConfig(updatedUserConfig.username());
        userConfigs = configRepository.getUserConfigs();

        // then
        assertThat(userConfigs).hasSize(1);
        assertThat(userConfigs.get(0).username()).isEqualTo("anonymous");
    }

    @Test
    public void shouldCrudRoleConfig() throws Exception {
        // given
        RoleConfig roleConfig = ImmutableRoleConfig.builder()
                .central(true)
                .name("brole")
                .addPermissions("p1")
                .addPermissions("p2")
                .build();

        // when
        configRepository.insertRoleConfig(roleConfig);
        List<RoleConfig> roleConfigs = configRepository.getRoleConfigs();

        // then
        assertThat(roleConfigs).hasSize(2);
        assertThat(roleConfigs.get(1)).isEqualTo(roleConfig);

        // and further

        // given
        RoleConfig updatedRoleConfig = ImmutableRoleConfig.builder()
                .central(true)
                .name("brole")
                .addPermissions("p5")
                .addPermissions("p6")
                .addPermissions("p7")
                .build();

        // when
        configRepository.updateRoleConfig(updatedRoleConfig, roleConfig.version());
        roleConfigs = configRepository.getRoleConfigs();

        // then
        assertThat(roleConfigs).hasSize(2);
        assertThat(roleConfigs.get(1)).isEqualTo(updatedRoleConfig);

        // and further

        // when
        configRepository.deleteRoleConfig(updatedRoleConfig.name());
        roleConfigs = configRepository.getRoleConfigs();

        // then
        assertThat(roleConfigs).hasSize(1);
        assertThat(roleConfigs.get(0).name()).isEqualTo("Administrator");
    }

    @Test
    public void shouldUpdateWebConfig() throws Exception {
        // given
        CentralWebConfig config = configRepository.getCentralWebConfig();
        CentralWebConfig updatedConfig = ImmutableCentralWebConfig.builder()
                .sessionTimeoutMinutes(31)
                .sessionCookieName("GLOWROOT_SESSION_ID2")
                .build();

        // when
        configRepository.updateCentralWebConfig(updatedConfig, config.version());
        config = configRepository.getCentralWebConfig();

        // then
        assertThat(config).isEqualTo(updatedConfig);
    }

    @Test
    public void shouldUpdateCentralStorageConfig() throws Exception {
        // given
        CentralStorageConfig config = configRepository.getCentralStorageConfig();
        CentralStorageConfig updatedConfig = ImmutableCentralStorageConfig.builder()
                .addRollupExpirationHours(1)
                .addRollupExpirationHours(2)
                .addRollupExpirationHours(3)
                .addRollupExpirationHours(4)
                .addQueryAndServiceCallRollupExpirationHours(5)
                .addQueryAndServiceCallRollupExpirationHours(6)
                .addQueryAndServiceCallRollupExpirationHours(7)
                .addQueryAndServiceCallRollupExpirationHours(8)
                .addProfileRollupExpirationHours(9)
                .addProfileRollupExpirationHours(10)
                .addProfileRollupExpirationHours(11)
                .addProfileRollupExpirationHours(12)
                .traceExpirationHours(100)
                .build();

        // when
        configRepository.updateCentralStorageConfig(updatedConfig, config.version());
        config = configRepository.getCentralStorageConfig();

        // then
        assertThat(config).isEqualTo(updatedConfig);
    }

    @Test
    public void shouldUpdateSmtpConfig() throws Exception {
        // given
        SmtpConfig config = configRepository.getSmtpConfig();
        SmtpConfig updatedConfig = ImmutableSmtpConfig.builder()
                .host("a")
                .port(555)
                .connectionSecurity(ConnectionSecurity.SSL_TLS)
                .username("b")
                .encryptedPassword("c")
                .putAdditionalProperties("f", "g")
                .putAdditionalProperties("h", "i")
                .fromEmailAddress("d")
                .fromDisplayName("e")
                .build();

        // when
        configRepository.updateSmtpConfig(updatedConfig, config.version());
        config = configRepository.getSmtpConfig();

        // then
        assertThat(config).isEqualTo(updatedConfig);
    }

    @Test
    public void shouldUpdateHttpProxyConfig() throws Exception {
        // given
        HttpProxyConfig config = configRepository.getHttpProxyConfig();
        HttpProxyConfig updatedConfig = ImmutableHttpProxyConfig.builder()
                .host("a")
                .port(555)
                .username("b")
                .encryptedPassword("c")
                .build();

        // when
        configRepository.updateHttpProxyConfig(updatedConfig, config.version());
        config = configRepository.getHttpProxyConfig();

        // then
        assertThat(config).isEqualTo(updatedConfig);
    }

    @Test
    public void shouldUpdateLdapConfig() throws Exception {
        // given
        LdapConfig config = configRepository.getLdapConfig();
        LdapConfig updatedConfig = ImmutableLdapConfig.builder()
                .host("a")
                .port(1234)
                .username("b")
                .encryptedPassword("c")
                .userBaseDn("d")
                .userSearchFilter("e")
                .groupBaseDn("f")
                .groupSearchFilter("g")
                .build();

        // when
        configRepository.updateLdapConfig(updatedConfig, config.version());
        config = configRepository.getLdapConfig();

        // then
        assertThat(config).isEqualTo(updatedConfig);
    }
}
