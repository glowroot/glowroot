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
        container = InformantContainer.create(0, false);
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
        config = updateAllFields(config);
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
        config = updateAllFields(config);
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
        config = updateAllFields(config);
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
        config = updateAllFields(config);
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
        config = updateAllFields(config);
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
        String uniqueHash = container.getInformant().addPointcutConfig(config);
        // when
        config = updateAllFields(config);
        container.getInformant().updatePointcutConfig(uniqueHash, config);
        // then
        List<PointcutConfig> pointcuts = container.getInformant().getPointcutConfigs();
        assertThat(pointcuts).hasSize(1);
        assertThat(pointcuts.get(0)).isEqualTo(config);
    }

    @Test
    public void shouldDeletePointcutConfig() throws Exception {
        // given
        PointcutConfig pointcut = createPointcutConfig();
        String uniqueHash = container.getInformant().addPointcutConfig(pointcut);
        // when
        container.getInformant().removePointcutConfig(uniqueHash);
        // then
        List<PointcutConfig> pointcuts = container.getInformant().getPointcutConfigs();
        assertThat(pointcuts).isEmpty();
    }

    private static GeneralConfig updateAllFields(GeneralConfig config) {
        GeneralConfig updatedConfig = new GeneralConfig();
        updatedConfig.setVersionHash(config.getVersionHash());
        updatedConfig.setEnabled(!config.isEnabled());
        updatedConfig.setStoreThresholdMillis(config.getStoreThresholdMillis() + 1);
        updatedConfig.setStuckThresholdSeconds(config.getStuckThresholdSeconds() + 1);
        updatedConfig.setMaxSpans(config.getMaxSpans() + 1);
        updatedConfig.setSnapshotExpirationHours(config.getSnapshotExpirationHours() + 1);
        updatedConfig.setRollingSizeMb(config.getRollingSizeMb() + 1);
        updatedConfig.setWarnOnSpanOutsideTrace(!config.isWarnOnSpanOutsideTrace());
        return updatedConfig;
    }

    private static CoarseProfilingConfig updateAllFields(CoarseProfilingConfig config) {
        CoarseProfilingConfig updatedConfig = new CoarseProfilingConfig();
        updatedConfig.setVersionHash(config.getVersionHash());
        updatedConfig.setEnabled(!config.isEnabled());
        updatedConfig.setInitialDelayMillis(config.getInitialDelayMillis() + 1);
        updatedConfig.setIntervalMillis(config.getIntervalMillis() + 1);
        updatedConfig.setTotalSeconds(config.getTotalSeconds() + 1);
        return updatedConfig;
    }

    private static FineProfilingConfig updateAllFields(FineProfilingConfig config) {
        FineProfilingConfig updatedConfig = new FineProfilingConfig();
        updatedConfig.setVersionHash(config.getVersionHash());
        updatedConfig.setEnabled(!config.isEnabled());
        updatedConfig.setTracePercentage(config.getTracePercentage() + 1);
        updatedConfig.setIntervalMillis(config.getIntervalMillis() + 1);
        updatedConfig.setTotalSeconds(config.getTotalSeconds() + 1);
        return updatedConfig;
    }

    private static UserConfig updateAllFields(UserConfig config) {
        UserConfig updatedConfig = new UserConfig();
        updatedConfig.setVersionHash(config.getVersionHash());
        updatedConfig.setEnabled(!config.isEnabled());
        updatedConfig.setUserId(config.getUserId() + "x");
        updatedConfig.setStoreThresholdMillis(config.getStoreThresholdMillis() + 1);
        updatedConfig.setFineProfiling(!config.isFineProfiling());
        return updatedConfig;
    }

    private static PluginConfig updateAllFields(PluginConfig config) {
        PluginConfig updatedConfig = new PluginConfig();
        updatedConfig.setVersionHash(config.getVersionHash());
        updatedConfig.setEnabled(!config.isEnabled());
        boolean starredHeadline = (Boolean) config.getProperty("starredHeadline");
        updatedConfig.setProperty("starredHeadline", !starredHeadline);
        String alternateHeadline = (String) config.getProperty("alternateHeadline");
        updatedConfig.setProperty("alternateHeadline", alternateHeadline + "x");
        String hasDefaultVal = (String) config.getProperty("hasDefaultVal");
        updatedConfig.setProperty("hasDefaultVal", hasDefaultVal + "x");
        return updatedConfig;
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

    private static PointcutConfig updateAllFields(PointcutConfig config) {
        PointcutConfig updatedConfig = new PointcutConfig();
        updatedConfig.setVersionHash(config.getVersionHash());
        if (config.getCaptureItems().contains(CaptureItem.TRACE)) {
            updatedConfig.setCaptureItems(ImmutableList.of(CaptureItem.METRIC, CaptureItem.SPAN));
        } else {
            updatedConfig.setCaptureItems(ImmutableList.of(CaptureItem.TRACE));
        }
        updatedConfig.setTypeName(config.getTypeName() + "a");
        updatedConfig.setMethodName(config.getMethodName() + "b");
        if (config.getMethodArgTypeNames().size() == 0) {
            updatedConfig.setMethodArgTypeNames(ImmutableList.of("java.lang.String"));
        } else {
            updatedConfig.setMethodArgTypeNames(ImmutableList.of(config
                    .getMethodArgTypeNames().get(0) + "c"));
        }
        updatedConfig.setMethodReturnTypeName(config.getMethodReturnTypeName() + "d");
        if (config.getMethodModifiers().contains(MethodModifier.PUBLIC)) {
            updatedConfig.setMethodModifiers(ImmutableList.of(MethodModifier.PRIVATE));
        } else {
            updatedConfig.setMethodModifiers(ImmutableList.of(MethodModifier.PUBLIC,
                    MethodModifier.STATIC));
        }
        updatedConfig.setMetricName(config.getMetricName() + "e");
        updatedConfig.setSpanTemplate(config.getSpanTemplate() + "f");
        return updatedConfig;
    }
}
