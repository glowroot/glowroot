/*
 * Copyright 2016-2017 the original author or authors.
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

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.KeyspaceMetadata;
import com.datastax.driver.core.Session;
import com.google.common.collect.ImmutableList;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.Sessions;
import org.glowroot.common.config.CentralStorageConfig;
import org.glowroot.common.config.ImmutableCentralStorageConfig;
import org.glowroot.common.config.ImmutableLdapConfig;
import org.glowroot.common.config.ImmutableRoleConfig;
import org.glowroot.common.config.ImmutableSmtpConfig;
import org.glowroot.common.config.ImmutableUserConfig;
import org.glowroot.common.config.ImmutableWebConfig;
import org.glowroot.common.config.LdapConfig;
import org.glowroot.common.config.RoleConfig;
import org.glowroot.common.config.SmtpConfig;
import org.glowroot.common.config.UserConfig;
import org.glowroot.common.config.WebConfig;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.util.Versions;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AdvancedConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertKind;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.GaugeConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.InstrumentationConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.InstrumentationConfig.CaptureKind;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.InstrumentationConfig.MethodModifier;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.MBeanAttribute;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.TransactionConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.UiConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.UserRecordingConfig;
import org.glowroot.wire.api.model.Proto.OptionalDouble;
import org.glowroot.wire.api.model.Proto.OptionalInt32;

import static org.assertj.core.api.Assertions.assertThat;

public class ConfigRepositoryIT {

    private static Cluster cluster;
    private static Session session;
    private static ClusterManager clusterManager;
    private static ConfigRepository configRepository;
    private static ConfigDao configDao;

    @BeforeClass
    public static void setUp() throws Exception {
        SharedSetupRunListener.startCassandra();
        cluster = Clusters.newCluster();
        session = cluster.newSession();
        Sessions.createKeyspaceIfNotExists(session, "glowroot_unit_tests");
        session.execute("use glowroot_unit_tests");
        KeyspaceMetadata keyspace = cluster.getMetadata().getKeyspace("glowroot_unit_tests");

        session.execute("drop table if exists agent");
        session.execute("drop table if exists agent_rollup");
        session.execute("drop table if exists user");
        session.execute("drop table if exists role");
        session.execute("drop table if exists central_config");
        clusterManager = ClusterManager.create();

        configDao = new ConfigDao(session, clusterManager);
        AgentDao agentDao = new AgentDao(session, clusterManager);
        CentralConfigDao centralConfigDao = new CentralConfigDao(session, clusterManager);
        UserDao userDao = new UserDao(session, keyspace, clusterManager);
        RoleDao roleDao = new RoleDao(session, keyspace, clusterManager);
        configRepository =
                new ConfigRepositoryImpl(agentDao, configDao, centralConfigDao, userDao, roleDao);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        clusterManager.close();
        // remove bad data so other tests don't have issue
        session.execute("drop table agent_rollup");
        session.execute("drop table config");
        session.execute("drop table user");
        session.execute("drop table role");
        session.execute("drop table central_config");
        session.close();
        cluster.close();
        SharedSetupRunListener.stopCassandra();
    }

    @Test
    public void shouldReadConfigForNonExistentAgentId() throws Exception {
        String agentId = UUID.randomUUID().toString();
        assertThat(configRepository.getGaugeConfigs(agentId)).isEmpty();
        boolean exception = false;
        try {
            configRepository.getGaugeConfig(agentId, "dummy");
        } catch (IllegalStateException e) {
            exception = true;
        }
        assertThat(exception).isTrue();
        assertThat(configRepository.getAlertConfigs(agentId)).isEmpty();
        assertThat(configRepository.getAlertConfig(agentId, "dummy")).isNull();
        assertThat(configRepository.getPluginConfigs(agentId)).isEmpty();
        exception = false;
        try {
            configRepository.getPluginConfig(agentId, "dummy");
        } catch (IllegalStateException e) {
            exception = true;
        }
        assertThat(exception).isTrue();
        assertThat(configRepository.getInstrumentationConfigs(agentId)).isEmpty();
        exception = false;
        try {
            configRepository.getInstrumentationConfig(agentId, "dummy");
        } catch (IllegalStateException e) {
            exception = true;
        }
        assertThat(exception).isTrue();
    }

    @Test
    public void shouldUpdateTransactionConfig() throws Exception {
        // given
        String agentId = UUID.randomUUID().toString();
        configDao.store(agentId, null, AgentConfig.getDefaultInstance());
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
    public void shouldUpdateUiConfig() throws Exception {
        // given
        String agentId = UUID.randomUUID().toString();
        configDao.store(agentId, null, AgentConfig.getDefaultInstance());
        UiConfig config = configRepository.getUiConfig(agentId);
        UiConfig updatedConfig = UiConfig.newBuilder()
                .setDefaultDisplayedTransactionType("xyz")
                .addDefaultDisplayedPercentile(99.0)
                .addDefaultDisplayedPercentile(99.9)
                .addDefaultDisplayedPercentile(99.99)
                .build();

        // when
        configRepository.updateUiConfig(agentId, updatedConfig, Versions.getVersion(config));
        config = configRepository.getUiConfig(agentId);

        // then
        assertThat(config).isEqualTo(updatedConfig);
    }

    @Test
    public void shouldUpdateUserRecordingConfig() throws Exception {
        // given
        String agentId = UUID.randomUUID().toString();
        configDao.store(agentId, null, AgentConfig.getDefaultInstance());
        UserRecordingConfig config = configRepository.getUserRecordingConfig(agentId);
        UserRecordingConfig updatedConfig = UserRecordingConfig.newBuilder()
                .addUser("x")
                .addUser("y")
                .addUser("z")
                .setProfilingIntervalMillis(OptionalInt32.newBuilder().setValue(1234))
                .build();

        // when
        configRepository.updateUserRecordingConfig(agentId, updatedConfig,
                Versions.getVersion(config));
        config = configRepository.getUserRecordingConfig(agentId);

        // then
        assertThat(config).isEqualTo(updatedConfig);
    }

    @Test
    public void shouldUpdateAdvancedConfig() throws Exception {
        // given
        String agentId = UUID.randomUUID().toString();
        configDao.store(agentId, null, AgentConfig.getDefaultInstance());
        AdvancedConfig config = configRepository.getAdvancedConfig(agentId);
        AdvancedConfig updatedConfig = AdvancedConfig.newBuilder()
                .setWeavingTimer(true)
                .setImmediatePartialStoreThresholdSeconds(OptionalInt32.newBuilder().setValue(1))
                .setMaxAggregateTransactionsPerType(OptionalInt32.newBuilder().setValue(2))
                .setMaxAggregateQueriesPerType(OptionalInt32.newBuilder().setValue(3))
                .setMaxAggregateServiceCallsPerType(OptionalInt32.newBuilder().setValue(4))
                .setMaxTraceEntriesPerTransaction(OptionalInt32.newBuilder().setValue(5))
                .setMaxStackTraceSamplesPerTransaction(OptionalInt32.newBuilder().setValue(6))
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
        configDao.store(agentId, null, AgentConfig.getDefaultInstance());
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
        configDao.store(agentId, null, AgentConfig.getDefaultInstance());
        AlertConfig alertConfig = AlertConfig.newBuilder()
                .setKind(AlertKind.GAUGE)
                .setGaugeName("abc")
                .setGaugeThreshold(OptionalDouble.newBuilder().setValue(111))
                .setTimePeriodSeconds(60)
                .addEmailAddress("noone@example.org")
                .build();

        // when
        String id = configRepository.insertAlertConfig(agentId, alertConfig);
        alertConfig = alertConfig.toBuilder()
                .setId(id)
                .build();
        List<AlertConfig> alertConfigs = configRepository.getAlertConfigs(agentId);

        // then
        assertThat(alertConfigs).hasSize(1);
        assertThat(alertConfigs.get(0)).isEqualTo(alertConfig);

        // and further

        // given
        AlertConfig updatedAlertConfig = alertConfig.toBuilder()
                .setGaugeName("abc2")
                .setGaugeThreshold(OptionalDouble.newBuilder().setValue(222))
                .setTimePeriodSeconds(62)
                .addEmailAddress("noone2@example.org")
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
        configRepository.deleteAlertConfig(agentId, updatedAlertConfig.getId());
        alertConfigs = configRepository.getAlertConfigs(agentId);

        // then
        assertThat(alertConfigs).isEmpty();
    }

    @Test
    public void shouldCrudInstrumentationConfig() throws Exception {
        // given
        String agentId = UUID.randomUUID().toString();
        configDao.store(agentId, null, AgentConfig.getDefaultInstance());
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
        WebConfig config = configRepository.getWebConfig();
        WebConfig updatedConfig = ImmutableWebConfig.builder()
                .port(4001)
                .sessionTimeoutMinutes(31)
                .sessionCookieName("GLOWROOT_SESSION_ID2")
                .build();

        // when
        configRepository.updateWebConfig(updatedConfig, config.version());
        config = configRepository.getWebConfig();

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
                .traceExpirationHours(100)
                .fullQueryTextExpirationHours(100)
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
                .ssl(true)
                .username("b")
                .password("c")
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
    public void shouldUpdateLdapConfig() throws Exception {
        // given
        LdapConfig config = configRepository.getLdapConfig();
        LdapConfig updatedConfig = ImmutableLdapConfig.builder()
                .host("a")
                .port(1234)
                .username("b")
                .password("c")
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
