/**
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
package io.informant.test;

import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import io.informant.Containers;
import io.informant.container.Container;
import io.informant.container.config.CoarseProfilingConfig;
import io.informant.container.config.FineProfilingConfig;
import io.informant.container.config.GeneralConfig;
import io.informant.container.config.PluginConfig;
import io.informant.container.config.PointcutConfig;
import io.informant.container.config.PointcutConfig.CaptureItem;
import io.informant.container.config.PointcutConfig.MethodModifier;
import io.informant.container.config.UserConfig;

import static org.fest.assertions.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ConfigTest {

    private static final String PLUGIN_ID = "io.informant:informant-integration-tests";

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
    public void shouldUpdateUserConfig() throws Exception {
        // given
        UserConfig config = container.getConfigService().getUserConfig();
        // when
        updateAllFields(config);
        container.getConfigService().updateUserConfig(config);
        // then
        UserConfig updatedConfig = container.getConfigService().getUserConfig();
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
    public void shouldInsertPointcutConfig() throws Exception {
        // given
        PointcutConfig config = createPointcutConfig();
        // when
        container.getConfigService().addPointcutConfig(config);
        // then
        List<PointcutConfig> pointcuts = container.getConfigService().getPointcutConfigs();
        assertThat(pointcuts).hasSize(1);
        assertThat(pointcuts.get(0)).isEqualTo(config);
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
        List<PointcutConfig> pointcuts = container.getConfigService().getPointcutConfigs();
        assertThat(pointcuts).hasSize(1);
        assertThat(pointcuts.get(0)).isEqualTo(config);
    }

    @Test
    public void shouldDeletePointcutConfig() throws Exception {
        // given
        PointcutConfig pointcut = createPointcutConfig();
        String version = container.getConfigService().addPointcutConfig(pointcut);
        // when
        container.getConfigService().removePointcutConfig(version);
        // then
        List<? extends PointcutConfig> pointcuts =
                container.getConfigService().getPointcutConfigs();
        assertThat(pointcuts).isEmpty();
    }

    private static void updateAllFields(GeneralConfig config) {
        config.setEnabled(!config.isEnabled());
        config.setStoreThresholdMillis(config.getStoreThresholdMillis() + 1);
        config.setStuckThresholdSeconds(config.getStuckThresholdSeconds() + 1);
        config.setMaxSpans(config.getMaxSpans() + 1);
        config.setSnapshotExpirationHours(config.getSnapshotExpirationHours() + 1);
        config.setRollingSizeMb(config.getRollingSizeMb() + 1);
        config.setWarnOnSpanOutsideTrace(!config.isWarnOnSpanOutsideTrace());
    }

    private static void updateAllFields(CoarseProfilingConfig config) {
        config.setEnabled(!config.isEnabled());
        config.setInitialDelayMillis(config.getInitialDelayMillis() + 1);
        config.setIntervalMillis(config.getIntervalMillis() + 1);
        config.setTotalSeconds(config.getTotalSeconds() + 1);
    }

    private static void updateAllFields(FineProfilingConfig config) {
        config.setEnabled(!config.isEnabled());
        config.setTracePercentage(config.getTracePercentage() + 1);
        config.setIntervalMillis(config.getIntervalMillis() + 1);
        config.setTotalSeconds(config.getTotalSeconds() + 1);
    }

    private static void updateAllFields(UserConfig config) {
        config.setEnabled(!config.isEnabled());
        config.setUserId(config.getUserId() + "x");
        config.setStoreThresholdMillis(config.getStoreThresholdMillis() + 1);
        config.setFineProfiling(!config.isFineProfiling());
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
        config.setCaptureItems(Lists.newArrayList(CaptureItem.METRIC, CaptureItem.SPAN));
        config.setTypeName("java.util.Collections");
        config.setMethodName("yak");
        config.setMethodArgTypeNames(Lists.newArrayList("java.lang.String", "java.util.List"));
        config.setMethodReturnTypeName("void");
        config.setMethodModifiers(Lists
                .newArrayList(MethodModifier.PUBLIC, MethodModifier.STATIC));
        config.setMetricName("yako");
        config.setSpanTemplate("yak(): {{0}}, {{1}} => {{?}}");
        return config;
    }

    private static void updateAllFields(PointcutConfig config) {
        if (config.getCaptureItems().contains(CaptureItem.TRACE)) {
            config.setCaptureItems(ImmutableList.of(CaptureItem.METRIC, CaptureItem.SPAN));
        } else {
            config.setCaptureItems(ImmutableList.of(CaptureItem.TRACE));
        }
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
        config.setSpanTemplate(config.getSpanTemplate() + "f");
    }
}
