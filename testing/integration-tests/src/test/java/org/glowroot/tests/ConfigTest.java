/*
 * Copyright 2011-2015 the original author or authors.
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
package org.glowroot.tests;

import java.util.List;
import java.util.Map;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.container.Container;
import org.glowroot.container.config.AdvancedConfig;
import org.glowroot.container.config.AlertConfig;
import org.glowroot.container.config.GaugeConfig;
import org.glowroot.container.config.GaugeConfig.MBeanAttribute;
import org.glowroot.container.config.InstrumentationConfig;
import org.glowroot.container.config.InstrumentationConfig.CaptureKind;
import org.glowroot.container.config.PluginConfig;
import org.glowroot.container.config.SmtpConfig;
import org.glowroot.container.config.StorageConfig;
import org.glowroot.container.config.TransactionConfig;
import org.glowroot.container.config.UserInterfaceConfig;
import org.glowroot.container.config.UserInterfaceConfig.AnonymousAccess;
import org.glowroot.container.config.UserRecordingConfig;

import static org.assertj.core.api.Assertions.assertThat;

public class ConfigTest {

    private static final String PLUGIN_ID = "glowroot-integration-tests";

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.getSharedContainer();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
    }

    @After
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldUpdateTransactionConfig() throws Exception {
        // given
        TransactionConfig config = container.getConfigService().getTransactionConfig();
        // when
        updateAllFields(config);
        container.getConfigService().updateTransactionConfig(config);
        // then
        TransactionConfig updatedConfig = container.getConfigService().getTransactionConfig();
        assertThat(updatedConfig).isEqualTo(config);
    }

    @Test
    public void shouldUpdateUserInterfaceConfig() throws Exception {
        // given
        UserInterfaceConfig config = container.getConfigService().getUserInterfaceConfig();
        // when
        updateAllFields(config);
        container.getConfigService().updateUserInterfaceConfig(config);
        // then
        UserInterfaceConfig updatedConfig = container.getConfigService().getUserInterfaceConfig();
        assertThat(updatedConfig).isEqualTo(config);
    }

    @Test
    public void shouldCheckDefaultUserInterfacePassword() throws Exception {
        // given
        UserInterfaceConfig config = container.getConfigService().getUserInterfaceConfig();
        // when
        // then
        assertThat(config.isAdminPasswordEnabled()).isFalse();
    }

    @Test
    public void shouldEnableUserInterfacePassword() throws Exception {
        // given
        UserInterfaceConfig config = container.getConfigService().getUserInterfaceConfig();
        config.setCurrentAdminPassword("");
        config.setNewAdminPassword("abc");
        // when
        container.getConfigService().updateUserInterfaceConfig(config);
        // then
        UserInterfaceConfig updatedConfig = container.getConfigService().getUserInterfaceConfig();
        assertThat(updatedConfig.isAdminPasswordEnabled()).isTrue();
    }

    @Test
    public void shouldChangeUserInterfacePassword() throws Exception {
        // given
        UserInterfaceConfig config = container.getConfigService().getUserInterfaceConfig();
        config.setAdminPasswordEnabled(true);
        config.setCurrentAdminPassword("");
        config.setNewAdminPassword("xyz");
        config.setAnonymousAccess(AnonymousAccess.NONE);
        container.getConfigService().updateUserInterfaceConfig(config);
        // when
        config = container.getConfigService().getUserInterfaceConfig();
        config.setCurrentAdminPassword("xyz");
        config.setNewAdminPassword("123");
        container.getConfigService().updateUserInterfaceConfig(config);
        // then
        UserInterfaceConfig updatedConfig = container.getConfigService().getUserInterfaceConfig();
        assertThat(updatedConfig.isAdminPasswordEnabled()).isTrue();
    }

    @Test
    public void shouldDisableUserInterfacePassword() throws Exception {
        // given
        UserInterfaceConfig config = container.getConfigService().getUserInterfaceConfig();
        config.setAdminPasswordEnabled(true);
        config.setCurrentAdminPassword("");
        config.setNewAdminPassword("efg");
        config.setAnonymousAccess(AnonymousAccess.NONE);
        container.getConfigService().updateUserInterfaceConfig(config);
        // when
        config = container.getConfigService().getUserInterfaceConfig();
        config.setAdminPasswordEnabled(false);
        config.setCurrentAdminPassword("efg");
        config.setNewAdminPassword("");
        config.setAnonymousAccess(AnonymousAccess.ADMIN);
        container.getConfigService().updateUserInterfaceConfig(config);
        // then
        UserInterfaceConfig updatedConfig = container.getConfigService().getUserInterfaceConfig();
        assertThat(updatedConfig.isAdminPasswordEnabled()).isFalse();
    }

    @Test
    public void shouldUpdateStorageConfig() throws Exception {
        // given
        StorageConfig config = container.getConfigService().getStorageConfig();
        // when
        updateAllFields(config);
        container.getConfigService().updateStorageConfig(config);
        // then
        StorageConfig updatedConfig = container.getConfigService().getStorageConfig();
        assertThat(updatedConfig).isEqualTo(config);
    }

    @Test
    public void shouldUpdateSmtpConfig() throws Exception {
        // given
        SmtpConfig config = container.getConfigService().getSmtpConfig();
        // when
        updateAllFields(config);
        container.getConfigService().updateSmtpConfig(config);
        // then
        SmtpConfig updatedConfig = container.getConfigService().getSmtpConfig();
        assertThat(updatedConfig).isEqualTo(config);
    }

    @Test
    public void shouldUpdateUserRecordingConfig() throws Exception {
        // given
        UserRecordingConfig config = container.getConfigService().getUserRecordingConfig();
        // when
        updateAllFields(config);
        container.getConfigService().updateUserRecordingConfig(config);
        // then
        UserRecordingConfig updatedConfig = container.getConfigService().getUserRecordingConfig();
        assertThat(updatedConfig).isEqualTo(config);
    }

    @Test
    public void shouldUpdateAdvancedConfig() throws Exception {
        // given
        AdvancedConfig config = container.getConfigService().getAdvancedConfig();
        // when
        updateAllFields(config);
        container.getConfigService().updateAdvancedConfig(config);
        // then
        AdvancedConfig updatedConfig = container.getConfigService().getAdvancedConfig();
        assertThat(updatedConfig).isEqualTo(config);
    }

    @Test
    public void shouldUpdatePluginConfig() throws Exception {
        // given
        PluginConfig config = container.getConfigService().getPluginConfig(PLUGIN_ID);
        // when
        updateAllFields(config);
        container.getConfigService().updatePluginConfig(PLUGIN_ID, config);
        // then
        PluginConfig updatedConfig = container.getConfigService().getPluginConfig(PLUGIN_ID);
        assertThat(updatedConfig).isEqualTo(config);
    }

    @Test
    public void shouldInsertInstrumentationConfig() throws Exception {
        // given
        InstrumentationConfig config = createInstrumentationConfig();
        // when
        String version = container.getConfigService().addInstrumentationConfig(config).getVersion();
        InstrumentationConfig newConfig =
                container.getConfigService().getInstrumentationConfig(version);
        // then
        assertThat(container.getConfigService().getInstrumentationConfigs()).hasSize(1);
        assertThat(newConfig).isEqualTo(config);
    }

    @Test
    public void shouldUpdateInstrumentationConfig() throws Exception {
        // given
        InstrumentationConfig config = createInstrumentationConfig();
        config = container.getConfigService().addInstrumentationConfig(config);
        // when
        updateAllFields(config);
        String version =
                container.getConfigService().updateInstrumentationConfig(config).getVersion();
        InstrumentationConfig newConfig =
                container.getConfigService().getInstrumentationConfig(version);
        // then
        assertThat(container.getConfigService().getInstrumentationConfigs()).hasSize(1);
        assertThat(newConfig).isEqualTo(config);
    }

    @Test
    public void shouldDeleteInstrumentationConfig() throws Exception {
        // given
        InstrumentationConfig config = createInstrumentationConfig();
        config = container.getConfigService().addInstrumentationConfig(config);
        // when
        container.getConfigService().removeInstrumentationConfig(config.getVersion());
        // then
        assertThat(container.getConfigService().getInstrumentationConfigs()).isEmpty();
    }

    @Test
    public void shouldInsertGauge() throws Exception {
        // given
        List<? extends GaugeConfig> originalConfigs =
                container.getConfigService().getGaugeConfigs();
        GaugeConfig config = createGauge();
        // when
        String version = container.getConfigService().addGaugeConfig(config).getVersion();
        // then
        List<GaugeConfig> configs = container.getConfigService().getGaugeConfigs();
        assertThat(configs).hasSize(originalConfigs.size() + 1);
        GaugeConfig newConfig = container.getConfigService().getGaugeConfig(version);
        assertThat(newConfig).isEqualTo(config);
    }

    @Test
    public void shouldUpdateGauge() throws Exception {
        // given
        List<? extends GaugeConfig> originalConfigs =
                container.getConfigService().getGaugeConfigs();
        GaugeConfig config = createGauge();
        config = container.getConfigService().addGaugeConfig(config);
        // when
        updateAllFields(config);
        String version = container.getConfigService().updateGaugeConfig(config).getVersion();
        // then
        List<GaugeConfig> configs = container.getConfigService().getGaugeConfigs();
        assertThat(configs).hasSize(originalConfigs.size() + 1);
        GaugeConfig newConfig = container.getConfigService().getGaugeConfig(version);
        assertThat(newConfig).isEqualTo(config);
    }

    @Test
    public void shouldDeleteGauge() throws Exception {
        // given
        List<? extends GaugeConfig> originalConfigs =
                container.getConfigService().getGaugeConfigs();
        GaugeConfig config = createGauge();
        config = container.getConfigService().addGaugeConfig(config);
        // when
        container.getConfigService().removeGaugeConfig(config.getVersion());
        // then
        List<? extends GaugeConfig> configs = container.getConfigService().getGaugeConfigs();
        assertThat(configs).isEqualTo(originalConfigs);
    }

    @Test
    public void shouldInsertAlert() throws Exception {
        // given
        AlertConfig config = createAlert();
        // when
        String version = container.getConfigService().addAlertConfig(config).getVersion();
        AlertConfig newConfig = container.getConfigService().getAlertConfig(version);
        // then
        assertThat(container.getConfigService().getAlertConfigs()).hasSize(1);
        assertThat(newConfig).isEqualTo(config);
    }

    @Test
    public void shouldUpdateAlert() throws Exception {
        // given
        AlertConfig config = createAlert();
        config = container.getConfigService().addAlertConfig(config);
        // when
        updateAllFields(config);
        String version = container.getConfigService().updateAlertConfig(config).getVersion();
        AlertConfig newConfig = container.getConfigService().getAlertConfig(version);
        // then
        assertThat(container.getConfigService().getAlertConfigs()).hasSize(1);
        assertThat(newConfig).isEqualTo(config);
    }

    @Test
    public void shouldDeleteAlert() throws Exception {
        // given
        AlertConfig config = createAlert();
        config = container.getConfigService().addAlertConfig(config);
        // when
        container.getConfigService().removeAlertConfig(config.getVersion());
        // then
        assertThat(container.getConfigService().getAlertConfigs()).isEmpty();
    }

    private static void updateAllFields(TransactionConfig config) {
        config.setSlowThresholdMillis(config.getSlowThresholdMillis() + 1);
        config.setProfilingIntervalMillis(config.getProfilingIntervalMillis() + 1);
        config.setDefaultDisplayedTransactionType(
                config.getDefaultDisplayedTransactionType() + "a");
        List<Double> percentiles = Lists.newArrayList();
        for (double percentile : config.getDefaultDisplayedPercentiles()) {
            percentiles.add(percentile / 2);
        }
        config.setDefaultDisplayedPercentiles(percentiles);
    }

    private static void updateAllFields(UserInterfaceConfig config) {
        // changing the port and password are tested elsewhere
        config.setSessionTimeoutMinutes(config.getSessionTimeoutMinutes() + 1);
    }

    private static void updateAllFields(StorageConfig config) {
        config.setRollupExpirationHours(ImmutableList.of(1, 2, 3));
        config.setTraceExpirationHours(config.getTraceExpirationHours() + 10);
        config.setRollupCappedDatabaseSizesMb(ImmutableList.of(100, 200, 300));
        config.setTraceCappedDatabaseSizeMb(config.getTraceCappedDatabaseSizeMb() + 100);
    }

    private static void updateAllFields(SmtpConfig config) {
        config.setFromEmailAddress(config.getFromEmailAddress() + "a");
        config.setFromDisplayName(config.getFromDisplayName() + "b");
        config.setHost(config.getHost() + "c");
        config.setPort(config.getPort() == null ? 123 : config.getPort() + 1);
        config.setSsl(!config.isSsl());
        config.setUsername(config.getUsername() + "d");
        config.setNewPassword("e");
        config.setAdditionalProperties(ImmutableMap.of("1", "x", "2", "y"));
    }

    private static void updateAllFields(UserRecordingConfig config) {
        config.setEnabled(!config.isEnabled());
        config.setUser(Strings.nullToEmpty(config.getUser()) + "x");
        config.setProfileIntervalMillis(config.getProfileIntervalMillis() + 1);
    }

    private static void updateAllFields(AdvancedConfig config) {
        config.setTimerWrapperMethods(!config.isTimerWrapperMethods());
        config.setWeavingTimer(!config.isWeavingTimer());
        config.setImmediatePartialStoreThresholdSeconds(
                config.getImmediatePartialStoreThresholdSeconds() + 1);
        config.setMaxAggregateTransactionsPerTransactionType(
                config.getMaxAggregateTransactionsPerTransactionType() + 10);
        config.setMaxAggregateQueriesPerQueryType(
                config.getMaxAggregateQueriesPerQueryType() + 100);
        config.setMaxTraceEntriesPerTransaction(config.getMaxTraceEntriesPerTransaction() + 1000);
        config.setMaxStackTraceSamplesPerTransaction(
                config.getMaxStackTraceSamplesPerTransaction() + 10000);
        config.setCaptureThreadInfo(!config.isCaptureThreadInfo());
        config.setCaptureGcInfo(!config.isCaptureGcInfo());
        config.setMBeanGaugeNotFoundDelaySeconds(
                config.getMBeanGaugeNotFoundDelaySeconds() + 100000);
        config.setInternalQueryTimeoutSeconds(config.getInternalQueryTimeoutSeconds() + 1000000);
    }

    private static void updateAllFields(PluginConfig config) {
        config.setEnabled(!config.isEnabled());
        boolean starredHeadline = (Boolean) config.getProperty("starredHeadline");
        config.setProperty("starredHeadline", !starredHeadline);
        String alternateHeadline = (String) config.getProperty("alternateHeadline");
        config.setProperty("alternateHeadline", alternateHeadline + "x");
        String hasDefaultVal = (String) config.getProperty("hasDefaultVal");
        config.setProperty("hasDefaultVal", hasDefaultVal + "x");
        boolean captureTraceEntryStackTraces =
                (Boolean) config.getProperty("captureTraceEntryStackTraces");
        config.setProperty("captureTraceEntryStackTraces", !captureTraceEntryStackTraces);
    }

    private static InstrumentationConfig createInstrumentationConfig() {
        InstrumentationConfig config = new InstrumentationConfig();
        config.setClassName("java.util.Collections");
        config.setDeclaringClassName("");
        config.setMethodName("yak");
        config.setMethodParameterTypes(Lists.newArrayList("java.lang.String", "java.util.List"));
        config.setMethodReturnType("void");
        config.setCaptureKind(CaptureKind.TRANSACTION);
        config.setTimerName("yako");
        config.setTraceEntryTemplate("yak(): {{0}}, {{1}} => {{?}}");
        config.setTransactionType("ttype");
        config.setTransactionNameTemplate("tname");
        config.setTransactionUserTemplate("");
        config.setEnabledProperty("");
        config.setTraceEntryEnabledProperty("");
        return config;
    }

    private static void updateAllFields(InstrumentationConfig config) {
        config.setClassName(config.getClassName() + "a");
        config.setDeclaringClassName(config.getDeclaringClassName() + "adeclaringclass");
        config.setMethodName(config.getMethodName() + "b");
        if (config.getMethodParameterTypes().size() == 0) {
            config.setMethodParameterTypes(ImmutableList.of("java.lang.String"));
        } else {
            config.setMethodParameterTypes(
                    ImmutableList.of(config.getMethodParameterTypes().get(0) + "c"));
        }
        config.setMethodReturnType(config.getMethodReturnType() + "d");
        if (config.getCaptureKind() == CaptureKind.TIMER) {
            config.setCaptureKind(CaptureKind.TRACE_ENTRY);
        } else {
            config.setCaptureKind(CaptureKind.TIMER);
        }
        config.setTimerName(config.getTimerName() + "e");
        config.setTraceEntryTemplate(config.getTraceEntryTemplate() + "f");
        config.setTraceEntryCaptureSelfNested(!config.isTraceEntryCaptureSelfNested());
        Long traceEntryStackThresholdMillis = config.getTraceEntryStackThresholdMillis();
        if (traceEntryStackThresholdMillis == null) {
            config.setTraceEntryStackThresholdMillis(1000L);
        } else {
            config.setTraceEntryStackThresholdMillis(traceEntryStackThresholdMillis + 1);
        }
        config.setTransactionType(config.getTransactionType() + "g");
        config.setTransactionNameTemplate(config.getTransactionNameTemplate() + "h");
        Long transactionSlowThresholdMillis = config.getTransactionSlowThresholdMillis();
        if (transactionSlowThresholdMillis == null) {
            config.setTransactionSlowThresholdMillis(1000L);
        } else {
            config.setTransactionSlowThresholdMillis(transactionSlowThresholdMillis + 10);
        }
        config.setTransactionUserTemplate(config.getTransactionUserTemplate() + "i");
        Map<String, String> transactionCustomAttributeTemplates =
                Maps.newHashMap(config.getTransactionCustomAttributeTemplates());
        transactionCustomAttributeTemplates.put("Test attr name", "Test attr value");
        config.setTransactionCustomAttributeTemplates(transactionCustomAttributeTemplates);
        config.setEnabledProperty(config.getEnabledProperty() + "k");
        config.setTraceEntryEnabledProperty(config.getTraceEntryEnabledProperty() + "l");
    }

    private static void updateAllFields(GaugeConfig config) {
        config.setMBeanObjectName("java.lang:type=Compilation");
        MBeanAttribute mbeanAttribute = new MBeanAttribute();
        mbeanAttribute.setName("TotalCompilationTime");
        config.setMBeanAttributes(Lists.newArrayList(mbeanAttribute));
    }

    private static void updateAllFields(AlertConfig config) {
        config.setTransactionType(config.getTransactionType() + "a");
        config.setPercentile(config.getPercentile() / 2);
        config.setTimePeriodMinutes(config.getTimePeriodMinutes() + 1);
        config.setThresholdMillis(config.getThresholdMillis() + 1);
        config.setMinTransactionCount(config.getMinTransactionCount() + 1);
        config.setEmailAddresses(Lists.newArrayList("three@example.org"));

    }

    private static GaugeConfig createGauge() {
        GaugeConfig config = new GaugeConfig();
        config.setMBeanObjectName("java.lang:type=ClassLoading");
        MBeanAttribute mbeanAttribute1 = new MBeanAttribute();
        mbeanAttribute1.setName("LoadedClassCount");
        MBeanAttribute mbeanAttribute2 = new MBeanAttribute();
        mbeanAttribute2.setName("TotalLoadedClassCount");
        mbeanAttribute2.setEverIncreasing(true);
        config.setMBeanAttributes(Lists.newArrayList(mbeanAttribute1, mbeanAttribute2));
        return config;
    }

    private static AlertConfig createAlert() {
        AlertConfig config = new AlertConfig();
        config.setTransactionType("a type");
        config.setPercentile(99.9);
        config.setTimePeriodMinutes(2);
        config.setThresholdMillis(1234);
        config.setMinTransactionCount(100);
        config.setEmailAddresses(Lists.newArrayList("one@example.org", "two@example.org"));
        return config;
    }
}
