/*
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.tests;

import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import io.informant.Containers;
import io.informant.container.Container;
import io.informant.container.config.AdhocPointcutConfig;
import io.informant.container.config.AdhocPointcutConfig.MethodModifier;
import io.informant.container.config.AdvancedConfig;
import io.informant.container.config.CoarseProfilingConfig;
import io.informant.container.config.FineProfilingConfig;
import io.informant.container.config.GeneralConfig;
import io.informant.container.config.PluginConfig;
import io.informant.container.config.StorageConfig;
import io.informant.container.config.UserInterfaceConfig;
import io.informant.container.config.UserOverridesConfig;

import static org.fest.assertions.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ConfigTest {

    private static final String PLUGIN_ID = "informant-integration-tests";

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.create();
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
    public void shouldUpdateGeneralConfig() throws Exception {
        // given
        GeneralConfig config = container.getConfigService().getGeneralConfig();
        // when
        updateAllFields(config);
        container.getConfigService().updateGeneralConfig(config);
        // then
        GeneralConfig updatedConfig = container.getConfigService().getGeneralConfig();
        assertThat(updatedConfig).isEqualTo(config);
    }

    @Test
    public void shouldUpdateCoarseProfilingConfig() throws Exception {
        // given
        CoarseProfilingConfig config = container.getConfigService().getCoarseProfilingConfig();
        // when
        updateAllFields(config);
        container.getConfigService().updateCoarseProfilingConfig(config);
        // then
        CoarseProfilingConfig updatedConfig = container.getConfigService()
                .getCoarseProfilingConfig();
        assertThat(updatedConfig).isEqualTo(config);
    }

    @Test
    public void shouldUpdateFineProfilingConfig() throws Exception {
        // given
        FineProfilingConfig config = container.getConfigService().getFineProfilingConfig();
        // when
        updateAllFields(config);
        container.getConfigService().updateFineProfilingConfig(config);
        // then
        FineProfilingConfig updatedConfig = container.getConfigService().getFineProfilingConfig();
        assertThat(updatedConfig).isEqualTo(config);
    }

    @Test
    public void shouldUpdateUserOverridesConfig() throws Exception {
        // given
        UserOverridesConfig config = container.getConfigService().getUserOverridesConfig();
        // when
        updateAllFields(config);
        container.getConfigService().updateUserOverridesConfig(config);
        // then
        UserOverridesConfig updatedConfig = container.getConfigService().getUserOverridesConfig();
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
        config.setPasswordEnabled(true);
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
        config.setPasswordEnabled(true);
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
        config.setPasswordEnabled(true);
        config.setNewPassword("efg");
        container.getConfigService().updateUserInterfaceConfig(config);
        // when
        config = container.getConfigService().getUserInterfaceConfig();
        config.setPasswordEnabled(false);
        config.setCurrentPassword("efg");
        container.getConfigService().updateUserInterfaceConfig(config);
        // then
        UserInterfaceConfig updatedConfig = container.getConfigService().getUserInterfaceConfig();
        assertThat(updatedConfig.isPasswordEnabled()).isFalse();
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
    public void shouldInsertAdhocPointcutConfig() throws Exception {
        // given
        AdhocPointcutConfig config = createAdhocPointcutConfig();
        // when
        container.getConfigService().addAdhocPointcutConfig(config);
        // then
        List<AdhocPointcutConfig> configs = container.getConfigService().getAdhocPointcutConfigs();
        assertThat(configs).hasSize(1);
        assertThat(configs.get(0)).isEqualTo(config);
    }

    @Test
    public void shouldUpdateAdhocPointcutConfig() throws Exception {
        // given
        AdhocPointcutConfig config = createAdhocPointcutConfig();
        String version = container.getConfigService().addAdhocPointcutConfig(config);
        // when
        updateAllFields(config);
        container.getConfigService().updateAdhocPointcutConfig(version, config);
        // then
        List<AdhocPointcutConfig> configs = container.getConfigService().getAdhocPointcutConfigs();
        assertThat(configs).hasSize(1);
        assertThat(configs.get(0)).isEqualTo(config);
    }

    @Test
    public void shouldDeleteAdhocPointcutConfig() throws Exception {
        // given
        AdhocPointcutConfig config = createAdhocPointcutConfig();
        String version = container.getConfigService().addAdhocPointcutConfig(config);
        // when
        container.getConfigService().removeAdhocPointcutConfig(version);
        // then
        List<? extends AdhocPointcutConfig> configs =
                container.getConfigService().getAdhocPointcutConfigs();
        assertThat(configs).isEmpty();
    }

    private static void updateAllFields(GeneralConfig config) {
        config.setEnabled(!config.isEnabled());
        config.setStoreThresholdMillis(config.getStoreThresholdMillis() + 1);
        config.setStuckThresholdSeconds(config.getStuckThresholdSeconds() + 1);
        config.setMaxSpans(config.getMaxSpans() + 1);
    }

    private static void updateAllFields(CoarseProfilingConfig config) {
        config.setEnabled(!config.isEnabled());
        config.setInitialDelayMillis(config.getInitialDelayMillis() + 1);
        config.setIntervalMillis(config.getIntervalMillis() + 1);
        config.setTotalSeconds(config.getTotalSeconds() + 1);
    }

    private static void updateAllFields(FineProfilingConfig config) {
        config.setTracePercentage(config.getTracePercentage() + 1);
        config.setIntervalMillis(config.getIntervalMillis() + 1);
        config.setTotalSeconds(config.getTotalSeconds() + 1);
    }

    private static void updateAllFields(UserOverridesConfig config) {
        config.setUserId(config.getUserId() + "x");
        config.setStoreThresholdMillis(config.getStoreThresholdMillis() + 1);
        config.setFineProfiling(!config.isFineProfiling());
    }

    private static void updateAllFields(StorageConfig config) {
        config.setSnapshotExpirationHours(config.getSnapshotExpirationHours() + 1);
        config.setRollingSizeMb(config.getRollingSizeMb() + 1);
    }

    private static void updateAllFields(UserInterfaceConfig config) {
        if (config.isPasswordEnabled()) {
            throw new AssertionError("Cannot disable password since that requires knowing current"
                    + " password");
        }
        config.setPasswordEnabled(false);
        config.setNewPassword("z");
        config.setSessionTimeoutMinutes(config.getSessionTimeoutMinutes() + 1);
    }

    private static void updateAllFields(AdvancedConfig config) {
        config.setGenerateMetricNameWrapperMethods(!config.isGenerateMetricNameWrapperMethods());
        config.setWarnOnSpanOutsideTrace(!config.isWarnOnSpanOutsideTrace());
        config.setWeavingDisabled(!config.isWeavingDisabled());
    }

    private static void updateAllFields(PluginConfig config) {
        config.setEnabled(!config.isEnabled());
        boolean starredGrouping = (Boolean) config.getProperty("starredGrouping");
        config.setProperty("starredGrouping", !starredGrouping);
        String alternateGrouping = (String) config.getProperty("alternateGrouping");
        config.setProperty("alternateGrouping", alternateGrouping + "x");
        String hasDefaultVal = (String) config.getProperty("hasDefaultVal");
        config.setProperty("hasDefaultVal", hasDefaultVal + "x");
        boolean captureSpanStackTraces = (Boolean) config.getProperty("captureSpanStackTraces");
        config.setProperty("captureSpanStackTraces", !captureSpanStackTraces);
    }

    private static AdhocPointcutConfig createAdhocPointcutConfig() {
        AdhocPointcutConfig config = new AdhocPointcutConfig();
        config.setMetric(true);
        config.setSpan(true);
        config.setTypeName("java.util.Collections");
        config.setMethodName("yak");
        config.setMethodArgTypeNames(Lists.newArrayList("java.lang.String", "java.util.List"));
        config.setMethodReturnTypeName("void");
        config.setMethodModifiers(Lists
                .newArrayList(MethodModifier.PUBLIC, MethodModifier.STATIC));
        config.setMetricName("yako");
        config.setSpanText("yak(): {{0}}, {{1}} => {{?}}");
        return config;
    }

    private static void updateAllFields(AdhocPointcutConfig config) {
        config.setMetric(!config.isMetric());
        config.setSpan(!config.isSpan());
        config.setTrace(!config.isTrace());
        config.setTypeName(config.getTypeName() + "a");
        config.setMethodName(config.getMethodName() + "b");
        if (config.getMethodArgTypeNames().size() == 0) {
            config.setMethodArgTypeNames(ImmutableList.of("java.lang.String"));
        } else {
            config.setMethodArgTypeNames(ImmutableList.of(config.getMethodArgTypeNames().get(0)
                    + "c"));
        }
        config.setMethodReturnTypeName(config.getMethodReturnTypeName() + "d");
        if (config.getMethodModifiers().contains(MethodModifier.PUBLIC)) {
            config.setMethodModifiers(ImmutableList.of(MethodModifier.PRIVATE));
        } else {
            config.setMethodModifiers(ImmutableList
                    .of(MethodModifier.PUBLIC, MethodModifier.STATIC));
        }
        config.setMetricName(config.getMetricName() + "e");
        config.setSpanText(config.getSpanText() + "f");
    }
}
