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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.container.Container;
import org.glowroot.container.config.AdvancedConfig;
import org.glowroot.container.config.GeneralConfig;
import org.glowroot.container.config.OutlierProfilingConfig;
import org.glowroot.container.config.PluginConfig;
import org.glowroot.container.config.PointcutConfig;
import org.glowroot.container.config.PointcutConfig.MethodModifier;
import org.glowroot.container.config.ProfilingConfig;
import org.glowroot.container.config.StorageConfig;
import org.glowroot.container.config.UserInterfaceConfig;
import org.glowroot.container.config.UserTracingConfig;

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
    public void shouldUpdateOutlierProfilingConfig() throws Exception {
        // given
        OutlierProfilingConfig config = container.getConfigService().getOutlierProfilingConfig();
        // when
        updateAllFields(config);
        container.getConfigService().updateOutlierProfilingConfig(config);
        // then
        OutlierProfilingConfig updatedConfig = container.getConfigService()
                .getOutlierProfilingConfig();
        assertThat(updatedConfig).isEqualTo(config);
    }

    @Test
    public void shouldUpdateUserTracingConfig() throws Exception {
        // given
        UserTracingConfig config = container.getConfigService().getUserTracingConfig();
        // when
        updateAllFields(config);
        container.getConfigService().updateUserTracingConfig(config);
        // then
        UserTracingConfig updatedConfig = container.getConfigService().getUserTracingConfig();
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
    public void shouldInsertPointcutConfig() throws Exception {
        // given
        PointcutConfig config = createPointcutConfig();
        // when
        container.getConfigService().addPointcutConfig(config);
        // then
        List<PointcutConfig> configs = container.getConfigService().getPointcutConfigs();
        assertThat(configs).hasSize(1);
        assertThat(configs.get(0)).isEqualTo(config);
    }

    @Test
    public void shouldUpdatePointcutConfig() throws Exception {
        // given
        PointcutConfig config = createPointcutConfig();
        String version = container.getConfigService().addPointcutConfig(config);
        // when
        updateAllFields(config);
        container.getConfigService().updatePointcutConfig(version, config);
        // then
        List<PointcutConfig> configs = container.getConfigService().getPointcutConfigs();
        assertThat(configs).hasSize(1);
        assertThat(configs.get(0)).isEqualTo(config);
    }

    @Test
    public void shouldDeletePointcutConfig() throws Exception {
        // given
        PointcutConfig config = createPointcutConfig();
        String version = container.getConfigService().addPointcutConfig(config);
        // when
        container.getConfigService().removePointcutConfig(version);
        // then
        List<PointcutConfig> configs = container.getConfigService().getPointcutConfigs();
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

    private static void updateAllFields(GeneralConfig config) {
        config.setEnabled(!config.isEnabled());
        config.setStoreThresholdMillis(config.getStoreThresholdMillis() + 1);
        config.setStuckThresholdSeconds(config.getStuckThresholdSeconds() + 1);
        config.setMaxSpans(config.getMaxSpans() + 1);
    }

    private static void updateAllFields(ProfilingConfig config) {
        config.setTracePercentage(config.getTracePercentage() + 1);
        config.setIntervalMillis(config.getIntervalMillis() + 1);
        config.setMaxSeconds(config.getMaxSeconds() + 1);
    }

    private static void updateAllFields(OutlierProfilingConfig config) {
        config.setEnabled(!config.isEnabled());
        config.setInitialDelayMillis(config.getInitialDelayMillis() + 1);
        config.setIntervalMillis(config.getIntervalMillis() + 1);
        config.setMaxSeconds(config.getMaxSeconds() + 1);
    }

    private static void updateAllFields(UserTracingConfig config) {
        config.setUser(config.getUser() + "x");
        config.setStoreThresholdMillis(config.getStoreThresholdMillis() + 1);
        config.setProfile(!config.isProfile());
    }

    private static void updateAllFields(StorageConfig config) {
        config.setTraceExpirationHours(config.getTraceExpirationHours() + 1);
        config.setCappedDatabaseSizeMb(config.getCappedDatabaseSizeMb() + 1);
    }

    private static void updateAllFields(UserInterfaceConfig config) {
        // changing the port and password are tested elsewhere
        config.setDefaultTransactionType(config.getDefaultTransactionType() + "a");
        config.setSessionTimeoutMinutes(config.getSessionTimeoutMinutes() + 1);
    }

    private static void updateAllFields(AdvancedConfig config) {
        config.setTraceMetricWrapperMethods(!config.isTraceMetricWrapperMethods());
    }

    private static void updateAllFields(PluginConfig config) {
        config.setEnabled(!config.isEnabled());
        boolean starredHeadline = (Boolean) config.getProperty("starredHeadline");
        config.setProperty("starredHeadline", !starredHeadline);
        String alternateHeadline = (String) config.getProperty("alternateHeadline");
        config.setProperty("alternateHeadline", alternateHeadline + "x");
        String hasDefaultVal = (String) config.getProperty("hasDefaultVal");
        config.setProperty("hasDefaultVal", hasDefaultVal + "x");
        boolean captureSpanStackTraces = (Boolean) config.getProperty("captureSpanStackTraces");
        config.setProperty("captureSpanStackTraces", !captureSpanStackTraces);
    }

    private static PointcutConfig createPointcutConfig() {
        PointcutConfig config = new PointcutConfig();
        config.setClassName("java.util.Collections");
        config.setMethodName("yak");
        config.setMethodParameterTypes(Lists.newArrayList("java.lang.String", "java.util.List"));
        config.setMethodReturnType("void");
        config.setMethodModifiers(Lists
                .newArrayList(MethodModifier.PUBLIC, MethodModifier.STATIC));
        config.setTraceMetric("yako");
        config.setMessageTemplate("yak(): {{0}}, {{1}} => {{?}}");
        config.setTransactionType("ttype");
        config.setTransactionNameTemplate("tname");
        config.setEnabledProperty("");
        config.setSpanEnabledProperty("");
        return config;
    }

    private static void updateAllFields(PointcutConfig config) {
        config.setClassName(config.getClassName() + "a");
        config.setMethodName(config.getMethodName() + "b");
        if (config.getMethodParameterTypes().size() == 0) {
            config.setMethodParameterTypes(ImmutableList.of("java.lang.String"));
        } else {
            config.setMethodParameterTypes(ImmutableList.of(config.getMethodParameterTypes().get(0)
                    + "c"));
        }
        config.setMethodReturnType(config.getMethodReturnType() + "d");
        if (config.getMethodModifiers().contains(MethodModifier.PUBLIC)) {
            config.setMethodModifiers(ImmutableList.of(MethodModifier.PRIVATE));
        } else {
            config.setMethodModifiers(ImmutableList
                    .of(MethodModifier.PUBLIC, MethodModifier.STATIC));
        }
        config.setTraceMetric(config.getTraceMetric() + "e");
        config.setMessageTemplate(config.getMessageTemplate() + "f");
        Long stackTraceThresholdMillis = config.getStackTraceThresholdMillis();
        if (stackTraceThresholdMillis == null) {
            config.setStackTraceThresholdMillis(1000L);
        } else {
            config.setStackTraceThresholdMillis(stackTraceThresholdMillis + 1);
        }
        config.setTransactionType(config.getTransactionType() + "g");
        config.setTransactionNameTemplate(config.getTransactionNameTemplate() + "h");
    }
}
