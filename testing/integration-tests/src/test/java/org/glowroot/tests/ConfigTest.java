/*
 * Copyright 2011-2014 the original author or authors.
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
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.container.Container;
import org.glowroot.container.config.AdvancedConfig;
import org.glowroot.container.config.CapturePoint;
import org.glowroot.container.config.CapturePoint.CaptureKind;
import org.glowroot.container.config.CapturePoint.MethodModifier;
import org.glowroot.container.config.PluginConfig;
import org.glowroot.container.config.ProfilingConfig;
import org.glowroot.container.config.StorageConfig;
import org.glowroot.container.config.TraceConfig;
import org.glowroot.container.config.UserInterfaceConfig;
import org.glowroot.container.config.UserRecordingConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
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
    public void shouldUpdateTraceConfig() throws Exception {
        // given
        TraceConfig config = container.getConfigService().getTraceConfig();
        // when
        updateAllFields(config);
        container.getConfigService().updateTraceConfig(config);
        // then
        TraceConfig updatedConfig = container.getConfigService().getTraceConfig();
        assertThat(updatedConfig).isEqualTo(config);
    }

    @Test
    public void shouldUpdateProfilingConfig() throws Exception {
        // given
        ProfilingConfig config = container.getConfigService().getProfilingConfig();
        // when
        updateAllFields(config);
        container.getConfigService().updateProfilingConfig(config);
        // then
        ProfilingConfig updatedConfig = container.getConfigService().getProfilingConfig();
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
        assertThat(config.isPasswordEnabled()).isFalse();
    }

    @Test
    public void shouldEnableUserInterfacePassword() throws Exception {
        // given
        UserInterfaceConfig config = container.getConfigService().getUserInterfaceConfig();
        config.setCurrentPassword("");
        config.setNewPassword("abc");
        // when
        container.getConfigService().updateUserInterfaceConfig(config);
        // then
        UserInterfaceConfig updatedConfig = container.getConfigService().getUserInterfaceConfig();
        assertThat(updatedConfig.isPasswordEnabled()).isTrue();
    }

    @Test
    public void shouldChangeUserInterfacePassword() throws Exception {
        // given
        UserInterfaceConfig config = container.getConfigService().getUserInterfaceConfig();
        config.setCurrentPassword("");
        config.setNewPassword("xyz");
        container.getConfigService().updateUserInterfaceConfig(config);
        // when
        config = container.getConfigService().getUserInterfaceConfig();
        config.setCurrentPassword("xyz");
        config.setNewPassword("123");
        container.getConfigService().updateUserInterfaceConfig(config);
        // then
        UserInterfaceConfig updatedConfig = container.getConfigService().getUserInterfaceConfig();
        assertThat(updatedConfig.isPasswordEnabled()).isTrue();
    }

    @Test
    public void shouldDisableUserInterfacePassword() throws Exception {
        // given
        UserInterfaceConfig config = container.getConfigService().getUserInterfaceConfig();
        config.setCurrentPassword("");
        config.setNewPassword("efg");
        container.getConfigService().updateUserInterfaceConfig(config);
        // when
        config = container.getConfigService().getUserInterfaceConfig();
        config.setCurrentPassword("efg");
        config.setNewPassword("");
        container.getConfigService().updateUserInterfaceConfig(config);
        // then
        UserInterfaceConfig updatedConfig = container.getConfigService().getUserInterfaceConfig();
        assertThat(updatedConfig.isPasswordEnabled()).isFalse();
    }

    @Test
    public void shouldInsertCapturePoint() throws Exception {
        // given
        CapturePoint config = createCapturePoint();
        // when
        container.getConfigService().addCapturePoint(config);
        // then
        List<CapturePoint> configs = container.getConfigService().getCapturePoints();
        assertThat(configs).hasSize(1);
        assertThat(configs.get(0)).isEqualTo(config);
    }

    @Test
    public void shouldUpdateCapturePoint() throws Exception {
        // given
        CapturePoint config = createCapturePoint();
        String version = container.getConfigService().addCapturePoint(config);
        // when
        updateAllFields(config);
        container.getConfigService().updateCapturePoint(version, config);
        // then
        List<CapturePoint> configs = container.getConfigService().getCapturePoints();
        assertThat(configs).hasSize(1);
        assertThat(configs.get(0)).isEqualTo(config);
    }

    @Test
    public void shouldDeleteCapturePoint() throws Exception {
        // given
        CapturePoint config = createCapturePoint();
        String version = container.getConfigService().addCapturePoint(config);
        // when
        container.getConfigService().removeCapturePoint(version);
        // then
        List<CapturePoint> configs = container.getConfigService().getCapturePoints();
        assertThat(configs).isEmpty();
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

    private static void updateAllFields(TraceConfig config) {
        config.setEnabled(!config.isEnabled());
        config.setStoreThresholdMillis(config.getStoreThresholdMillis() + 1);
        config.setOutlierProfilingEnabled(!config.isOutlierProfilingEnabled());
        config.setOutlierProfilingInitialDelayMillis(
                config.getOutlierProfilingInitialDelayMillis() + 1);
        config.setOutlierProfilingIntervalMillis(config.getOutlierProfilingIntervalMillis() + 1);
    }

    private static void updateAllFields(ProfilingConfig config) {
        config.setEnabled(!config.isEnabled());
        config.setTransactionPercentage(config.getTransactionPercentage() + 1);
        config.setIntervalMillis(config.getIntervalMillis() + 1);
        config.setTraceStoreThresholdOverrideMillis(
                config.getTraceStoreThresholdOverrideMillis() + 1);
    }

    private static void updateAllFields(UserRecordingConfig config) {
        config.setEnabled(!config.isEnabled());
        config.setUser(Strings.nullToEmpty(config.getUser()) + "x");
        config.setProfileIntervalMillis(config.getProfileIntervalMillis() + 1);
    }

    private static void updateAllFields(StorageConfig config) {
        config.setAggregateExpirationHours(config.getAggregateExpirationHours() + 1);
        config.setTraceExpirationHours(config.getTraceExpirationHours() + 1);
        config.setCappedDatabaseSizeMb(config.getCappedDatabaseSizeMb() + 1);
    }

    private static void updateAllFields(UserInterfaceConfig config) {
        // changing the port and password are tested elsewhere
        config.setDefaultTransactionType(config.getDefaultTransactionType() + "a");
        config.setSessionTimeoutMinutes(config.getSessionTimeoutMinutes() + 1);
    }

    private static void updateAllFields(AdvancedConfig config) {
        config.setMetricWrapperMethods(!config.isMetricWrapperMethods());
        config.setImmediatePartialStoreThresholdSeconds(
                config.getImmediatePartialStoreThresholdSeconds() + 1);
        config.setMaxEntriesPerTrace(config.getMaxEntriesPerTrace() + 1);
        config.setCaptureThreadInfo(!config.isCaptureThreadInfo());
        config.setCaptureGcInfo(!config.isCaptureGcInfo());
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

    private static CapturePoint createCapturePoint() {
        CapturePoint config = new CapturePoint();
        config.setClassName("java.util.Collections");
        config.setMethodName("yak");
        config.setMethodParameterTypes(Lists.newArrayList("java.lang.String", "java.util.List"));
        config.setMethodReturnType("void");
        config.setMethodModifiers(Lists.newArrayList(MethodModifier.PUBLIC, MethodModifier.STATIC));
        config.setCaptureKind(CaptureKind.TRANSACTION);
        config.setMetricName("yako");
        config.setTraceEntryTemplate("yak(): {{0}}, {{1}} => {{?}}");
        config.setTransactionType("ttype");
        config.setTransactionNameTemplate("tname");
        config.setTransactionUserTemplate("");
        config.setEnabledProperty("");
        config.setTraceEntryEnabledProperty("");
        return config;
    }

    private static void updateAllFields(CapturePoint config) {
        config.setClassName(config.getClassName() + "a");
        config.setMethodName(config.getMethodName() + "b");
        if (config.getMethodParameterTypes().size() == 0) {
            config.setMethodParameterTypes(ImmutableList.of("java.lang.String"));
        } else {
            config.setMethodParameterTypes(
                    ImmutableList.of(config.getMethodParameterTypes().get(0) + "c"));
        }
        config.setMethodReturnType(config.getMethodReturnType() + "d");
        if (config.getMethodModifiers().contains(MethodModifier.PUBLIC)) {
            config.setMethodModifiers(ImmutableList.of(MethodModifier.PRIVATE));
        } else {
            config.setMethodModifiers(
                    ImmutableList.of(MethodModifier.PUBLIC, MethodModifier.STATIC));
        }
        if (config.getCaptureKind() == CaptureKind.METRIC) {
            config.setCaptureKind(CaptureKind.TRACE_ENTRY);
        } else {
            config.setCaptureKind(CaptureKind.METRIC);
        }
        config.setMetricName(config.getMetricName() + "e");
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
        Long storeThresholdOverrideMillis = config.getTraceStoreThresholdMillis();
        if (storeThresholdOverrideMillis == null) {
            config.setTraceStoreThresholdMillis(1000L);
        } else {
            config.setTraceStoreThresholdMillis(storeThresholdOverrideMillis + 1);
        }
        config.setTransactionUserTemplate(config.getTransactionUserTemplate() + "i");
        Map<String, String> transactionCustomAttributeTemplates =
                Maps.newHashMap(config.getTransactionCustomAttributeTemplates());
        transactionCustomAttributeTemplates.put("Test attr name", "Test attr value");
        config.setTransactionCustomAttributeTemplates(transactionCustomAttributeTemplates);
        config.setEnabledProperty(config.getEnabledProperty() + "k");
        config.setTraceEntryEnabledProperty(config.getTraceEntryEnabledProperty() + "l");
    }
}
