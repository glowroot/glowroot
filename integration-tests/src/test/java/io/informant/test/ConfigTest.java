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

import static org.fest.assertions.api.Assertions.assertThat;
import io.informant.testkit.CoarseProfilingConfig;
import io.informant.testkit.FineProfilingConfig;
import io.informant.testkit.GeneralConfig;
import io.informant.testkit.InformantContainer;
import io.informant.testkit.PluginConfig;
import io.informant.testkit.PointcutConfig;
import io.informant.testkit.PointcutConfig.CaptureItem;
import io.informant.testkit.PointcutConfig.MethodModifier;
import io.informant.testkit.UserConfig;

import java.util.List;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ConfigTest {

    private static final String PLUGIN_ID = "io.informant:informant-integration-tests";

    private static InformantContainer container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = InformantContainer.create();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
    }

    @After
    public void afterEachTest() throws Exception {
        container.getInformant().cleanUpAfterEachTest();
    }

    @Test
    public void shouldUpdateGeneralConfig() throws Exception {
        // given
        GeneralConfig config = container.getInformant().getGeneralConfig();
        // when
        updateAllFields(config);
        container.getInformant().updateGeneralConfig(config);
        // then
        GeneralConfig updatedConfig = container.getInformant().getGeneralConfig();
        assertThat(updatedConfig).isEqualTo(config);
    }

    @Test
    public void shouldUpdateCoarseProfilingConfig() throws Exception {
        // given
        CoarseProfilingConfig config = container.getInformant().getCoarseProfilingConfig();
        // when
        updateAllFields(config);
        container.getInformant().updateCoarseProfilingConfig(config);
        // then
        CoarseProfilingConfig updatedConfig = container.getInformant().getCoarseProfilingConfig();
        assertThat(updatedConfig).isEqualTo(config);
    }

    @Test
    public void shouldUpdateFineProfilingConfig() throws Exception {
        // given
        FineProfilingConfig config = container.getInformant().getFineProfilingConfig();
        // when
        updateAllFields(config);
        container.getInformant().updateFineProfilingConfig(config);
        // then
        FineProfilingConfig updatedConfig = container.getInformant().getFineProfilingConfig();
        assertThat(updatedConfig).isEqualTo(config);
    }

    @Test
    public void shouldUpdateUserConfig() throws Exception {
        // given
        UserConfig config = container.getInformant().getUserConfig();
        // when
        updateAllFields(config);
        container.getInformant().updateUserConfig(config);
        // then
        UserConfig updatedConfig = container.getInformant().getUserConfig();
        assertThat(updatedConfig).isEqualTo(config);
    }

    @Test
    public void shouldUpdatePluginConfig() throws Exception {
        // given
        PluginConfig config = container.getInformant().getPluginConfig(PLUGIN_ID);
        // when
        updateAllFields(config);
        container.getInformant().updatePluginConfig(PLUGIN_ID, config);
        // then
        PluginConfig updatedConfig = container.getInformant().getPluginConfig(PLUGIN_ID);
        assertThat(updatedConfig).isEqualTo(config);
    }

    @Test
    public void shouldInsertPointcutConfig() throws Exception {
        // given
        PointcutConfig config = createPointcutConfig();
        // when
        container.getInformant().addPointcutConfig(config);
        // then
        List<PointcutConfig> pointcuts = container.getInformant().getPointcutConfigs();
        assertThat(pointcuts).hasSize(1);
        assertThat(pointcuts.get(0)).isEqualTo(config);
    }

    @Test
    public void shouldUpdatePointcutConfig() throws Exception {
        // given
        PointcutConfig config = createPointcutConfig();
        String version = container.getInformant().addPointcutConfig(config);
        // when
        updateAllFields(config);
        container.getInformant().updatePointcutConfig(version, config);
        // then
        List<PointcutConfig> pointcuts = container.getInformant().getPointcutConfigs();
        assertThat(pointcuts).hasSize(1);
        assertThat(pointcuts.get(0)).isEqualTo(config);
    }

    @Test
    public void shouldDeletePointcutConfig() throws Exception {
        // given
        PointcutConfig pointcut = createPointcutConfig();
        String version = container.getInformant().addPointcutConfig(pointcut);
        // when
        container.getInformant().removePointcutConfig(version);
        // then
        List<PointcutConfig> pointcuts = container.getInformant().getPointcutConfigs();
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
