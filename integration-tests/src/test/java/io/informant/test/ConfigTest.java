/**
 * Copyright 2011-2012 the original author or authors.
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
import io.informant.testkit.Config.CoarseProfilingConfig;
import io.informant.testkit.Config.CoreConfig;
import io.informant.testkit.Config.FineProfilingConfig;
import io.informant.testkit.Config.PluginConfig;
import io.informant.testkit.Config.UserTracingConfig;
import io.informant.testkit.InformantContainer;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

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
    public void shouldUpdateCoreConfig() throws Exception {
        // given
        CoreConfig config = container.getInformant().getCoreConfig();
        // when
        config = updateAllFields(config);
        container.getInformant().updateCoreConfig(config);
        // then
        CoreConfig updatedConfig = container.getInformant().getCoreConfig();
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
    public void shouldUpdateUserTracingConfig() throws Exception {
        // given
        UserTracingConfig config = container.getInformant().getUserTracingConfig();
        // when
        config = updateAllFields(config);
        container.getInformant().updateUserTracingConfig(config);
        // then
        UserTracingConfig updatedConfig = container.getInformant().getUserTracingConfig();
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

    private static CoreConfig updateAllFields(CoreConfig config) {
        CoreConfig updatedConfig = new CoreConfig();
        updatedConfig.setEnabled(!config.isEnabled());
        updatedConfig.setStoreThresholdMillis(config.getStoreThresholdMillis() + 1);
        updatedConfig.setStuckThresholdSeconds(config.getStuckThresholdSeconds() + 1);
        updatedConfig.setMaxSpans(config.getMaxSpans() + 1);
        updatedConfig.setRollingSizeMb(config.getRollingSizeMb() + 1);
        updatedConfig.setWarnOnSpanOutsideTrace(!config.isWarnOnSpanOutsideTrace());
        updatedConfig.setMetricPeriodMillis(config.getMetricPeriodMillis() + 1);
        return updatedConfig;
    }

    private static CoarseProfilingConfig updateAllFields(CoarseProfilingConfig config) {
        CoarseProfilingConfig updatedConfig = new CoarseProfilingConfig();
        updatedConfig.setEnabled(!config.isEnabled());
        updatedConfig.setInitialDelayMillis(config.getInitialDelayMillis() + 1);
        updatedConfig.setIntervalMillis(config.getIntervalMillis() + 1);
        updatedConfig.setTotalSeconds(config.getTotalSeconds() + 1);
        return updatedConfig;
    }

    private static FineProfilingConfig updateAllFields(FineProfilingConfig config) {
        FineProfilingConfig updatedConfig = new FineProfilingConfig();
        updatedConfig.setEnabled(!config.isEnabled());
        updatedConfig.setTracePercentage(config.getTracePercentage() + 1);
        updatedConfig.setIntervalMillis(config.getIntervalMillis() + 1);
        updatedConfig.setTotalSeconds(config.getTotalSeconds() + 1);
        return updatedConfig;
    }

    private static UserTracingConfig updateAllFields(UserTracingConfig config) {
        UserTracingConfig updatedConfig = new UserTracingConfig();
        updatedConfig.setEnabled(!config.isEnabled());
        updatedConfig.setUserId(config.getUserId() + "x");
        updatedConfig.setStoreThresholdMillis(config.getStoreThresholdMillis() + 1);
        updatedConfig.setFineProfiling(!config.isFineProfiling());
        return updatedConfig;
    }

    private static PluginConfig updateAllFields(PluginConfig config) {
        PluginConfig updatedConfig = new PluginConfig();
        updatedConfig.setEnabled(!config.isEnabled());
        boolean starredDescription = (Boolean) config.getProperty("starredDescription");
        updatedConfig.setProperty("starredDescription", !starredDescription);
        String alternateDescription = (String) config.getProperty("alternateDescription");
        updatedConfig.setProperty("alternateDescription", alternateDescription + "x");
        String hasDefaultVal = (String) config.getProperty("hasDefaultVal");
        updatedConfig.setProperty("hasDefaultVal", hasDefaultVal + "x");
        return updatedConfig;
    }
}
